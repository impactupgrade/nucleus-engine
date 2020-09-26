package com.impactupgrade.common.paymentgateway.paymentspring;

import com.impactupgrade.integration.paymentspring.PaymentSpringClient;

public class PaymentSpringClientFactory {

  private static final String API_KEY = System.getenv("PAYMENTSPRING_KEY");
  private static final PaymentSpringClient CLIENT = new PaymentSpringClient(API_KEY);

  public static PaymentSpringClient client() {
    return CLIENT;
  }
}
