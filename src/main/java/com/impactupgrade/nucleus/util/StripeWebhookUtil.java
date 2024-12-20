/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.stripe.model.WebhookEndpoint;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Examples:
 *
 * CUSTOM INSTANCE
 * StripeWebhookUtil.setupWebhook(env, "org-nucleus.herokuapp.com");
 *
 * CUSTOM INSTANCE WITH UNIQUE PATH
 * StripeWebhookUtil.setupWebhook(env, "org-nucleus.herokuapp.com", "/api/stripe/webhook/unique-path");
 *
 * NUCLEUS CORE
 * StripeWebhookUtil.setupWebhookNucleusCore(env, "dc992cd2-a48e-4179-a82b-2b0f91dd879b");
 */
public class StripeWebhookUtil {

  private static final Logger log = LogManager.getLogger(StripeWebhookUtil.class.getName());

  public static void setupWebhook(Environment env, String hostname) {
    setupWebhook(env, hostname, "/api/stripe/webhook");
  }

  public static void setupWebhookNucleusCore(Environment env, String nucleusApiKey) {
    setupWebhook(env, "nucleus.impactupgrade.com", "/api/stripe/webhook?Nucleus-Api-Key=" + nucleusApiKey);
  }

  public static void setupWebhook(Environment env, String hostname, String path) {
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
      params.put("url", "https://" + hostname + path);
      params.put("enabled_events", enabledEvents);
      params.put("api_version", "2020-08-27");

      WebhookEndpoint.create(params, stripeClient.getRequestOptions());
    } catch (Exception e) {
      log.error("failed to set up Stripe webhook", e);
    }
  }
}