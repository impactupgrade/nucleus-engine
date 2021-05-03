package com.impactupgrade.nucleus.model;

import com.impactupgrade.nucleus.AbstractMockTest;
import com.impactupgrade.nucleus.environment.Environment;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PaymentGatewayWebhookEventMockTest extends AbstractMockTest {

  @Test
  public void testInitStripeCustomerName() {
    Environment env = new DefaultEnvironment();
    {
      PaymentGatewayWebhookEvent event = new PaymentGatewayWebhookEvent(env, env.newRequestEnvironment(null));
      Customer customer = new Customer();
      customer.setName("Brett Meyer The First");
      customer.setMetadata(Collections.emptyMap());
      Charge charge = new Charge();
      charge.setMetadata(Collections.emptyMap());
      event.initStripeCustomerName(customer, charge);

      assertEquals("Brett Meyer The First", event.getFullName());
      assertEquals("Brett", event.getFirstName());
      assertEquals("Meyer The First", event.getLastName());
    }
  }
}
