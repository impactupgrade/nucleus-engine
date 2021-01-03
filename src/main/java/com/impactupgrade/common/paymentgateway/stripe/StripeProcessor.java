package com.impactupgrade.common.paymentgateway.stripe;

import com.google.common.base.Strings;
import com.impactupgrade.common.environment.Environment;
import com.impactupgrade.common.paymentgateway.DonationService;
import com.impactupgrade.common.paymentgateway.DonorService;
import com.impactupgrade.common.paymentgateway.model.PaymentGatewayEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Payout;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import org.apache.camel.Exchange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Calendar;
import java.util.List;
import java.util.Optional;

public class StripeProcessor {

  private static final Logger log = LogManager.getLogger(StripeProcessor.class);

  private final Environment env;
  private final StripeClient stripeClient;
  private final DonorService donorService;
  private final DonationService donationService;

  public StripeProcessor(Environment env) {
    this.env = env;
    stripeClient = new StripeClient(env);
    donorService = new DonorService(env);
    donationService = new DonationService(env);
  }

  public void processEvent(Event event, Exchange exchange) {
    EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
    StripeObject stripeObject = dataObjectDeserializer.getObject().get();
    exchange.getMessage().setBody(stripeObject);
  }

  public void processEvent(StripeObject stripeObject, Exchange exchange) throws Exception {
    PaymentGatewayEvent paymentGatewayEvent = env.buildPaymentGatewayEvent();

    switch (event.getType()) {
      case "charge.succeeded": {
        Charge charge = (Charge) stripeObject;
        log.info("found charge {}", charge.getId());

        if (!Strings.isNullOrEmpty(charge.getPaymentIntent())) {
          log.info("charge {} is part of an intent; skipping and waiting for the payment_intent.succeeded event...", charge.getId());
        } else {
          processCharge(charge, paymentGatewayEvent);
          donorService.processAccount(paymentGatewayEvent);
          donationService.createDonation(paymentGatewayEvent);
        }

        break;
      }
      case "payment_intent.succeeded": {
        PaymentIntent paymentIntent = (PaymentIntent) stripeObject;
        log.info("found payment intent {}", paymentIntent.getId());

        processPaymentIntent(paymentIntent, paymentGatewayEvent);
        donorService.processAccount(paymentGatewayEvent);
        donationService.createDonation(paymentGatewayEvent);

        break;
      }
      case "charge.failed": {
        Charge charge = (Charge) stripeObject;
        log.info("found charge {}", charge.getId());

        if (!Strings.isNullOrEmpty(charge.getPaymentIntent())) {
          log.info("charge {} is part of an intent; skipping...", charge.getId());
        } else {
          processCharge(charge, paymentGatewayEvent);
          donorService.processAccount(paymentGatewayEvent);
          donationService.createDonation(paymentGatewayEvent);
        }

        break;
      }
      case "charge.refunded": {
        // TODO: Not completely understanding this one just yet, but it appears a recent API change
        // is sending Charges instead of Refunds in this case...
        Refund refund;
        if (stripeObject instanceof Charge) {
          Charge charge = (Charge) stripeObject;
          refund = charge.getRefunds().getData().get(0);
        } else {
          refund = (Refund) stripeObject;
        }
        log.info("found refund {}", refund.getId());

        paymentGatewayEvent.initStripe(refund);
        donationService.refundDonation(paymentGatewayEvent);
        break;
      }
      case "customer.subscription.created": {
        Subscription subscription = (Subscription) stripeObject;
        log.info("found subscription {}", subscription.getId());

        if ("true".equalsIgnoreCase(subscription.getMetadata().get("auto-migrated"))) {
          // This subscription comes from an auto-migration path, most likely from PaymentSpring through
          // the Donor Portal. In this case, the migration will have already updated the existing
          // recurring donation. Prevent this from creating another one.

          log.info("skipping the auto-migrated subscription");
        } else if ("trialing".equalsIgnoreCase(subscription.getStatus())) {
          // IE, handle the subscription if it's going to happen in the future. Otherwise, if it started already,
          // the incoming payment will handle it. This prevents timing issues for start-now subscriptions, where
          // we'll likely get the subscription and charge near instantaneously (but on different requests/threads).

          Customer createdSubscriptionCustomer = stripeClient.getCustomer(subscription.getCustomer());
          log.info("found customer {}", createdSubscriptionCustomer.getId());

          paymentGatewayEvent.initStripe(subscription, createdSubscriptionCustomer);
          donorService.processAccount(paymentGatewayEvent);
          donationService.processSubscription(paymentGatewayEvent);
        } else {
          log.info("subscription is not trialing, so doing nothing; allowing the charge.succeeded event to create the recurring donation");
        }
        break;
      }
      case "customer.subscription.deleted": {
        Subscription subscription = (Subscription) stripeObject;
        log.info("found subscription {}", subscription.getId());
        Customer deletedSubscriptionCustomer = stripeClient.getCustomer(subscription.getCustomer());
        log.info("found customer {}", deletedSubscriptionCustomer.getId());

        paymentGatewayEvent.initStripe(subscription, deletedSubscriptionCustomer);
        // NOTE: the customer.subscription.deleted name is a little misleading -- it instead means
        // that the subscription has been canceled immediately, either by manual action or subscription settings. So,
        // simply close the recurring donation.
        donationService.closeRecurringDonation(paymentGatewayEvent);
        break;
      }
      case "payout.paid": {
        Payout payout = (Payout) stripeObject;
        log.info("found payout {}", payout.getId());
        List<BalanceTransaction> balanceTransactions = stripeClient.getBalanceTransactions(payout);
        for (BalanceTransaction balanceTransaction : balanceTransactions) {
          if (balanceTransaction.getSourceObject() instanceof Charge) {
            Charge charge = (Charge) balanceTransaction.getSourceObject();
            log.info("found charge {}", charge.getId());

            if (Strings.isNullOrEmpty(charge.getPaymentIntent())) {
              processCharge(charge, Optional.of(balanceTransaction), paymentGatewayEvent);
            } else {
              log.info("found intent {}", charge.getPaymentIntent());
              processPaymentIntent(charge.getPaymentIntentObject(), Optional.of(balanceTransaction), paymentGatewayEvent);
            }
            paymentGatewayEvent.setDepositId(payout.getId());
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(payout.getArrivalDate() * 1000);
            paymentGatewayEvent.setDepositDate(c);
            donationService.chargeDeposited(paymentGatewayEvent);
          }
        }
        break;
      }
      default:
        log.info("unhandled Stripe webhook event type: {}", event.getType());
    }
  }

  private void processCharge(Charge charge, PaymentGatewayEvent paymentGatewayEvent)
      throws StripeException {
    Optional<BalanceTransaction> chargeBalanceTransaction;
    if (Strings.isNullOrEmpty(charge.getBalanceTransaction())) {
      chargeBalanceTransaction = Optional.empty();
    } else {
      chargeBalanceTransaction = Optional.of(stripeClient.getBalanceTransaction(charge.getBalanceTransaction()));
      log.info("found balance transaction {}", chargeBalanceTransaction.get().getId());
    }

    processCharge(charge, chargeBalanceTransaction, paymentGatewayEvent);
  }

  private void processCharge(Charge charge, Optional<BalanceTransaction> chargeBalanceTransaction,
      PaymentGatewayEvent paymentGatewayEvent) throws StripeException {
    Customer chargeCustomer = stripeClient.getCustomer(charge.getCustomer());
    log.info("found customer {}", chargeCustomer.getId());

    Optional<Invoice> chargeInvoice;
    if (Strings.isNullOrEmpty(charge.getInvoice())) {
      chargeInvoice = Optional.empty();
    } else {
      chargeInvoice = Optional.of(stripeClient.getInvoice(charge.getInvoice()));
      log.info("found invoice {}", chargeInvoice.get().getId());
    }

    paymentGatewayEvent.initStripe(charge, chargeCustomer, chargeInvoice, chargeBalanceTransaction);
  }

  private void processPaymentIntent(PaymentIntent paymentIntent, PaymentGatewayEvent paymentGatewayEvent) throws StripeException {
    Optional<BalanceTransaction> chargeBalanceTransaction = Optional.empty();
    if (paymentIntent.getCharges() != null && !paymentIntent.getCharges().getData().isEmpty()) {
      if (paymentIntent.getCharges().getData().size() == 1) {
        String balanceTransactionId = paymentIntent.getCharges().getData().get(0).getBalanceTransaction();
        if (!Strings.isNullOrEmpty(balanceTransactionId)) {
          chargeBalanceTransaction = Optional.of(stripeClient.getBalanceTransaction(balanceTransactionId));
          log.info("found balance transaction {}", chargeBalanceTransaction.get().getId());
        }
      }
    }

    processPaymentIntent(paymentIntent, chargeBalanceTransaction, paymentGatewayEvent);
  }

  private void processPaymentIntent(PaymentIntent paymentIntent, Optional<BalanceTransaction> chargeBalanceTransaction,
      PaymentGatewayEvent paymentGatewayEvent) throws StripeException {
    // TODO: For TER, the customers aren't always included in the webhook -- not sure why. For now, if that's the case,
    // retrieve the whole PaymentIntent and try again...
    Customer chargeCustomer;
    if (Strings.isNullOrEmpty(paymentIntent.getCustomer())) {
      log.info("payment intent {} was missing the customer id in the webhook; retrieving the full payment intent...", paymentIntent.getId());
      PaymentIntent fullPaymentIntent = stripeClient.getPaymentIntent(paymentIntent.getId());
      chargeCustomer = stripeClient.getCustomer(fullPaymentIntent.getCustomer());
    } else {
      chargeCustomer = stripeClient.getCustomer(paymentIntent.getCustomer());
    }
    log.info("found customer {}", chargeCustomer.getId());

    Optional<Invoice> chargeInvoice;
    if (Strings.isNullOrEmpty(paymentIntent.getInvoice())) {
      chargeInvoice = Optional.empty();
    } else {
      chargeInvoice = Optional.of(stripeClient.getInvoice(paymentIntent.getInvoice()));
      log.info("found invoice {}", chargeInvoice.get().getId());
    }

    paymentGatewayEvent.initStripe(paymentIntent, chargeCustomer, chargeInvoice, chargeBalanceTransaction);
  }

  // TODO: To be wrapped in a REST call for the UI to kick off, etc.
//  public void verifyAndReplayStripeCharges(Date startDate, Date endDate) throws StripeException {
//    SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
//    Iterable<Charge> charges = stripeClient.getAllCharges(startDate, endDate);
//    int count = 0;
//    for (Charge charge : charges) {
//      if (!charge.getStatus().equalsIgnoreCase("succeeded")
//          || charge.getPaymentIntentObject() != null && !charge.getPaymentIntentObject().getStatus().equalsIgnoreCase("succeeded")) {
//        continue;
//      }
//
//      count++;
//
//      try {
//        String paymentIntentId = charge.getPaymentIntent();
//        String chargeId = charge.getId();
//        Optional<SObject> opportunity = Optional.empty();
//        if (!Strings.isNullOrEmpty(paymentIntentId)) {
//          opportunity = sfdcClient.getDonationByTransactionId("Stripe_Charge_ID__c", paymentIntentId);
//        }
//        if (opportunity.isEmpty()) {
//          opportunity = sfdcClient.getDonationByTransactionId("Stripe_Charge_ID__c", chargeId);
//        }
//
//        if (opportunity.isEmpty()) {
//          System.out.println("(" + count + ") MISSING: " + chargeId + "/" + paymentIntentId + " " + SDF.format(charge.getCreated() * 1000));
//
//          if (Strings.isNullOrEmpty(paymentIntentId)) {
//            processEvent("charge.succeeded", charge);
//          } else {
//            processEvent("payment_intent.succeeded", charge.getPaymentIntentObject());
//          }
//        }
//      } catch (Exception e) {
//        e.printStackTrace();
//      }
//    }
//  }

  // TODO: To be wrapped in a REST call for the UI to kick off, etc.
//  public void replayStripePayouts(Date startDate, Date endDate) throws Exception {
//    SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
//    Iterable<Payout> payouts = stripeClient.getPayouts(startDate, endDate, 100);
//    for (Payout payout : payouts) {
//      System.out.println(SDF.format(new Date(payout.getArrivalDate() * 1000)));
//      processEvent("payout.paid", payout);
//    }
//  }
}
