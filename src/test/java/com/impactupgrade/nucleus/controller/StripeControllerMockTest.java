/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.AbstractMockTest;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
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

public class StripeControllerMockTest extends AbstractMockTest {

  @Test
  public void testCustomCampaignMetadata() throws Exception {
    EnvironmentConfig envConfig = new EnvironmentConfig();
    envConfig.metadataKeys.campaign = Set.of("sf_campaign", "Designation Code");
    Environment env = new DefaultEnvironment() {
      @Override
      public EnvironmentConfig getConfig() {
        return envConfig;
      }
    };
    EnvironmentFactory envFactory = new EnvironmentFactory() {
      @Override
      public Environment newEnv() {
        return env;
      }
    };
    StripeController stripeController = new StripeController(envFactory);

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
    charge.setPaymentMethodDetails(new Charge.PaymentMethodDetails());
    charge.getPaymentMethodDetails().setType("ach");

    // Test to ensure we can retrieve the campaign ID using custom metadata, using TER's setup as an example.
    Map<String, String> chargeMetadata = Map.of("Designation Code", "campaign_1");
    charge.setMetadata(chargeMetadata);

    stripeController.processEvent("charge.succeeded", charge, env);

    ArgumentCaptor<PaymentGatewayEvent> argumentCaptor = ArgumentCaptor.forClass(PaymentGatewayEvent.class);
    verify(donationServiceMock).createDonation(argumentCaptor.capture());
    PaymentGatewayEvent paymentGatewayEvent = argumentCaptor.getValue();
    assertEquals("campaign_1", paymentGatewayEvent.getCrmDonation().getMetadataValue(envConfig.metadataKeys.campaign));
  }
}
