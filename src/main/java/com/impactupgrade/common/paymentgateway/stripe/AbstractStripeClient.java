package com.impactupgrade.common.paymentgateway.stripe;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AbstractStripeClient {

  private static final Logger log = LogManager.getLogger(AbstractStripeClient.class.getName());

  static {
    Stripe.apiKey = System.getenv("STRIPE.KEY");
  }

  public static void cancelSubscription(String subscriptionId) throws StripeException {
    log.info("canceling subscription {}...", subscriptionId);
    Subscription.retrieve(subscriptionId).cancel();
    log.info("canceled subscription {}", subscriptionId);
  }
}
