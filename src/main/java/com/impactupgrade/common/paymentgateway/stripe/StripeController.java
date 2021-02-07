package com.impactupgrade.common.paymentgateway.stripe;

import com.google.common.base.Strings;
import com.impactupgrade.common.environment.Environment;
import com.impactupgrade.common.environment.RequestEnvironment;
import com.impactupgrade.common.paymentgateway.DonationService;
import com.impactupgrade.common.paymentgateway.DonorService;
import com.impactupgrade.common.paymentgateway.model.PaymentGatewayEvent;
import com.impactupgrade.common.util.LoggingUtil;
import com.sforce.soap.partner.sobject.SObject;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * This service acts as the central webhook endpoint for Stripe events, handling everything from
 * successful/failed charges, subscription changes, etc. It also houses anything Stripe-specific called by the
 * Portal UI.
 */
@Path("/stripe")
public class StripeController {

  private static final Logger log = LogManager.getLogger(StripeController.class);

  private final Environment env;
  private final DonorService donorService;
  private final DonationService donationService;

  public StripeController(Environment env) {
    this.env = env;
    donorService = env.donorService();
    donationService = env.donationService();
  }

  /**
   * Receives and processes *all* webhooks from Stripe.
   *
   * @param json
   * @return
   */
  @Path("/webhook")
  @POST
  @Produces(MediaType.APPLICATION_JSON)
  public Response webhook(String json, @Context HttpServletRequest request) {
    LoggingUtil.verbose(log, json);

    // stripe-java uses GSON, so Jersey/Jackson won't work on its own
    Event event = Event.GSON.fromJson(json, Event.class);

    EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
    StripeObject stripeObject;
    if (dataObjectDeserializer.getObject().isPresent()) {
      stripeObject = dataObjectDeserializer.getObject().get();
    } else {
      log.error("Stripe deserialization failed, probably due to an API version mismatch.");
      return Response.status(500).build();
    }

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      // don't log the whole thing -- can be found in Stripe's dashboard -> Developers -> Webhooks
      // log this within the new thread for traceability's sake
      log.info("received event {}: {}", event.getType(), event.getId());

      try {
        processEvent(event.getType(), stripeObject, RequestEnvironment.fromRequest(request));
      } catch (Exception e) {
        log.error("failed to process the Stripe event", e);
        // TODO: email notification?
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  // Public so that utilities can call this directly.
  public void processEvent(String eventType, StripeObject stripeObject, RequestEnvironment requestEnv) throws Exception {
    PaymentGatewayEvent paymentGatewayEvent = new PaymentGatewayEvent(env, requestEnv);

    switch (eventType) {
      case "charge.succeeded" -> {
        Charge charge = (Charge) stripeObject;
        log.info("found charge {}", charge.getId());

        if (!Strings.isNullOrEmpty(charge.getPaymentIntent())) {
          log.info("charge {} is part of an intent; skipping and waiting for the payment_intent.succeeded event...", charge.getId());
        } else {
          processCharge(charge, paymentGatewayEvent, requestEnv);
          donorService.processAccount(paymentGatewayEvent);
          donationService.createDonation(paymentGatewayEvent);
        }
      }
      case "payment_intent.succeeded" -> {
        PaymentIntent paymentIntent = (PaymentIntent) stripeObject;
        log.info("found payment intent {}", paymentIntent.getId());

        processPaymentIntent(paymentIntent, paymentGatewayEvent, requestEnv);
        donorService.processAccount(paymentGatewayEvent);
        donationService.createDonation(paymentGatewayEvent);
      }
      case "charge.failed" -> {
        Charge charge = (Charge) stripeObject;
        log.info("found charge {}", charge.getId());

        if (!Strings.isNullOrEmpty(charge.getPaymentIntent())) {
          log.info("charge {} is part of an intent; skipping...", charge.getId());
        } else {
          processCharge(charge, paymentGatewayEvent, requestEnv);
          donorService.processAccount(paymentGatewayEvent);
          donationService.createDonation(paymentGatewayEvent);
        }
      }
      case "charge.refunded" -> {
        // TODO: Not completely understanding this one just yet, but it appears a recent API change
        // is sending Charges instead of Refunds in this case...
        Refund refund;
        if (stripeObject instanceof Charge charge) {
          refund = charge.getRefunds().getData().get(0);
        } else {
          refund = (Refund) stripeObject;
        }
        log.info("found refund {}", refund.getId());

        paymentGatewayEvent.initStripe(refund);
        donationService.refundDonation(paymentGatewayEvent);
      }
      case "customer.subscription.created" -> {
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

          Customer createdSubscriptionCustomer = requestEnv.stripeClient().getCustomer(subscription.getCustomer());
          log.info("found customer {}", createdSubscriptionCustomer.getId());

          paymentGatewayEvent.initStripe(subscription, createdSubscriptionCustomer);
          donorService.processAccount(paymentGatewayEvent);
          donationService.processSubscription(paymentGatewayEvent);
        } else {
          log.info("subscription is not trialing, so doing nothing; allowing the charge.succeeded event to create the recurring donation");
        }
      }
      case "customer.subscription.deleted" -> {
        Subscription subscription = (Subscription) stripeObject;
        log.info("found subscription {}", subscription.getId());
        Customer deletedSubscriptionCustomer = requestEnv.stripeClient().getCustomer(subscription.getCustomer());
        log.info("found customer {}", deletedSubscriptionCustomer.getId());

        paymentGatewayEvent.initStripe(subscription, deletedSubscriptionCustomer);
        // NOTE: the customer.subscription.deleted name is a little misleading -- it instead means
        // that the subscription has been canceled immediately, either by manual action or subscription settings. So,
        // simply close the recurring donation.
        donationService.closeRecurringDonation(paymentGatewayEvent);
      }
      case "payout.paid" -> {
        Payout payout = (Payout) stripeObject;
        log.info("found payout {}", payout.getId());
        List<BalanceTransaction> balanceTransactions = requestEnv.stripeClient().getBalanceTransactions(payout);
        for (BalanceTransaction balanceTransaction : balanceTransactions) {
          if (balanceTransaction.getSourceObject() instanceof Charge charge) {
            log.info("found charge {}", charge.getId());

            if (Strings.isNullOrEmpty(charge.getPaymentIntent())) {
              processCharge(charge, Optional.of(balanceTransaction), paymentGatewayEvent, requestEnv);
            } else {
              log.info("found intent {}", charge.getPaymentIntent());
              processPaymentIntent(charge.getPaymentIntentObject(), Optional.of(balanceTransaction), paymentGatewayEvent, requestEnv);
            }
            paymentGatewayEvent.setDepositId(payout.getId());
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(payout.getArrivalDate() * 1000);
            paymentGatewayEvent.setDepositDate(c);
            donationService.chargeDeposited(paymentGatewayEvent);
          }
        }
      }
      default -> log.info("unhandled Stripe webhook event type: {}", eventType);
    }
  }

  private void processCharge(Charge charge, PaymentGatewayEvent paymentGatewayEvent, RequestEnvironment requestEnv)
      throws StripeException {
    Optional<BalanceTransaction> chargeBalanceTransaction;
    if (Strings.isNullOrEmpty(charge.getBalanceTransaction())) {
      chargeBalanceTransaction = Optional.empty();
    } else {
      chargeBalanceTransaction = Optional.of(requestEnv.stripeClient().getBalanceTransaction(charge.getBalanceTransaction()));
      log.info("found balance transaction {}", chargeBalanceTransaction.get().getId());
    }

    processCharge(charge, chargeBalanceTransaction, paymentGatewayEvent, requestEnv);
  }

  private void processCharge(Charge charge, Optional<BalanceTransaction> chargeBalanceTransaction,
      PaymentGatewayEvent paymentGatewayEvent, RequestEnvironment requestEnv) throws StripeException {
    Customer chargeCustomer = requestEnv.stripeClient().getCustomer(charge.getCustomer());
    log.info("found customer {}", chargeCustomer.getId());

    Optional<Invoice> chargeInvoice;
    if (Strings.isNullOrEmpty(charge.getInvoice())) {
      chargeInvoice = Optional.empty();
    } else {
      chargeInvoice = Optional.of(requestEnv.stripeClient().getInvoice(charge.getInvoice()));
      log.info("found invoice {}", chargeInvoice.get().getId());
    }

    paymentGatewayEvent.initStripe(charge, chargeCustomer, chargeInvoice, chargeBalanceTransaction);
  }

  private void processPaymentIntent(PaymentIntent paymentIntent, PaymentGatewayEvent paymentGatewayEvent,
      RequestEnvironment requestEnv) throws StripeException {
    Optional<BalanceTransaction> chargeBalanceTransaction = Optional.empty();
    if (paymentIntent.getCharges() != null && !paymentIntent.getCharges().getData().isEmpty()) {
      if (paymentIntent.getCharges().getData().size() == 1) {
        String balanceTransactionId = paymentIntent.getCharges().getData().get(0).getBalanceTransaction();
        if (!Strings.isNullOrEmpty(balanceTransactionId)) {
          chargeBalanceTransaction = Optional.of(requestEnv.stripeClient().getBalanceTransaction(balanceTransactionId));
          log.info("found balance transaction {}", chargeBalanceTransaction.get().getId());
        }
      }
    }

    processPaymentIntent(paymentIntent, chargeBalanceTransaction, paymentGatewayEvent, requestEnv);
  }

  private void processPaymentIntent(PaymentIntent paymentIntent, Optional<BalanceTransaction> chargeBalanceTransaction,
      PaymentGatewayEvent paymentGatewayEvent, RequestEnvironment requestEnv) throws StripeException {
    // TODO: For TER, the customers and/or metadata aren't always included in the webhook -- not sure why.
    //  For now, retrieve the whole PaymentIntent and try again...
    PaymentIntent fullPaymentIntent = requestEnv.stripeClient().getPaymentIntent(paymentIntent.getId());
    Customer chargeCustomer = requestEnv.stripeClient().getCustomer(fullPaymentIntent.getCustomer());
    log.info("found customer {}", chargeCustomer.getId());

    Optional<Invoice> chargeInvoice;
    if (Strings.isNullOrEmpty(fullPaymentIntent.getInvoice())) {
      chargeInvoice = Optional.empty();
    } else {
      chargeInvoice = Optional.of(requestEnv.stripeClient().getInvoice(fullPaymentIntent.getInvoice()));
      log.info("found invoice {}", chargeInvoice.get().getId());
    }

    paymentGatewayEvent.initStripe(fullPaymentIntent, chargeCustomer, chargeInvoice, chargeBalanceTransaction);
  }

  // TODO: To be wrapped in a REST call for the UI to kick off, etc.
  public void verifyAndReplayStripeCharges(Date startDate, Date endDate, RequestEnvironment requestEnv) throws StripeException {
    SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
    Iterable<Charge> charges = requestEnv.stripeClient().getAllCharges(startDate, endDate);
    int count = 0;
    for (Charge charge : charges) {
      if (!charge.getStatus().equalsIgnoreCase("succeeded")
          || charge.getPaymentIntentObject() != null && !charge.getPaymentIntentObject().getStatus().equalsIgnoreCase("succeeded")) {
        continue;
      }

      count++;

      try {
        String paymentIntentId = charge.getPaymentIntent();
        String chargeId = charge.getId();
        Optional<SObject> opportunity = Optional.empty();
        if (!Strings.isNullOrEmpty(paymentIntentId)) {
          // TODO: Needs pulled to CrmSourceService
          opportunity = env.sfdcClient().getDonationByTransactionId(paymentIntentId);
        }
        if (opportunity.isEmpty()) {
          // TODO: Needs pulled to CrmSourceService
          opportunity = env.sfdcClient().getDonationByTransactionId(chargeId);
        }

        if (opportunity.isEmpty()) {
          System.out.println("(" + count + ") MISSING: " + chargeId + "/" + paymentIntentId + " " + SDF.format(charge.getCreated() * 1000));

          if (Strings.isNullOrEmpty(paymentIntentId)) {
            processEvent("charge.succeeded", charge, requestEnv);
          } else {
            processEvent("payment_intent.succeeded", charge.getPaymentIntentObject(), requestEnv);
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  // TODO: To be wrapped in a REST call for the UI to kick off, etc.
  public void replayStripePayouts(Date startDate, Date endDate, RequestEnvironment requestEnv) throws Exception {
    SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
    Iterable<Payout> payouts = requestEnv.stripeClient().getPayouts(startDate, endDate, 100);
    for (Payout payout : payouts) {
      System.out.println(SDF.format(new Date(payout.getArrivalDate() * 1000)));
      processEvent("payout.paid", payout, requestEnv);
    }
  }
}
