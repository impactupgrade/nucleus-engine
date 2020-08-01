package com.impactupgrade.common.paymentgateway.paymentspring;

import com.impactupgrade.integration.paymentspring.PaymentSpringClient;

public class PaymentSpringClientFactory {

  // TODO: stupidly simple, but leaving this pattern in case we need something more complex in the future

  private static final String API_KEY = System.getenv("PAYMENTSPRING.KEY");
  private static final PaymentSpringClient CLIENT = new PaymentSpringClient(API_KEY);

  public static PaymentSpringClient client() {
    return CLIENT;
  }
}
