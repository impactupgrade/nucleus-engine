/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.impactupgrade.integration.paymentspring.PaymentSpringClient;

public class PaymentSpringClientFactory {

  private static final String API_KEY = System.getenv("PAYMENTSPRING_KEY");
  private static final PaymentSpringClient CLIENT = new PaymentSpringClient(API_KEY);

  public static PaymentSpringClient client() {
    return CLIENT;
  }
}
