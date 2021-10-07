/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.service.logic.FailedRequestService;
import com.impactupgrade.nucleus.service.segment.StripePaymentGatewayService;
import com.impactupgrade.nucleus.util.EmailUtil;
import com.impactupgrade.nucleus.util.LoggingUtil;
import com.impactupgrade.nucleus.util.TestUtil;
import com.stripe.exception.StripeException;
import com.stripe.model.Card;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentSource;
import com.stripe.model.Payout;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

    // TODO: remove once done testing
    FailedRequestService failedRequestService = env.failedRequestService();
    failedRequestService.persist(event, Event::getId, JSONObject::new, "test");

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
          failedRequestService.delete(event, Event::getId);
        } catch (Exception e) {
          log.error("failed to process the Stripe event", e);
          // TODO: email notification?
          failedRequestService.persist(event, Event::getId, JSONObject::new, e.getMessage());
        }
      };
      new Thread(thread).start();
    }

    return Response.status(200).build();
  }

  // Public so that utilities can call this directly.
  public void processEvent(String eventType, StripeObject stripeObject, Environment env) throws Exception {
    StripePaymentGatewayService stripePaymentGatewayService = (StripePaymentGatewayService) env.paymentGatewayService("stripe");

    switch (eventType) {
      case "charge.succeeded" -> {
        Charge charge = (Charge) stripeObject;
        log.info("found charge {}", charge.getId());

        if (!Strings.isNullOrEmpty(charge.getPaymentIntent())) {
          log.info("charge {} is part of an intent; skipping and waiting for the payment_intent.succeeded event...", charge.getId());
        } else {
          PaymentGatewayEvent paymentGatewayEvent = stripePaymentGatewayService.chargeToPaymentGatewayEvent(charge);
          env.contactService().processDonor(paymentGatewayEvent);
          env.donationService().createDonation(paymentGatewayEvent);
        }
      }
      case "payment_intent.succeeded" -> {
        PaymentIntent paymentIntent = (PaymentIntent) stripeObject;
        log.info("found payment intent {}", paymentIntent.getId());

        PaymentGatewayEvent paymentGatewayEvent = stripePaymentGatewayService.paymentIntentToPaymentGatewayEvent(paymentIntent);
        env.contactService().processDonor(paymentGatewayEvent);
        env.donationService().createDonation(paymentGatewayEvent);
      }
      case "charge.failed" -> {
        Charge charge = (Charge) stripeObject;
        log.info("found charge {}", charge.getId());

        if (!Strings.isNullOrEmpty(charge.getPaymentIntent())) {
          log.info("charge {} is part of an intent; skipping...", charge.getId());
        } else {
          PaymentGatewayEvent paymentGatewayEvent = stripePaymentGatewayService.chargeToPaymentGatewayEvent(charge);
          env.contactService().processDonor(paymentGatewayEvent);
          env.donationService().createDonation(paymentGatewayEvent);
        }
      }
      case "payment_intent.payment_failed" -> {
        PaymentIntent paymentIntent = (PaymentIntent) stripeObject;
        log.info("found payment intent {}", paymentIntent.getId());

        PaymentGatewayEvent paymentGatewayEvent = stripePaymentGatewayService.paymentIntentToPaymentGatewayEvent(paymentIntent);
        env.contactService().processDonor(paymentGatewayEvent);
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

        // TODO: Move to StripePaymentGatewayService?
        PaymentGatewayEvent paymentGatewayEvent = new PaymentGatewayEvent(env);
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

          // TODO: Move to StripePaymentGatewayService?
          PaymentGatewayEvent paymentGatewayEvent = new PaymentGatewayEvent(env);
          paymentGatewayEvent.initStripe(subscription, createdSubscriptionCustomer);
          env.contactService().processDonor(paymentGatewayEvent);
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

        // TODO: Move to StripePaymentGatewayService?
        PaymentGatewayEvent paymentGatewayEvent = new PaymentGatewayEvent(env);
        paymentGatewayEvent.initStripe(subscription, deletedSubscriptionCustomer);
        // NOTE: the customer.subscription.deleted name is a little misleading -- it instead means
        // that the subscription has been canceled immediately, either by manual action or subscription settings. So,
        // simply close the recurring donation.
        env.donationService().closeRecurringDonation(paymentGatewayEvent);
      }
      case "payout.paid" -> {
        Payout payout = (Payout) stripeObject;
        log.info("found payout {}", payout.getId());

        List<PaymentGatewayEvent> paymentGatewayEvents = stripePaymentGatewayService.payoutToPaymentGatewayEvents(payout);
        for (PaymentGatewayEvent paymentGatewayEvent : paymentGatewayEvents) {
          env.donationService().chargeDeposited(paymentGatewayEvent);
        }
      }
      case "customer.source.expiring" -> {
        // Occurs whenever a card or source will expire at the end of the month.
        if (stripeObject instanceof Card) {
          Card card = (Card) stripeObject;
          log.info("found expiring card {}", card.getId());

          Customer customer = env.stripeClient().getCustomer(card.getCustomer());
          List<Subscription> activeSubscriptions = env.stripeClient().getActiveSubscriptionsFromCustomer(card.getCustomer());
          List<Subscription> affectedSubscriptions = new ArrayList<>();

          for (Subscription subscription: activeSubscriptions) {
            // ID of the default payment method for the subscription.
            // It must belong to the customer associated with the subscription.
            // This takes precedence over default_source.
            String subscriptionPaymentMethodId = subscription.getDefaultPaymentMethod();
            if (Strings.isNullOrEmpty(subscriptionPaymentMethodId)) {
              subscriptionPaymentMethodId = subscription.getDefaultSource();
            }
            // If neither are set, invoices will use the customer’s invoice_settings.default_payment_method
            // or default_source.
            if (Strings.isNullOrEmpty(subscriptionPaymentMethodId)) {
              subscriptionPaymentMethodId = customer.getDefaultSource();
            }
            if (card.getId().equalsIgnoreCase(subscriptionPaymentMethodId)) {
              affectedSubscriptions.add(subscription);
            }
          }

          for (Subscription subscription: affectedSubscriptions) {
            //For each open subscription using that payment source,
            //look up the associated recurring donation from CrmService's getRecurringDonationBySubscriptionId
            Optional<CrmRecurringDonation> crmRecurringDonationOptional = env.donationsCrmService().getRecurringDonationById(subscription.getId());

            if (crmRecurringDonationOptional.isPresent()) {
              //For each RD, send a staff email notification + create a task
              CrmRecurringDonation donation = crmRecurringDonationOptional.get();

              // Email
              EnvironmentConfig.Notifications notifications = env.getConfig().notifications.get("stripe:customer.source.expiring");
              String emailFrom = notifications.email.from;
              String emailTo = String.join(",", notifications.email.to);
              String emailSubject = notifications.email.subject;
              EmailUtil.sendEmail(
                      emailSubject,
                      "Recurring donation " + donation.id + " is using card " + card.getId() + " that is about to expire!", // TODO: define message text
                      "<html></html>",
                      emailTo, emailFrom);

              // SMS
              //String fromPhoneNumber = notifications.sms.from;
              //List<String> toPhoneNumbers = notifications.sms.to;
              // TODO: send sms messages

              // Task
              LocalDate now = LocalDate.now();
              LocalDate inAWeek = now.plusDays(7);
              Date dueDate = Date.from(inAWeek.atStartOfDay().toInstant(ZoneOffset.UTC)); // TODO: define due date
              String assignTo = notifications.task.assignTo;
              String taskSubject = notifications.task.subject;

              String targetId = null;
              Optional<CrmAccount> crmAccountOptional = env.donationsCrmService().getAccountByCustomerId(card.getCustomer());
              if (crmAccountOptional.isPresent()) {
                targetId = crmAccountOptional.get().id;
              } else {
                Optional<CrmContact> crmContactOptional = env.donationsCrmService().getContactByEmail(customer.getEmail());
                if (crmContactOptional.isPresent()) {
                  targetId = crmAccountOptional.get().id;
                }
              }

              if (!Strings.isNullOrEmpty(targetId)) {
                env.donationsCrmService().insertTask(new CrmTask(
                        targetId, assignTo, taskSubject, "Contact payment card will expire soon!",
                        CrmTask.Status.TO_DO, CrmTask.Priority.MEDIUM, dueDate));
              }
            }
          }
        } else {
          log.info("found expiring payment source {}", ((PaymentSource) stripeObject).getId());
        }



      }
      default -> log.info("unhandled Stripe webhook event type: {}", eventType);
    }
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
        Optional<CrmDonation> donation = Optional.empty();
        if (!Strings.isNullOrEmpty(paymentIntentId)) {
          donation = env.donationsCrmService().getDonationByTransactionId(paymentIntentId);
        }
        if (donation.isEmpty()) {
          donation = env.donationsCrmService().getDonationByTransactionId(chargeId);
        }

        if (donation.isEmpty()) {
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
