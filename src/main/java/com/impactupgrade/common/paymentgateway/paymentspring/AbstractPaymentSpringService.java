package com.impactupgrade.common.paymentgateway.paymentspring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.common.paymentgateway.PaymentGatewayEvent;
import com.impactupgrade.integration.paymentspring.model.Customer;
import com.impactupgrade.integration.paymentspring.model.Event;
import com.impactupgrade.integration.paymentspring.model.Subscription;
import com.impactupgrade.integration.paymentspring.model.Transaction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.Optional;

/**
 * This service acts as the central webhook endpoint for PaymentSpring events, handling everything from
 * successful/failed charges, subscription changes, etc. It also houses anything PaymentSpring-specific called by the
 * Portal UI.
 */
public abstract class AbstractPaymentSpringService<T extends PaymentGatewayEvent> {

  private static final Logger log = LogManager.getLogger(AbstractPaymentSpringService.class);

  private static final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Receives and processes *all* webhooks from PaymentSpring.
   */
  protected Response webhook(String json, T paymentGatewayEvent) {
    // PaymentSpring is so bloody difficult to replay. For now, as we're fixing issues, always log the raw requests.
//    LoggingUtil.verbose(log, json);
    log.info(json);

    Event event;
    try {
      event = objectMapper.readValue(json, Event.class);
    } catch (IOException e) {
      log.error("PaymentSpring deserialization failed", e);
      return Response.status(500).build();
    }

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      // don't log the whole thing -- can be found in PaymentSpring's dashboard
      // log this within the new thread for traceability's sake
      log.info("received event {}: {}", event.getEventResource(), event.getEventType());

      try {
        switch (event.getEventResource()) {
          case "transaction":
            Transaction transaction = objectMapper.readValue(event.getPayloadJson().toString(), Transaction.class);
            log.info("found transaction {}", transaction.getId());

            Optional<Customer> transactionCustomer;
            if (Strings.isNullOrEmpty(transaction.getCustomerId())) {
              transactionCustomer = Optional.empty();
            } else {
              transactionCustomer = Optional.of(PaymentSpringClientFactory.client().customers().getById(transaction.getCustomerId()));
              log.info("found customer {}", transactionCustomer.get().getId());
            }

            Optional<Subscription> transactionSubscription;
            if (!transaction.getMetadata().containsKey("plan_id")
                || Strings.isNullOrEmpty(transaction.getMetadata().get("plan_id"))) {
              transactionSubscription = Optional.empty();
            } else {
              String transactionPlanId = transaction.getMetadata().get("plan_id");
              transactionSubscription = Optional.of(PaymentSpringClientFactory.client().subscriptions().getByPlanId(
                  transactionPlanId, transaction.getCustomerId()));
              log.info("found subscription {}", transactionSubscription.get().getId());
            }

            paymentGatewayEvent.initPaymentSpring(transaction, transactionCustomer, transactionSubscription);

            switch (event.getEventType()) {
              case "created":
                transactionCreated(paymentGatewayEvent);
                break;
              case "failed":
                transactionFailed(paymentGatewayEvent);
                break;
              case "refunded":
                transactionRefunded(paymentGatewayEvent);
                break;
              default:
                log.info("unhandled PaymentSpring transaction event type: {}", event.getEventType());
            }
            break;
          case "subscription":
            switch (event.getEventType()) {
              case "created":
                // NOTE: We currently do nothing with this. Unlike Stripe, PaymentSpring does not support
                // a "trialing" concept, or we're not using it. Either way, no need to create the recurring donation
                // ahead of time. Just let the first transaction do it.
                break;
              case "destroyed":
                Subscription subscription = objectMapper.readValue(event.getPayloadJson().toString(), Subscription.class);
                log.info("found subscription {}", subscription.getId());

                paymentGatewayEvent.initPaymentSpring(subscription);

                subscriptionDestroyed(paymentGatewayEvent);
                break;
              default:
                log.info("unhandled PaymentSpring subscription event type: {}", event.getEventType());
            }
            break;
          default:
            log.info("unhandled PaymentSpring event resource: {}", event.getEventResource());
        }
      } catch (Exception e) {
        log.error("failed to process the PaymentSpring event", e);
        // TODO: email notification?
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  protected abstract void transactionCreated(T paymentGatewayEvent) throws Exception;
  protected abstract void transactionFailed(T paymentGatewayEvent) throws Exception;
  protected abstract void transactionRefunded(T paymentGatewayEvent) throws Exception;
  protected abstract void subscriptionDestroyed(T paymentGatewayEvent) throws Exception;
}
