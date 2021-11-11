/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.google.common.io.Resources;
import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.sforce.soap.partner.sobject.SObject;
import org.junit.jupiter.api.Test;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CustomDonationsToSfdcIT extends AbstractIT {

  protected CustomDonationsToSfdcIT() {
    super(new App(envFactorySfdcStripe));
  }

  @Test
  public void coreOneTime() throws Exception {
    clearSfdc();

    // play a Stripe webhook, captured directly from our Stripe account itself
    String json = Resources.toString(Resources.getResource("custom-donations-charge-success.json"), StandardCharsets.UTF_8);
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    SfdcClient sfdcClient = env.sfdcClient();

    // verify ContactService -> SfdcCrmService
    Optional<SObject> contactO = sfdcClient.getContactByEmail("team+integration+tester@impactupgrade.com");
    assertTrue(contactO.isPresent());
    SObject contact = contactO.get();
    String accountId = contact.getField("AccountId").toString();
    Optional<SObject> accountO = sfdcClient.getAccountById(accountId);
    assertTrue(accountO.isPresent());
    SObject account = accountO.get();
    assertEquals("Integration Tester", account.getField("Name"));
    assertEquals("13022 Redding Drive", account.getField("BillingStreet"));
    assertEquals("Fort Wayne", account.getField("BillingCity"));
    assertEquals("IN", account.getField("BillingState"));
    assertEquals("46814", account.getField("BillingPostalCode"));
    assertEquals("US", account.getField("BillingCountry"));
    assertEquals("Integration", contact.getField("FirstName"));
    assertEquals("Tester", contact.getField("LastName"));
    assertEquals("team+integration+tester@impactupgrade.com", contact.getField("Email"));
    assertEquals("260-349-5732", contact.getField("MobilePhone"));

    // verify DonationService -> SfdcCrmService
    List<SObject> opps = sfdcClient.getDonationsByAccountId(accountId);
    assertEquals(1, opps.size());
    SObject opp = opps.get(0);
    assertNull(opp.getField("Npe03__Recurring_Donation__c"));
    assertEquals("Stripe", opp.getField("Payment_Gateway_Name__c"));
    assertEquals("ch_3JWulhHAwJOu5brr1EEbHa4z", opp.getField("Payment_Gateway_Transaction_Id__c"));
    assertNull(opp.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals("Posted", opp.getField("StageName"));
    assertEquals("2021-09-07", opp.getField("CloseDate"));
    assertEquals("Integration Tester Donation", opp.getField("Name"));
    assertEquals("104.92", opp.getField("Amount"));

    // TODO: Allocation Name (singular) and Allocations List (plural)
    // TODO: Campaign
  }

  @Test
  public void coreSubscription() throws Exception {
    clearSfdc();

    // play a Stripe webhook, captured directly from our Stripe account itself
    String json = Resources.toString(Resources.getResource("custom-donations-subscription-success.json"), StandardCharsets.UTF_8);
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    SfdcClient sfdcClient = env.sfdcClient();

    // verify DonorService -> SfdcCrmService
    Optional<SObject> contactO = sfdcClient.getContactByEmail("team+integration+tester@impactupgrade.com");
    assertTrue(contactO.isPresent());
    SObject contact = contactO.get();
    String accountId = contact.getField("AccountId").toString();
    Optional<SObject> accountO = sfdcClient.getAccountById(accountId);
    assertTrue(accountO.isPresent());
    SObject account = accountO.get();
    assertEquals("Integration Tester", account.getField("Name"));
    assertEquals("13022 Redding Drive", account.getField("BillingStreet"));
    assertEquals("Fort Wayne", account.getField("BillingCity"));
    assertEquals("IN", account.getField("BillingState"));
    assertEquals("46814", account.getField("BillingPostalCode"));
    assertEquals("US", account.getField("BillingCountry"));
    assertEquals("Integration", contact.getField("FirstName"));
    assertEquals("Tester", contact.getField("LastName"));
    assertEquals("team+integration+tester@impactupgrade.com", contact.getField("Email"));
    assertEquals("260-349-5732", contact.getField("MobilePhone"));

    // verify the RD
    List<SObject> rds = sfdcClient.getRecurringDonationsByAccountId(accountId);
    assertEquals(1, rds.size());
    SObject rd = rds.get(0);
    assertEquals("52.62", rd.getField("npe03__Amount__c"));
    assertEquals("Open", rd.getField("npe03__Open_Ended_Status__c"));
    assertEquals("Multiply By", rd.getField("npe03__Schedule_Type__c"));
    assertEquals("month", rd.getField("npe03__Installment_Period__c"));
    assertEquals("2021-09-07", rd.getField("npe03__Date_Established__c"));
    // TODO: periodically fails -- may have a timing issue where this isn't being updated fast enough
    assertEquals("2021-09-07", rd.getField("npe03__Next_Payment_Date__c"));
    assertEquals("Stripe", rd.getField("Payment_Gateway_Name__c"));
    assertEquals("cus_KBHqPAS8xHRjcM", rd.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals("sub_KBHqz0v4OgY68l", rd.getField("Payment_Gateway_Subscription_Id__c"));

    // verify the Posted opp
    List<SObject> opps = sfdcClient.getDonationsByAccountId(accountId);
    assertEquals(1, opps.size());
    SObject opp = opps.get(0);
    assertEquals(rd.getId(), opp.getField("npe03__Recurring_Donation__c"));
    assertEquals("Stripe", opp.getField("Payment_Gateway_Name__c"));
    assertEquals("pi_3JWvHFHAwJOu5brr0jae8t9x", opp.getField("Payment_Gateway_Transaction_Id__c"));
    assertEquals("cus_KBHqPAS8xHRjcM", rd.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals("Posted", opp.getField("StageName"));
    assertEquals("2021-09-07", opp.getField("CloseDate"));
    assertEquals("Integration Tester Donation", opp.getField("Name"));
    assertEquals("52.62", opp.getField("Amount"));

    // TODO: Allocation Name (singular) and Allocations List (plural)
    // TODO: Campaign
  }
}
