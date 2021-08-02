/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.impactupgrade.integration.paymentspring.PaymentSpringClient;
import com.impactupgrade.nucleus.environment.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentSpringClientConfig {

  @Bean
  public PaymentSpringClient paymentspringClient(Environment env) {
    return new PaymentSpringClient(env.getConfig().paymentSpring.secretKey);
  }
}
