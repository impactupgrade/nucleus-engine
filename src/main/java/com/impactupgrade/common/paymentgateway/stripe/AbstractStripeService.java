package com.impactupgrade.common.paymentgateway.stripe;

import com.google.common.base.Strings;
import com.impactupgrade.common.paymentgateway.PaymentGatewayEvent;
import com.impactupgrade.common.util.LoggingUtil;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.core.Response;
import java.util.Optional;

public abstract class AbstractStripeService<T extends PaymentGatewayEvent> {

  private static final Logger log = LogManager.getLogger(AbstractStripeService.class);

  static {
    Stripe.apiKey = System.getenv("STRIPE_KEY");
  }

  protected Response webhook(String json, T paymentGatewayEvent) {
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
        switch (event.getType()) {
          case "charge.succeeded":
            processCharge(stripeObject, paymentGatewayEvent);
            chargeSucceeded(paymentGatewayEvent);
            break;
          case "charge.failed":
            processCharge(stripeObject, paymentGatewayEvent);
            chargeFailed(paymentGatewayEvent);
            break;
          case "charge.refunded":
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

            chargeRefunded(paymentGatewayEvent);
            break;
          case "customer.subscription.created":
            Subscription createdSubscription = (Subscription) stripeObject;
            log.info("found subscription {}", createdSubscription.getId());

            if ("true".equalsIgnoreCase(createdSubscription.getMetadata().get("auto-migrated"))) {
              // This subscription comes from an auto-migration path, most likely from PaymentSpring through
              // the Donor Portal. In this case, the migration will have already updated the existing
              // recurring donation. Prevent this from creating another one.

              log.info("skipping the auto-migrated subscription");
            } else if ("trialing".equalsIgnoreCase(createdSubscription.getStatus())) {
              // IE, handle the subscription if it's going to happen in the future. Otherwise, if it started already,
              // the incoming payment will handle it. This prevents timing issues for start-now subscriptions, where
              // we'll likely get the subscription and charge near instantaneously (but on different requests/threads).

              Customer createdSubscriptionCustomer = StripeClient.getCustomer(createdSubscription.getCustomer());
              log.info("found customer {}", createdSubscriptionCustomer.getId());

              paymentGatewayEvent.initStripe(createdSubscription, createdSubscriptionCustomer);

              subscriptionTrialing(paymentGatewayEvent);
            } else {
              log.info("subscription is not trialing, so doing nothing; allowing the charge.succeeded event to create the recurring donation");
            }
            break;
          case "customer.subscription.deleted":
            Subscription deletedSubscription = (Subscription) stripeObject;
            log.info("found subscription {}", deletedSubscription.getId());
            Customer deletedSubscriptionCustomer = StripeClient.getCustomer(deletedSubscription.getCustomer());
            log.info("found customer {}", deletedSubscriptionCustomer.getId());
            paymentGatewayEvent.initStripe(deletedSubscription, deletedSubscriptionCustomer);

            // NOTE: the customer.subscription.deleted name is a little misleading -- it instead means
            // that the subscription has been canceled immediately, either by manual action or subscription settings. So,
            // simply close the recurring donation.
            subscriptionDeleted(paymentGatewayEvent);
            break;
          default:
            log.info("unhandled Stripe webhook event type: {}", event.getType());
        }
      } catch (Exception e) {
        log.error("failed to process the Stripe event", e);
        // TODO: email notification?
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  private void processCharge(StripeObject stripeObject, T paymentGatewayEvent) throws StripeException {
    Charge charge = (Charge) stripeObject;
    log.info("found charge {}", charge.getId());
    Customer chargeCustomer = StripeClient.getCustomer(charge.getCustomer());
    log.info("found customer {}", chargeCustomer.getId());

    Optional<Invoice> chargeInvoice;
    if (Strings.isNullOrEmpty(charge.getInvoice())) {
      chargeInvoice = Optional.empty();
    } else {
      chargeInvoice = Optional.of(StripeClient.getInvoice(charge.getInvoice()));
      log.info("found invoice {}", chargeInvoice.get().getId());
    }

    Optional<BalanceTransaction> chargeBalanceTransaction;
    if (Strings.isNullOrEmpty(charge.getBalanceTransaction())) {
      chargeBalanceTransaction = Optional.empty();
    } else {
      chargeBalanceTransaction = Optional.of(StripeClient.getBalanceTransaction(charge.getBalanceTransaction()));
      log.info("found balance transaction {}", chargeBalanceTransaction.get().getId());
    }

    paymentGatewayEvent.initStripe(charge, chargeCustomer, chargeInvoice, chargeBalanceTransaction);
  }

  protected abstract void chargeSucceeded(T paymentGatewayEvent) throws Exception;
  protected abstract void chargeFailed(T paymentGatewayEvent) throws Exception;
  protected abstract void chargeRefunded(T paymentGatewayEvent) throws Exception;
  protected abstract void subscriptionTrialing(T paymentGatewayEvent) throws Exception;
  protected abstract void subscriptionDeleted(T paymentGatewayEvent) throws Exception;
}
