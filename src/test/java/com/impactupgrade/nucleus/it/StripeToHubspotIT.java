/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.google.common.io.Resources;
import com.impactupgrade.integration.hubspot.crm.v3.Deal;
import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.service.segment.HubSpotCrmService;
import org.junit.jupiter.api.Test;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StripeToHubspotIT extends AbstractIT {

  protected StripeToHubspotIT() {
    super(new App(envFactoryHubspotStripe, sessionFactory));
  }

  @Test
  public void coreOneTime() throws Exception {
    clearHubspot();

    // play a Stripe webhook, captured directly from our Stripe account itself
    String json = Resources.toString(Resources.getResource("stripe-charge-success.json"), StandardCharsets.UTF_8);
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // HS needs time to catch up...
    Thread.sleep(5000);

    HubSpotCrmService hsCrmService = (HubSpotCrmService) env.crmService("hubspot");

    Optional<CrmContact> contactO = hsCrmService.getContactByEmail("team+integration+tester@impactupgrade.com");
    assertTrue(contactO.isPresent());
    CrmContact contact = contactO.get();
    String accountId = contact.accountId;
    Optional<CrmAccount> accountO = hsCrmService.getAccountById(accountId);
    assertTrue(accountO.isPresent());
    CrmAccount account = accountO.get();
    assertEquals("Integration Tester", account.name);
    assertEquals("13022 Redding Drive", account.address.street);
    assertEquals("Fort Wayne", account.address.city);
    assertEquals("IN", account.address.state);
    assertEquals("46814", account.address.postalCode);
    assertEquals("US", account.address.country);
    assertEquals("Integration", contact.firstName);
    assertEquals("Tester", contact.lastName);
    assertEquals("team+integration+tester@impactupgrade.com", contact.email);
    assertEquals("+12603495732", contact.mobilePhone);

    List<CrmDonation> donations = hsCrmService.getDonationsByAccountId(accountId);
    assertEquals(1, donations.size());
    CrmDonation donation = donations.get(0);
    Deal deal = (Deal) donation.rawObject;
    // TODO: assert no association to an RD
    assertEquals("Stripe", donation.paymentGatewayName);
    assertEquals("pi_1ImrOLHAwJOu5brrpQ71F1G9", deal.getProperties().getOtherProperties().get("payment_gateway_transaction_id"));
    assertEquals("cus_JPgkris8GTsXIH", deal.getProperties().getOtherProperties().get("payment_gateway_customer_id"));
    assertEquals(CrmDonation.Status.SUCCESSFUL, donation.status);
    // TODO: periodically failing -- TZ issue?
//    assertEquals("2021-05-02", new SimpleDateFormat("yyyy-MM-dd").format(donation.closeDate.getTime()));
    assertEquals("Donation: Integration Tester", donation.name);
    assertEquals(100.0, donation.amount);
  }

  @Test
  public void coreSubscription() throws Exception {
    clearHubspot();

    // play a Stripe webhook, captured directly from our Stripe account itself
    String json = Resources.toString(Resources.getResource("stripe-subscription-charge-success.json"), StandardCharsets.UTF_8);
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // HS needs time to catch up...
    Thread.sleep(5000);

    HubSpotCrmService hsCrmService = (HubSpotCrmService) env.crmService("hubspot");

    Optional<CrmContact> contactO = hsCrmService.getContactByEmail("team+integration+tester@impactupgrade.com");
    assertTrue(contactO.isPresent());
    CrmContact contact = contactO.get();
    String accountId = contact.accountId;
    Optional<CrmAccount> accountO = hsCrmService.getAccountById(accountId);
    assertTrue(accountO.isPresent());
    CrmAccount account = accountO.get();
    assertEquals("Integration Tester", account.name);
    assertEquals("13022 Redding Drive", account.address.street);
    assertEquals("Fort Wayne", account.address.city);
    assertEquals("IN", account.address.state);
    assertEquals("46814", account.address.postalCode);
    assertEquals("US", account.address.country);
    assertEquals("Integration", contact.firstName);
    assertEquals("Tester", contact.lastName);
    assertEquals("team+integration+tester@impactupgrade.com", contact.email);
    assertEquals("+12603495732", contact.mobilePhone);

    List<CrmRecurringDonation> rds = hsCrmService.getOpenRecurringDonationsByAccountId(accountId);
    assertEquals(1, rds.size());
    CrmRecurringDonation rd = rds.get(0);
    Deal rdDeal = (Deal) rd.rawObject;
    assertEquals(100.0, rd.amount);
    assertTrue(rd.active);
    assertEquals(CrmRecurringDonation.Frequency.MONTHLY, rd.frequency);
    assertEquals("2021-11-11", new SimpleDateFormat("yyyy-MM-dd").format(rdDeal.getProperties().getClosedate().getTime()));
    assertEquals("Stripe", rd.paymentGatewayName);
    assertEquals("cus_JPgkris8GTsXIH", rd.customerId);
    assertEquals("sub_1JufwxHAwJOu5brrARMtj1Gb", rd.subscriptionId);

    // verify the Posted opp
    List<CrmDonation> donations = hsCrmService.getDonationsByAccountId(accountId);
    assertEquals(1, donations.size());
    CrmDonation donation = donations.get(0);
    Deal donationDeal = (Deal) donation.rawObject;
    // TODO: assert an association to an RD
    assertEquals("Stripe", donation.paymentGatewayName);
    assertEquals("pi_3JufwxHAwJOu5brr0WP4aAVs", donationDeal.getProperties().getOtherProperties().get("payment_gateway_transaction_id"));
    assertEquals("cus_JPgkris8GTsXIH", donationDeal.getProperties().getOtherProperties().get("payment_gateway_customer_id"));
    assertEquals(CrmDonation.Status.SUCCESSFUL, donation.status);
    assertEquals("2021-11-11", new SimpleDateFormat("yyyy-MM-dd").format(donation.closeDate.getTime()));
    assertEquals("Donation: Integration Tester", donation.name);
    assertEquals(100.0, donation.amount);
  }
}
