/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.google.common.io.Resources;
import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.model.ContactSearch;
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

public class StripeToSfdcIT extends AbstractIT {

  protected StripeToSfdcIT() {
    super(new App(envFactorySfdcStripe));
  }

  @Test
  public void coreOneTime() throws Exception {
    clearSfdc();

    // play a Stripe webhook, captured directly from our Stripe account itself
    String json = Resources.toString(Resources.getResource("stripe-charge-success.json"), StandardCharsets.UTF_8);
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    SfdcClient sfdcClient = env.sfdcClient();

    // verify ContactService -> SfdcCrmService
    Optional<SObject> contactO = sfdcClient.searchContacts(ContactSearch.byEmail("team+integration+tester@impactupgrade.com")).getSingleResult();
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
    assertEquals("+12603495732", contact.getField("MobilePhone"));

    // verify DonationService -> SfdcCrmService
    List<SObject> opps = sfdcClient.getDonationsByAccountId(accountId);
    assertEquals(1, opps.size());
    SObject opp = opps.get(0);
    assertNull(opp.getField("Npe03__Recurring_Donation__c"));
    assertEquals("Stripe", opp.getField("Payment_Gateway_Name__c"));
    assertEquals("pi_1ImrOLHAwJOu5brrpQ71F1G9", opp.getField("Payment_Gateway_Transaction_Id__c"));
    assertEquals("cus_JPgkris8GTsXIH", opp.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals("Closed Won", opp.getField("StageName"));
    assertEquals("2021-05-03", opp.getField("CloseDate"));
    assertEquals("Integration Tester Donation", opp.getField("Name"));
    assertEquals("100.0", opp.getField("Amount"));
  }

  @Test
  public void coreSubscription() throws Exception {
    clearSfdc();

    // play a Stripe webhook, captured directly from our Stripe account itself
    String json = Resources.toString(Resources.getResource("stripe-subscription-charge-success.json"), StandardCharsets.UTF_8);
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    SfdcClient sfdcClient = env.sfdcClient();

    Optional<SObject> contactO = sfdcClient.searchContacts(ContactSearch.byEmail("team+integration+tester@impactupgrade.com")).getSingleResult();
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
    assertEquals("+12603495732", contact.getField("MobilePhone"));

    List<SObject> rds = sfdcClient.getRecurringDonationsByAccountId(accountId);
    assertEquals(1, rds.size());
    SObject rd = rds.get(0);
    assertEquals("100.0", rd.getField("npe03__Amount__c"));
    assertEquals("Open", rd.getField("npe03__Open_Ended_Status__c"));
    assertEquals("Multiply By", rd.getField("npe03__Schedule_Type__c"));
    assertEquals("Monthly", rd.getField("npe03__Installment_Period__c"));
    assertEquals("2021-11-11", rd.getField("npe03__Date_Established__c"));
    // TODO: periodically fails -- may have a timing issue where this isn't being updated fast enough
    assertEquals("2021-11-11", rd.getField("npe03__Next_Payment_Date__c"));
    assertEquals("Stripe", rd.getField("Payment_Gateway_Name__c"));
    assertEquals("cus_JPgkris8GTsXIH", rd.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals("sub_1JufwxHAwJOu5brrARMtj1Gb", rd.getField("Payment_Gateway_Subscription_Id__c"));

    // verify the Closed Won opp
    List<SObject> opps = sfdcClient.getDonationsByAccountId(accountId);
    assertEquals(1, opps.size());
    SObject opp = opps.get(0);
    assertEquals(rd.getId(), opp.getField("npe03__Recurring_Donation__c"));
    assertEquals("Stripe", opp.getField("Payment_Gateway_Name__c"));
    assertEquals("pi_3JufwxHAwJOu5brr0WP4aAVs", opp.getField("Payment_Gateway_Transaction_Id__c"));
    assertEquals("cus_JPgkris8GTsXIH", opp.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals("Closed Won", opp.getField("StageName"));
    assertEquals("2021-11-11", opp.getField("CloseDate"));
    assertEquals("Integration Tester Donation", opp.getField("Name"));
    assertEquals("100.0", opp.getField("Amount"));
  }

  @Test
  public void missingData() throws Exception {
    clearSfdc();

    SfdcClient sfdcClient = env.sfdcClient();

    SObject existingAccount = new SObject("Account");
    existingAccount.setField("Name", "Integration Tester");
    existingAccount.setField("BillingCountry", "US");
    String existingAccountId = sfdcClient.insert(existingAccount).getId();
    SObject existingContact = new SObject("Contact");
    existingContact.setField("AccountId", existingAccountId);
    // no FirstName
    existingContact.setField("LastName", "Tester");
    existingContact.setField("Email", "team+integration+tester@impactupgrade.com");
    String existingContactId = sfdcClient.insert(existingContact).getId();

    // play a Stripe webhook, captured directly from our Stripe account itself
    String json = Resources.toString(Resources.getResource("stripe-charge-success.json"), StandardCharsets.UTF_8);
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // verify ContactService -> SfdcCrmService
    Optional<SObject> contactO = sfdcClient.searchContacts(ContactSearch.byEmail("team+integration+tester@impactupgrade.com")).getSingleResult();
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
    assertEquals("+12603495732", contact.getField("MobilePhone"));

    // verify DonationService -> SfdcCrmService
    List<SObject> opps = sfdcClient.getDonationsByAccountId(accountId);
    assertEquals(1, opps.size());
    SObject opp = opps.get(0);
    assertNull(opp.getField("Npe03__Recurring_Donation__c"));
    assertEquals("Stripe", opp.getField("Payment_Gateway_Name__c"));
    assertEquals("pi_1ImrOLHAwJOu5brrpQ71F1G9", opp.getField("Payment_Gateway_Transaction_Id__c"));
    assertEquals("cus_JPgkris8GTsXIH", opp.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals("Closed Won", opp.getField("StageName"));
    assertEquals("2021-05-03", opp.getField("CloseDate"));
    assertEquals("Integration Tester Donation", opp.getField("Name"));
    assertEquals("100.0", opp.getField("Amount"));
  }

  @Test
  public void preserveData() throws Exception {
    clearSfdc();

    SfdcClient sfdcClient = env.sfdcClient();

    SObject existingAccount = new SObject("Account");
    existingAccount.setField("Name", "Integration Tester");
    existingAccount.setField("BillingStreet", "1111 Richard Road");
    existingAccount.setField("BillingCity", "Kendallville");
    existingAccount.setField("BillingState", "IN");
    existingAccount.setField("BillingPostalCode", "46755");
    existingAccount.setField("BillingCountry", "US");
    String existingAccountId = sfdcClient.insert(existingAccount).getId();
    SObject existingContact = new SObject("Contact");
    existingContact.setField("AccountId", existingAccountId);
    existingContact.setField("FirstName", "Integration");
    existingContact.setField("LastName", "Tester2");
    existingContact.setField("Email", "team+integration+tester@impactupgrade.com");
    existingContact.setField("MobilePhone", "+12604153316");
    String existingContactId = sfdcClient.insert(existingContact).getId();

    // play a Stripe webhook, captured directly from our Stripe account itself
    String json = Resources.toString(Resources.getResource("stripe-charge-success.json"), StandardCharsets.UTF_8);
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // verify ContactService -> SfdcCrmService
    Optional<SObject> contactO = sfdcClient.searchContacts(ContactSearch.byEmail("team+integration+tester@impactupgrade.com")).getSingleResult();
    assertTrue(contactO.isPresent());
    SObject contact = contactO.get();
    String accountId = contact.getField("AccountId").toString();
    Optional<SObject> accountO = sfdcClient.getAccountById(accountId);
    assertTrue(accountO.isPresent());
    SObject account = accountO.get();
    assertEquals("Integration Tester", account.getField("Name"));
    assertEquals("1111 Richard Road", account.getField("BillingStreet"));
    assertEquals("Kendallville", account.getField("BillingCity"));
    assertEquals("IN", account.getField("BillingState"));
    assertEquals("46755", account.getField("BillingPostalCode"));
    assertEquals("US", account.getField("BillingCountry"));
    assertEquals("Integration", contact.getField("FirstName"));
    assertEquals("Tester2", contact.getField("LastName"));
    assertEquals("team+integration+tester@impactupgrade.com", contact.getField("Email"));
    assertEquals("+12604153316", contact.getField("MobilePhone"));

    // verify DonationService -> SfdcCrmService
    List<SObject> opps = sfdcClient.getDonationsByAccountId(accountId);
    assertEquals(1, opps.size());
    SObject opp = opps.get(0);
    assertNull(opp.getField("Npe03__Recurring_Donation__c"));
    assertEquals("Stripe", opp.getField("Payment_Gateway_Name__c"));
    assertEquals("pi_1ImrOLHAwJOu5brrpQ71F1G9", opp.getField("Payment_Gateway_Transaction_Id__c"));
    assertEquals("cus_JPgkris8GTsXIH", opp.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals("Closed Won", opp.getField("StageName"));
    assertEquals("2021-05-03", opp.getField("CloseDate"));
    assertEquals("Integration Tester2 Donation", opp.getField("Name"));
    assertEquals("100.0", opp.getField("Amount"));
  }
}
