/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.impactupgrade.nucleus.AbstractMockTest;
import com.impactupgrade.nucleus.environment.Environment;
import com.stripe.model.Customer;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PaymentGatewayEventMockTest extends AbstractMockTest {

  @Test
  public void testInitStripeCustomerName() {
    Environment env = new DefaultEnvironment();
    {
      PaymentGatewayEvent event = new PaymentGatewayEvent(env);
      Customer customer = new Customer();
      customer.setName("Brett The Dork Meyer");
      customer.setMetadata(Collections.emptyMap());
      event.initStripeCustomerName(Optional.of(customer), Optional.empty());

      assertEquals("Brett The Dork Meyer", event.getCrmAccount().name);
      assertEquals("Brett The Dork", event.crmContact.firstName);
      assertEquals("Meyer", event.crmContact.lastName);
    }
  }
}
