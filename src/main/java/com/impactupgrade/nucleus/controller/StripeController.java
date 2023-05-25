/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.service.segment.EnrichmentService;
import com.impactupgrade.nucleus.service.segment.StripePaymentGatewayService;
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

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    Environment env = envFactory.init(request);

    // stripe-java uses GSON, so Jersey/Jackson won't work on its own
    Event event = Event.GSON.fromJson(json, Event.class);

    EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
    StripeObject stripeObject;
    if (dataObjectDeserializer.getObject().isPresent()) {
      stripeObject = dataObjectDeserializer.getObject().get();
    } else {
      log.warn("Stripe deserialization failed, probably due to an API version mismatch.");
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
          String jobName = "Stripe Event";
          env.startJobLog(JobType.EVENT, "webhook", jobName, "Stripe");
          processEvent(event.getType(), stripeObject, env);
          env.endJobLog(jobName);
        } catch (Exception e) {
          log.error("failed to process the Stripe event", e);
          env.logJobError(e.getMessage(), true);
          // TODO: email notification?
        }
      };
      new Thread(thread).start();
    }

    return Response.status(200).build();
  }

  // Public so that utilities can call this directly.
  public void processEvent(String eventType, StripeObject stripeObject, Environment env) throws Exception {
    StripePaymentGatewayService stripePaymentGatewayService = (StripePaymentGatewayService) env.paymentGatewayService("stripe");
    if (stripePaymentGatewayService.filter(stripeObject)) {
      log.info("Skipping stripe object...");
      return;
    }

    switch (eventType) {
      case "charge.succeeded" -> {
        Charge charge = (Charge) stripeObject;
        log.info("found charge {}", charge.getId());

        if (!Strings.isNullOrEmpty(charge.getPaymentIntent())) {
          log.info("charge {} is part of an intent; skipping and waiting for the payment_intent.succeeded event...", charge.getId());
        } else {
          PaymentGatewayEvent paymentGatewayEvent = stripePaymentGatewayService.chargeToPaymentGatewayEvent(charge, true);

          // must first process the account/contact so they're available for the enricher
          env.contactService().processDonor(paymentGatewayEvent);

          enrich(paymentGatewayEvent, env);

          env.donationService().createDonation(paymentGatewayEvent);
          env.accountingService().processTransaction(paymentGatewayEvent);
        }
      }
      case "payment_intent.succeeded" -> {
        PaymentIntent paymentIntent = (PaymentIntent) stripeObject;
        log.info("found payment intent {}", paymentIntent.getId());

        PaymentGatewayEvent paymentGatewayEvent = stripePaymentGatewayService.paymentIntentToPaymentGatewayEvent(paymentIntent, true);

        // must first process the account/contact so they're available for the enricher
        env.contactService().processDonor(paymentGatewayEvent);

        enrich(paymentGatewayEvent, env);

        env.donationService().createDonation(paymentGatewayEvent);
        env.accountingService().processTransaction(paymentGatewayEvent);
      }
      case "charge.failed" -> {
        Charge charge = (Charge) stripeObject;
        log.info("found charge {}", charge.getId());

        if (!Strings.isNullOrEmpty(charge.getPaymentIntent())) {
          log.info("charge {} is part of an intent; skipping...", charge.getId());
        } else {
          PaymentGatewayEvent paymentGatewayEvent = stripePaymentGatewayService.chargeToPaymentGatewayEvent(charge, true);
          env.contactService().processDonor(paymentGatewayEvent);
          env.donationService().createDonation(paymentGatewayEvent);
        }
      }
      case "payment_intent.payment_failed" -> {
        PaymentIntent paymentIntent = (PaymentIntent) stripeObject;
        log.info("found payment intent {}", paymentIntent.getId());

        PaymentGatewayEvent paymentGatewayEvent = stripePaymentGatewayService.paymentIntentToPaymentGatewayEvent(paymentIntent, true);
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
        env.donationService().processDeposit(paymentGatewayEvents);
      }
      case "customer.source.expiring" -> {
        // Occurs whenever a card or source will expire at the end of the month.
        if (stripeObject instanceof Card) {
          Card card = (Card) stripeObject;
          log.info("found expiring card {}", card.getId());

          Customer customer = env.stripeClient().getCustomer(card.getCustomer());
          log.info("found customer {}", customer.getId());
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

          CrmService crmService = env.donationsCrmService();

          for (Subscription subscription: affectedSubscriptions) {
            // TODO: Move to StripePaymentGatewayService?
            PaymentGatewayEvent paymentGatewayEvent = new PaymentGatewayEvent(env);
            paymentGatewayEvent.initStripe(subscription, customer);
            // For each open subscription using that payment source,
            // look up the associated recurring donation from CrmService's getRecurringDonationBySubscriptionId
            Optional<CrmRecurringDonation> crmRecurringDonationOptional = crmService.getRecurringDonation(
                paymentGatewayEvent.getCrmRecurringDonation().id,
                paymentGatewayEvent.getCrmRecurringDonation().subscriptionId,
                paymentGatewayEvent.getCrmAccount().id,
                paymentGatewayEvent.getCrmContact().id
            );

            if (crmRecurringDonationOptional.isPresent()) {
              String targetId = null;
              Optional<CrmAccount> crmAccountOptional = crmService.getAccountByCustomerId(card.getCustomer());
              if (crmAccountOptional.isPresent()) {
                targetId = crmAccountOptional.get().id;
              } else {
                Optional<CrmContact> crmContactOptional = crmService.searchContacts(ContactSearch.byEmail(customer.getEmail())).getSingleResult();
                if (crmContactOptional.isPresent()) {
                  targetId = crmContactOptional.get().account.id;
                }
              }

              if (!Strings.isNullOrEmpty(targetId)) {
                env.notificationService().sendNotification(
                    "Recurring Donation: Card Expiring",
                    "Recurring donation " + crmRecurringDonationOptional.get().id + " is using a card that's about to expire.<br/>Stripe Subscription: <a href=\"https://dashboard.stripe.com/subscriptions/" + subscription.getId() + "\">https://dashboard.stripe.com/subscriptions/" + subscription.getId() + "</a><br/>Recurring Donation: <a href=\"" + crmRecurringDonationOptional.get().crmUrl + "\">" + crmRecurringDonationOptional.get().crmUrl + "</a>",
                    targetId,
                    "donations:card-expiring"
                );
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

  private void enrich(PaymentGatewayEvent paymentGatewayEvent, Environment env)
      throws Exception {
    List<EnrichmentService> enrichmentServices = env.allEnrichmentServices().stream()
        .filter(es -> es.eventIsFromPlatform(paymentGatewayEvent.getCrmDonation())).toList();
    for (EnrichmentService enrichmentService : enrichmentServices) {
      enrichmentService.enrich(paymentGatewayEvent.getCrmDonation());
    }
  }

  // Used for clients that have a simple Stripe widget to update a payment method.
  @Path("/update-source")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response updateSource(
      @FormParam(value = "stripeToken") String stripeToken,
      @FormParam(value = "customerEmail") String customerEmail,
      @FormParam(value = "successUrl") String successUrl,
      @FormParam(value = "failUrl") String failUrl,
      @Context HttpServletRequest request
  ) {
    Environment env = envFactory.init(request);
    StripeClient stripeClient = env.stripeClient();

    try {
      List<Customer> customers = stripeClient.getCustomersByEmail(customerEmail);

      if (customers.isEmpty()) {
        log.info("unable to find donor using {}", customerEmail);
        String error = URLEncoder.encode("Unable to find the donor record", StandardCharsets.UTF_8);
        return Response.temporaryRedirect(URI.create(failUrl + "?error=" + error)).build();
      }

      for (Customer customer : customers) {
        PaymentSource newSource = stripeClient.addCustomerSource(customer, stripeToken);
        stripeClient.setCustomerDefaultSource(customer, newSource);
        log.info("created new source {} for customer {}", customer.getId(), newSource.getId());

        List<Subscription> activeSubscriptions = env.stripeClient().getActiveSubscriptionsFromCustomer(customer.getId());
        for (Subscription subscription: activeSubscriptions) {
          String subscriptionPaymentMethodId = subscription.getDefaultPaymentMethod();
          if (Strings.isNullOrEmpty(subscriptionPaymentMethodId)) {
            subscriptionPaymentMethodId = subscription.getDefaultSource();
          }
          // If neither are set, invoices will use the customer’s invoice_settings.default_payment_method
          // or default_source.
          if (Strings.isNullOrEmpty(subscriptionPaymentMethodId)) {
            subscriptionPaymentMethodId = customer.getDefaultSource();
          }

          if (!newSource.getId().equalsIgnoreCase(subscriptionPaymentMethodId)) {
            stripeClient.updateSubscriptionPaymentMethod(subscription, newSource);
            log.info("updated payment method for subscription {}", subscription.getId());
          }
        }
      }
      return Response.temporaryRedirect(URI.create(successUrl)).build();
    } catch (StripeException e) {
      log.info("failed to update the source for {}", customerEmail, e);
      String error = URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8);
      return Response.temporaryRedirect(URI.create(failUrl + "?error=" + error)).build();
    }
  }
}
