/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

public class StripeToHubspotIT extends AbstractIT {

  // TODO: Need to be able to provide multiple copies of environment-it.json
  // TODO: Or provide defaults in the file, then override individual values programmatically
  // TODO: Set up a test portal in HS and configure it!

//  @Test
//  public void coreOneTime() throws Exception {
//    clearHubspot();
//
//    // play a Stripe webhook, captured directly from our Stripe account itself
//    String json = Resources.toString(Resources.getResource("stripe-charge-success.json"), StandardCharsets.UTF_8);
//    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
//    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
//
//    HubSpotCrmService hsCrmService = (HubSpotCrmService) env().crmService("hubspot");
//
//    // verify ContactService -> SfdcCrmService
//    Optional<CrmContact> contactO = hsCrmService.getContactByEmail("team+integration+tester@impactupgrade.com");
//    assertTrue(contactO.isPresent());
//    CrmContact contact = contactO.get();
//    String accountId = contact.accountId;
//    Optional<CrmAccount> accountO = hsCrmService.getAccountById(accountId);
//    assertTrue(accountO.isPresent());
//    CrmAccount account = accountO.get();
//    assertEquals("Integration Tester", account.name);
//    assertEquals("13022 Redding Drive", account.address.street);
//    assertEquals("Fort Wayne", account.address.city);
//    assertEquals("IN", account.address.state);
//    assertEquals("46814", account.address.postalCode);
//    assertEquals("US", account.address.country);
//    assertEquals("Integration", contact.firstName);
//    assertEquals("Tester", contact.lastName);
//    assertEquals("team+integration+tester@impactupgrade.com", contact.email);
//    assertEquals("+12603495732", contact.mobilePhone);
//
//    // verify DonationService -> SfdcCrmService
//    List<CrmDonation> opps = hsCrmService.getDonationsByAccountId(accountId);
//    assertEquals(1, opps.size());
//    CrmDonation opp = opps.get(0);
//    // TODO: Test that the Recurring Donation Deal ID is null, but needs wired up.
//    assertEquals("Stripe", opp.paymentGatewayName);
//    // TODO: need added to CrmDonation
////    assertEquals("pi_1ImrOLHAwJOu5brrpQ71F1G9", opp.getField("Payment_Gateway_Transaction_Id__c"));
////    assertEquals("cus_JPgkris8GTsXIH", opp.getField("Payment_Gateway_Customer_Id__c"));
//    assertEquals(CrmDonation.Status.SUCCESSFUL, opp.status);
//    // assertEquals("TODO", opp.getField("CampaignId"));
//    assertEquals("2021-05-03", opp.closeDate);
////    assertEquals("TODO", opp.getField("Description"));
//    assertEquals("Integration Tester Donation", opp.name);
//    assertEquals(100.0, opp.amount);
//  }
}
