package com.impactupgrade.common.paymentgateway.stripe;

import com.impactupgrade.common.AbstractTest;
import com.impactupgrade.common.environment.Environment;
import com.impactupgrade.common.paymentgateway.model.PaymentGatewayEvent;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.PaymentSourceCollection;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StripeControllerTest extends AbstractTest {

  @Test
  public void testCustomCampaignMetadata() throws Exception {
    Environment env = new DefaultEnvironment() {
      @Override
      public String[] campaignMetadataKeys() {
        return new String[]{"sf_campaign", "Designation Code"};
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

    stripeController.processEvent("charge.succeeded", charge);

    ArgumentCaptor<PaymentGatewayEvent> argumentCaptor = ArgumentCaptor.forClass(PaymentGatewayEvent.class);
    verify(donationServiceMock).createDonation(argumentCaptor.capture());
    PaymentGatewayEvent paymentGatewayEvent = argumentCaptor.getValue();
    assertEquals("campaign_1", paymentGatewayEvent.getCampaignId());
  }
}
