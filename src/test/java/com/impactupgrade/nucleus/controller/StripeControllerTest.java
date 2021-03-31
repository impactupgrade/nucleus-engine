package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.AbstractTest;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.PaymentSourceCollection;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StripeControllerTest extends AbstractTest {

  @Test
  public void testCustomCampaignMetadata() throws Exception {
    EnvironmentConfig envConfig = new EnvironmentConfig();
    envConfig.metadataKeys.campaign = Set.of("sf_campaign", "Designation Code");
    Environment env = new DefaultEnvironment() {
      @Override
      public EnvironmentConfig config() {
        return envConfig;
      }
    };
    StripeController stripeController = new StripeController(env);

    Customer customer = new Customer();
    customer.setId("customer_1");
    customer.setMetadata(Collections.emptyMap());
    PaymentSourceCollection paymentSourceCollection = new PaymentSourceCollection();
    paymentSourceCollection.setData(Collections.emptyList());
    customer.setSources(paymentSourceCollection);

    when(stripeClientMock.getCustomer(customer.getId())).thenReturn(customer);

    Charge charge = new Charge();
    charge.setId("charge_1");
    charge.setCustomer(customer.getId());
    charge.setAmount(2000L);
    charge.setCurrency("usd");

    // Test to ensure we can retrieve the campaign ID using custom metadata, using TER's setup as an example.
    Map<String, String> chargeMetadata = Map.of("Designation Code", "campaign_1");
    charge.setMetadata(chargeMetadata);

    stripeController.processEvent("charge.succeeded", charge, new DefaultRequestEnvironment(null));

    ArgumentCaptor<PaymentGatewayWebhookEvent> argumentCaptor = ArgumentCaptor.forClass(PaymentGatewayWebhookEvent.class);
    verify(donationServiceMock).createDonation(argumentCaptor.capture());
    PaymentGatewayWebhookEvent paymentGatewayEvent = argumentCaptor.getValue();
    assertEquals("campaign_1", paymentGatewayEvent.getCampaignId());
  }
}
