/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.impactupgrade.integration.paymentspring.PaymentSpringClient;
import com.impactupgrade.nucleus.environment.Environment;

public class PaymentSpringClientFactory {

  public static PaymentSpringClient client(Environment env) {
    return new PaymentSpringClient(env.getConfig().paymentSpring.secretKey);
  }
}
