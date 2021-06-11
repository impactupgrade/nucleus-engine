/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;
import com.impactupgrade.nucleus.util.LoggingUtil;
import com.impactupgrade.nucleus.util.TestUtil;
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

  protected final EnvironmentFactory envFactory;

  public StripeController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
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
  public Response webhook(String json, @Context HttpServletRequest request) throws Exception {
    LoggingUtil.verbose(log, json);
    Environment env = envFactory.init(request);

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

    // don't log the whole thing -- can be found in Stripe's dashboard -> Developers -> Webhooks
    // log this within the new thread for traceability's sake
    log.info("received event {}: {}", event.getType(), event.getId());

    if (TestUtil.SKIP_NEW_THREADS) {
      processEvent(event.getType(), stripeObject, env);
    } else {
      // takes a while, so spin it off as a new thread
      Runnable thread = () -> {
        try {
          processEvent(event.getType(), stripeObject, env);
        } catch (Exception e) {
          log.error("failed to process the Stripe event", e);
          // TODO: email notification?
        }
      };
      new Thread(thread).start();
    }

    return Response.status(200).build();
  }

  // Public so that utilities can call this directly.
  public void processEvent(String eventType, StripeObject stripeObject, Environment env) throws Exception {
    PaymentGatewayWebhookEvent paymentGatewayEvent = new PaymentGatewayWebhookEvent(env);

    switch (eventType) {
      case "charge.succeeded" -> {
        Charge charge = (Charge) stripeObject;
        log.info("found charge {}", charge.getId());

        if (!Strings.isNullOrEmpty(charge.getPaymentIntent())) {
          log.info("charge {} is part of an intent; skipping and waiting for the payment_intent.succeeded event...", charge.getId());
        } else {
          processCharge(charge, paymentGatewayEvent, env);
          env.donorService().processAccount(paymentGatewayEvent);
          env.donationService().createDonation(paymentGatewayEvent);
        }
      }
      case "payment_intent.succeeded" -> {
        PaymentIntent paymentIntent = (PaymentIntent) stripeObject;
        log.info("found payment intent {}", paymentIntent.getId());

        processPaymentIntent(paymentIntent, paymentGatewayEvent, env);
        env.donorService().processAccount(paymentGatewayEvent);
        env.donationService().createDonation(paymentGatewayEvent);
      }
      case "charge.failed" -> {
        Charge charge = (Charge) stripeObject;
        log.info("found charge {}", charge.getId());

        if (!Strings.isNullOrEmpty(charge.getPaymentIntent())) {
          log.info("charge {} is part of an intent; skipping...", charge.getId());
        } else {
          processCharge(charge, paymentGatewayEvent, env);
          env.donorService().processAccount(paymentGatewayEvent);
          env.donationService().createDonation(paymentGatewayEvent);
        }
      }
      case "payment_intent.payment_failed" -> {
        PaymentIntent paymentIntent = (PaymentIntent) stripeObject;
        log.info("found payment intent {}", paymentIntent.getId());

        processPaymentIntent(paymentIntent, paymentGatewayEvent, env);
        env.donorService().processAccount(paymentGatewayEvent);
        env.donationService().createDonation(paymentGatewayEvent);
      }
      case "charge.refunded" -> {
        // TODO: Not completely understanding this one just yet, but it appears a recent API change
        //  is sending Charges instead of Refunds in this case...
        Refund refund;
        if (stripeObject instanceof Charge charge) {
          refund = charge.getRefunds().getData().get(0);
        } else {
          refund = (Refund) stripeObject;
        }
        log.info("found refund {}", refund.getId());

        paymentGatewayEvent.initStripe(refund);
        env.donationService().refundDonation(paymentGatewayEvent);
      }
      case "customer.subscription.created" -> {
        Subscription subscription = (Subscription) stripeObject;
        log.info("found subscription {}", subscription.getId());

        if ("true".equalsIgnoreCase(subscription.getMetadata().get("auto-migrated"))) {
          // This subscription comes from an auto-migration path, such as PaymentSpring -> Stripe through
          // LJI's Donor Portal. In this case, the migration will have already updated the existing
          // recurring donation. Prevent this from creating another one.

          log.info("skipping the auto-migrated subscription");
        } else if ("trialing".equalsIgnoreCase(subscription.getStatus())) {
          // IE, handle the subscription if it's going to happen in the future. Otherwise, if it started already,
          // the incoming payment will handle it. This prevents timing issues for start-now subscriptions, where
          // we'll likely get the subscription and charge near instantaneously (but on different requests/threads).

          Customer createdSubscriptionCustomer = env.stripeClient().getCustomer(subscription.getCustomer());
          log.info("found customer {}", createdSubscriptionCustomer.getId());

          paymentGatewayEvent.initStripe(subscription, createdSubscriptionCustomer);
          env.donorService().processAccount(paymentGatewayEvent);
          env.donationService().processSubscription(paymentGatewayEvent);
        } else {
          log.info("subscription is not trialing, so doing nothing; allowing the charge.succeeded event to create the recurring donation");
        }
      }
      case "customer.subscription.deleted" -> {
        Subscription subscription = (Subscription) stripeObject;
        log.info("found subscription {}", subscription.getId());
        Customer deletedSubscriptionCustomer = env.stripeClient().getCustomer(subscription.getCustomer());
        log.info("found customer {}", deletedSubscriptionCustomer.getId());

        paymentGatewayEvent.initStripe(subscription, deletedSubscriptionCustomer);
        // NOTE: the customer.subscription.deleted name is a little misleading -- it instead means
        // that the subscription has been canceled immediately, either by manual action or subscription settings. So,
        // simply close the recurring donation.
        env.donationService().closeRecurringDonation(paymentGatewayEvent);
      }
      case "payout.paid" -> {
        Payout payout = (Payout) stripeObject;
        log.info("found payout {}", payout.getId());
        List<BalanceTransaction> balanceTransactions = env.stripeClient().getBalanceTransactions(payout);
        for (BalanceTransaction balanceTransaction : balanceTransactions) {
          if (balanceTransaction.getSourceObject() instanceof Charge charge) {
            log.info("found charge {}", charge.getId());

            // TODO: Better way to do this? Plus, ultimately need to process them in some way since the initial charge
            //  will show fees, but the reversal refund sends back the full amount.
            if (Strings.isNullOrEmpty(charge.getCustomer())) {
              log.info("skipping charge {}; no customer, so likely a reversal refund", charge.getId());
              continue;
            }

            if (Strings.isNullOrEmpty(charge.getPaymentIntent())) {
              processCharge(charge, Optional.of(balanceTransaction), paymentGatewayEvent, env);
            } else {
              log.info("found intent {}", charge.getPaymentIntent());
              processPaymentIntent(charge.getPaymentIntentObject(), Optional.of(balanceTransaction), paymentGatewayEvent, env);
            }
            paymentGatewayEvent.setDepositId(payout.getId());
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(payout.getArrivalDate() * 1000);
            paymentGatewayEvent.setDepositDate(c);
            env.donationService().chargeDeposited(paymentGatewayEvent);
          }
        }
      }
      default -> log.info("unhandled Stripe webhook event type: {}", eventType);
    }
  }

  private void processCharge(Charge charge, PaymentGatewayWebhookEvent paymentGatewayEvent, Environment env)
      throws StripeException {
    Optional<BalanceTransaction> chargeBalanceTransaction;
    if (Strings.isNullOrEmpty(charge.getBalanceTransaction())) {
      chargeBalanceTransaction = Optional.empty();
    } else {
      chargeBalanceTransaction = Optional.of(env.stripeClient().getBalanceTransaction(charge.getBalanceTransaction()));
      log.info("found balance transaction {}", chargeBalanceTransaction.get().getId());
    }

    processCharge(charge, chargeBalanceTransaction, paymentGatewayEvent, env);
  }

  private void processCharge(Charge charge, Optional<BalanceTransaction> chargeBalanceTransaction,
      PaymentGatewayWebhookEvent paymentGatewayEvent, Environment env) throws StripeException {
    Customer chargeCustomer = env.stripeClient().getCustomer(charge.getCustomer());
    log.info("found customer {}", chargeCustomer.getId());

    Optional<Invoice> chargeInvoice;
    if (Strings.isNullOrEmpty(charge.getInvoice())) {
      chargeInvoice = Optional.empty();
    } else {
      chargeInvoice = Optional.of(env.stripeClient().getInvoice(charge.getInvoice()));
      log.info("found invoice {}", chargeInvoice.get().getId());
    }

    paymentGatewayEvent.initStripe(charge, chargeCustomer, chargeInvoice, chargeBalanceTransaction);
  }

  private void processPaymentIntent(PaymentIntent paymentIntent, PaymentGatewayWebhookEvent paymentGatewayEvent,
      Environment env) throws StripeException {
    Optional<BalanceTransaction> chargeBalanceTransaction = Optional.empty();
    if (paymentIntent.getCharges() != null && !paymentIntent.getCharges().getData().isEmpty()) {
      if (paymentIntent.getCharges().getData().size() == 1) {
        String balanceTransactionId = paymentIntent.getCharges().getData().get(0).getBalanceTransaction();
        if (!Strings.isNullOrEmpty(balanceTransactionId)) {
          chargeBalanceTransaction = Optional.of(env.stripeClient().getBalanceTransaction(balanceTransactionId));
          log.info("found balance transaction {}", chargeBalanceTransaction.get().getId());
        }
      }
    }

    processPaymentIntent(paymentIntent, chargeBalanceTransaction, paymentGatewayEvent, env);
  }

  private void processPaymentIntent(PaymentIntent paymentIntent, Optional<BalanceTransaction> chargeBalanceTransaction,
      PaymentGatewayWebhookEvent paymentGatewayEvent, Environment env) throws StripeException {
    // TODO: For TER, the customers and/or metadata aren't always included in the webhook -- not sure why.
    //  For now, retrieve the whole PaymentIntent and try again...
    PaymentIntent fullPaymentIntent = env.stripeClient().getPaymentIntent(paymentIntent.getId());
    Customer chargeCustomer = env.stripeClient().getCustomer(fullPaymentIntent.getCustomer());
    log.info("found customer {}", chargeCustomer.getId());

    Optional<Invoice> chargeInvoice;
    if (Strings.isNullOrEmpty(fullPaymentIntent.getInvoice())) {
      chargeInvoice = Optional.empty();
    } else {
      chargeInvoice = Optional.of(env.stripeClient().getInvoice(fullPaymentIntent.getInvoice()));
      log.info("found invoice {}", chargeInvoice.get().getId());
    }

    paymentGatewayEvent.initStripe(fullPaymentIntent, chargeCustomer, chargeInvoice, chargeBalanceTransaction);
  }

  // TODO: To be wrapped in a REST call for the UI to kick off, etc.
  public void verifyAndReplayStripeCharges(Date startDate, Date endDate, Environment env) throws StripeException {
    SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
    Iterable<Charge> charges = env.stripeClient().getAllCharges(startDate, endDate);
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
          // TODO: Needs pulled to CrmService
          opportunity = env.sfdcClient().getDonationByTransactionId(paymentIntentId);
        }
        if (opportunity.isEmpty()) {
          // TODO: Needs pulled to CrmService
          opportunity = env.sfdcClient().getDonationByTransactionId(chargeId);
        }

        if (opportunity.isEmpty()) {
          System.out.println("(" + count + ") MISSING: " + chargeId + "/" + paymentIntentId + " " + SDF.format(charge.getCreated() * 1000));

          if (Strings.isNullOrEmpty(paymentIntentId)) {
            processEvent("charge.succeeded", charge, env);
          } else {
            processEvent("payment_intent.succeeded", charge.getPaymentIntentObject(), env);
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  // TODO: To be wrapped in a REST call for the UI to kick off, etc.
  public void replayStripePayouts(Date startDate, Date endDate, Environment env) throws Exception {
    SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
    Iterable<Payout> payouts = env.stripeClient().getPayouts(startDate, endDate, 100);
    for (Payout payout : payouts) {
      try {
        System.out.println(SDF.format(new Date(payout.getArrivalDate() * 1000)));
        processEvent("payout.paid", payout, env);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }
}
