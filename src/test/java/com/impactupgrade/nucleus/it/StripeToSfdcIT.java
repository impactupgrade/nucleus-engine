/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

public class StripeToSfdcIT extends AbstractIT {

//  @Test
//  public void coreOneTime() throws Exception {
//    clearSfdc();
//
//    // play a Stripe webhook, captured directly from our Stripe account itself
//    String json = Resources.toString(Resources.getResource("stripe-charge-success.json"), StandardCharsets.UTF_8);
//    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
//    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
//
//    SfdcClient sfdcClient = env().sfdcClient();
//
//    // verify DonorService -> SfdcCrmService
//    Optional<SObject> contactO = sfdcClient.getContactByEmail("team+integration+tester@impactupgrade.com");
//    assertTrue(contactO.isPresent());
//    SObject contact = contactO.get();
//    String accountId = contact.getField("AccountId").toString();
//    Optional<SObject> accountO = sfdcClient.getAccountById(accountId);
//    assertTrue(accountO.isPresent());
//    SObject account = accountO.get();
//    assertEquals("Integration Tester", account.getField("Name"));
//    assertEquals("13022 Redding Drive", account.getField("BillingStreet"));
//    assertEquals("Fort Wayne", account.getField("BillingCity"));
//    assertEquals("IN", account.getField("BillingState"));
//    assertEquals("46814", account.getField("BillingPostalCode"));
//    assertEquals("US", account.getField("BillingCountry"));
//    assertEquals("Integration", contact.getField("FirstName"));
//    assertEquals("Tester", contact.getField("LastName"));
//    assertEquals("team+integration+tester@impactupgrade.com", contact.getField("Email"));
//    assertEquals("+12603495732", contact.getField("MobilePhone"));
//
//    // verify DonationService -> SfdcCrmService
//    List<SObject> opps = sfdcClient.getDonationsByAccountId(accountId);
//    assertEquals(1, opps.size());
//    SObject opp = opps.get(0);
//    assertNull(opp.getField("Npe03__Recurring_Donation__c"));
//    assertEquals("Stripe", opp.getField("Payment_Gateway_Name__c"));
//    assertEquals("pi_1ImrOLHAwJOu5brrpQ71F1G9", opp.getField("Payment_Gateway_Transaction_Id__c"));
//    assertEquals("cus_JPgkris8GTsXIH", opp.getField("Payment_Gateway_Customer_Id__c"));
//    assertEquals("Posted", opp.getField("StageName"));
//    // assertEquals("TODO", opp.getField("CampaignId"));
//    assertEquals("2021-05-03", opp.getField("CloseDate"));
////    assertEquals("TODO", opp.getField("Description"));
//    assertEquals("Integration Tester Donation", opp.getField("Name"));
//    assertEquals("100.0", opp.getField("Amount"));
//  }
}
