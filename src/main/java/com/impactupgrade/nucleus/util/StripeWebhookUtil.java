package com.impactupgrade.nucleus.util;

import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.stripe.model.WebhookEndpoint;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// TODO: This is a start of what could become Portal endpoints for auto-provisioning Stripe.
public class StripeWebhookUtil {

  private static final Logger log = LogManager.getLogger(StripeWebhookUtil.class.getName());

  public void setupWebhook(Environment env, String hostname) {
    try {
      StripeClient stripeClient = new StripeClient(env);

      List<String> enabledEvents = List.of(
        "charge.failed",
        "charge.refunded",
        "charge.refund.updated",
        "charge.succeeded",
        "charge.updated",
        "customer.source.expiring",
        "customer.subscription.created",
        "customer.subscription.deleted",
        "customer.subscription.updated",
        "payment_intent.succeeded",
        "payment_intent.payment_failed",
        "payout.paid"
      );

      Map<String, Object> params = new HashMap<>();
      params.put("url", "https://" + hostname + "/api/stripe/webhook");
      params.put("enabled_events", enabledEvents);
      params.put("api_version", "2020-08-27");

      WebhookEndpoint.create(params, stripeClient.getRequestOptions());
    } catch (Exception e) {
      log.error("failed to set up Stripe webhook", e);
    }
  }
}