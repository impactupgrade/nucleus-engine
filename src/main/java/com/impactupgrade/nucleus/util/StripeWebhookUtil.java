package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.stripe.model.WebhookEndpoint;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// TODO: This is a start of what could become Portal endpoints for auto-provisioning Stripe.

/**
 * Examples:
 *
 * CUSTOM INSTANCE
 * StripeWebhookUtil.setupWebhook(env, "axis-nucleus.herokuapp.com");
 *
 * NUCLEUS CORE
 * StripeWebhookUtil.setupWebhook(env, "nucleus.impactupgrade.com", "8017d001-531e-4094-8757-8a251fc80652");
 */
public class StripeWebhookUtil {

  private static final Logger log = LogManager.getLogger(StripeWebhookUtil.class.getName());

  public void setupWebhook(Environment env, String hostname) {
    setupWebhook(env, hostname, "");
  }

  public void setupWebhook(Environment env, String hostname, String nucleusApiKey) {
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

      String url = "https://" + hostname + "/api/stripe/webhook";
      if (!Strings.isNullOrEmpty(nucleusApiKey)) {
        url += "?Nucleus-Api-Key=" + nucleusApiKey;
      }

      Map<String, Object> params = new HashMap<>();
      params.put("url", url);
      params.put("enabled_events", enabledEvents);
      params.put("api_version", "2020-08-27");

      WebhookEndpoint.create(params, stripeClient.getRequestOptions());
    } catch (Exception e) {
      log.error("failed to set up Stripe webhook", e);
    }
  }
}