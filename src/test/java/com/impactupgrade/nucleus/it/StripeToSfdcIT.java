/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.it.util.StripeUtil;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.sforce.soap.partner.sobject.SObject;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Subscription;
import com.stripe.param.PlanCreateParams;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.impactupgrade.nucleus.util.Utils.now;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StripeToSfdcIT extends AbstractIT {

  protected StripeToSfdcIT() {
    super(new App(envFactorySfdcStripe));
  }

  @Test
  public void coreOneTime() throws Exception {
    String nowDate = DateTimeFormatter.ofPattern("yyyy-M-d").format(now("UTC"));

    Customer customer = StripeUtil.createCustomer(env);
    Charge charge = StripeUtil.createCharge(customer, env);
    String json = StripeUtil.createEventJson("charge.succeeded", charge.getRawJsonObject(), charge.getCreated());

    // play as a Stripe webhook
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    SfdcClient sfdcClient = env.sfdcClient();

    // verify ContactService -> SfdcCrmService
    Optional<SObject> contactO = sfdcClient.searchContacts(ContactSearch.byEmail(customer.getEmail())).getSingleResult();
    assertTrue(contactO.isPresent());
    SObject contact = contactO.get();
    String accountId = contact.getField("AccountId").toString();
    Optional<SObject> accountO = sfdcClient.getAccountById(accountId);
    assertTrue(accountO.isPresent());
    SObject account = accountO.get();
    assertEquals(customer.getName(), account.getField("Name"));
    assertEquals("123 Somewhere St", account.getField("BillingStreet"));
    assertEquals("Fort Wayne", account.getField("BillingCity"));
    assertEquals("IN", account.getField("BillingState"));
    assertEquals("46814", account.getField("BillingPostalCode"));
    assertEquals("US", account.getField("BillingCountry"));
    assertEquals(customer.getName().split(" ")[0], contact.getField("FirstName"));
    assertEquals(customer.getName().split(" ")[1], contact.getField("LastName"));
    assertEquals(customer.getEmail(), contact.getField("Email"));
    assertEquals(customer.getPhone(), contact.getField("MobilePhone"));

    // verify DonationService -> SfdcCrmService
    List<SObject> opps = sfdcClient.getDonationsByAccountId(accountId);
    assertEquals(1, opps.size());
    SObject opp = opps.get(0);
    assertNull(opp.getField("Npe03__Recurring_Donation__c"));
    assertEquals("Stripe", opp.getField("Payment_Gateway_Name__c"));
    assertEquals(charge.getId(), opp.getField("Payment_Gateway_Transaction_Id__c"));
    assertEquals(charge.getCustomer(), opp.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals("Closed Won", opp.getField("StageName"));
    assertEquals(nowDate, opp.getField("CloseDate"));
    assertEquals(customer.getName() + " Donation", opp.getField("Name"));
    assertEquals("1.0", opp.getField("Amount"));

    List<SObject> contactRoles = sfdcClient.queryList("SELECT Id, ContactId, IsPrimary FROM OpportunityContactRole WHERE OpportunityId='" + opp.getId() + "'");
    assertEquals(1, contactRoles.size());
    SObject contactRole = contactRoles.get(0);
    assertEquals(contact.getId(), contactRole.getField("ContactId"));
    assertEquals("true", contactRole.getField("IsPrimary"));

    // only delete if the test passed -- keep failures in SFDC for analysis
    clearSfdc(customer.getName());
  }

  @Test
  public void coreOneTimeByName() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    Customer customer = StripeUtil.createCustomer(env);

    String firstName = customer.getName().split(" ")[0];
    String lastName = customer.getName().split(" ")[1];

    // precreate a contact that has the same name and address
    SObject existingAccount = new SObject("Account");
    existingAccount.setField("Name", customer.getName());
    // same address
    existingAccount.setField("BillingStreet", "123 Somewhere St");
    existingAccount.setField("BillingCity", "Fort Wayne");
    existingAccount.setField("BillingState", "IN");
    existingAccount.setField("BillingPostalCode", "46814");
    existingAccount.setField("BillingCountry", "US");
    String existingAccountId = sfdcClient.insert(existingAccount).getId();
    SObject existingContact = new SObject("Contact");
    existingContact.setField("AccountId", existingAccountId);
    // same name
    existingContact.setField("FirstName", firstName);
    existingContact.setField("LastName", lastName);
    // different email
    existingContact.setField("Email", RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@test.com");
    // different phone
    String existingContactId = sfdcClient.insert(existingContact).getId();

    Charge charge = StripeUtil.createCharge(customer, env);
    String json = StripeUtil.createEventJson("charge.succeeded", charge.getRawJsonObject(), charge.getCreated());

    // play as a Stripe webhook
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // verify ContactService -> SfdcCrmService
    List<SObject> contacts = sfdcClient.searchContacts(ContactSearch.byName(firstName, lastName)).getResults();
    // main test -- this would be 2 if the by-name match didn't work
    assertEquals(1, contacts.size());
    SObject contact = contacts.get(0);
    String accountId = contact.getField("AccountId").toString();
    Optional<SObject> accountO = sfdcClient.getAccountById(accountId);
    assertTrue(accountO.isPresent());
    SObject account = accountO.get();
    assertEquals(customer.getName(), account.getField("Name"));
    assertEquals("123 Somewhere St", account.getField("BillingStreet"));
    assertEquals("Fort Wayne", account.getField("BillingCity"));
    assertEquals("IN", account.getField("BillingState"));
    assertEquals("46814", account.getField("BillingPostalCode"));
    assertEquals("US", account.getField("BillingCountry"));
    assertEquals(customer.getName().split(" ")[0], contact.getField("FirstName"));
    assertEquals(customer.getName().split(" ")[1], contact.getField("LastName"));
    // should be the existing CRM field, not the Stripe customer
    assertEquals(existingContact.getField("Email"), contact.getField("Email"));

    // only delete if the test passed -- keep failures in SFDC for analysis
    clearSfdc(customer.getName());
  }

  /**
   * Some clients store explicit sf_account and sf_contact identifiers in Stripe customer metadata.
   * At times, the sf_contact may not exist by ID in SFDC, usually due to merges. Ensure we fall back to email searches.
   */
  @Test
  public void customerMetadataAimedAtMissingContact() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    Customer customer = StripeUtil.createCustomer(env);

    String firstName = customer.getName().split(" ")[0];
    String lastName = customer.getName().split(" ")[1];

    // precreate a contact that has the same name and email
    SObject existingAccount = new SObject("Account");
    existingAccount.setField("Name", customer.getName());
    // same address
    String existingAccountId = sfdcClient.insert(existingAccount).getId();
    SObject existingContact = new SObject("Contact");
    existingContact.setField("AccountId", existingAccountId);
    // same name
    existingContact.setField("FirstName", firstName);
    existingContact.setField("LastName", lastName);
    // same email
    existingContact.setField("Email", customer.getEmail());
    String existingContactId = sfdcClient.insert(existingContact).getId();

    // update the Customer with metadata pointing to missing records
    Map<String, String> customerMetadata = Map.of("sf_account", existingAccountId, "sf_contact", "0035YABCDEFGHIJKLM");
    env.stripeClient().updateCustomer(customer, customerMetadata);

    Charge charge = StripeUtil.createCharge(customer, env);
    String json = StripeUtil.createEventJson("charge.succeeded", charge.getRawJsonObject(), charge.getCreated());

    // play as a Stripe webhook
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // verify ContactService -> SfdcCrmService
    List<SObject> contacts = sfdcClient.searchContacts(ContactSearch.byEmail(customer.getEmail())).getResults();
    // this would be 2 if the by-email match didn't work
    assertEquals(1, contacts.size());
    SObject contact = contacts.get(0);
    String accountId = contact.getField("AccountId").toString();
    Optional<SObject> accountO = sfdcClient.getAccountById(accountId);
    assertTrue(accountO.isPresent());
    assertEquals(existingAccountId, accountId);
    SObject account = accountO.get();
    assertEquals(customer.getName(), account.getField("Name"));
    assertEquals(customer.getName().split(" ")[0], contact.getField("FirstName"));
    assertEquals(customer.getName().split(" ")[1], contact.getField("LastName"));
    assertEquals(customer.getEmail(), contact.getField("Email"));

    // only delete if the test passed -- keep failures in SFDC for analysis
    clearSfdc(customer.getName());
  }

  @Test
  public void coreSubscription() throws Exception {
    String nowDate = DateTimeFormatter.ofPattern("yyyy-M-d").format(now("UTC"));

    Customer customer = StripeUtil.createCustomer(env);
    Subscription subscription = StripeUtil.createSubscription(customer, env, PlanCreateParams.Interval.MONTH);
    List<PaymentIntent> paymentIntents = env.stripeClient().getPaymentIntentsFromCustomer(customer.getId());
    PaymentIntent paymentIntent = env.stripeClient().getPaymentIntent(paymentIntents.get(0).getId());
    String json = StripeUtil.createEventJson("payment_intent.succeeded", paymentIntent.getRawJsonObject(), paymentIntent.getCreated());

    // play as a Stripe webhook
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    SfdcClient sfdcClient = env.sfdcClient();

    Optional<SObject> contactO = sfdcClient.searchContacts(ContactSearch.byEmail(customer.getEmail())).getSingleResult();
    assertTrue(contactO.isPresent());
    SObject contact = contactO.get();
    String accountId = contact.getField("AccountId").toString();
    Optional<SObject> accountO = sfdcClient.getAccountById(accountId);
    assertTrue(accountO.isPresent());
    SObject account = accountO.get();
    assertEquals(customer.getName(), account.getField("Name"));
    assertEquals("123 Somewhere St", account.getField("BillingStreet"));
    assertEquals("Fort Wayne", account.getField("BillingCity"));
    assertEquals("IN", account.getField("BillingState"));
    assertEquals("46814", account.getField("BillingPostalCode"));
    assertEquals("US", account.getField("BillingCountry"));
    assertEquals(customer.getName().split(" ")[0], contact.getField("FirstName"));
    assertEquals(customer.getName().split(" ")[1], contact.getField("LastName"));
    assertEquals(customer.getEmail(), contact.getField("Email"));
    assertEquals(customer.getPhone(), contact.getField("MobilePhone"));

    List<SObject> rds = sfdcClient.getRecurringDonationsByAccountId(accountId);
    assertEquals(1, rds.size());
    SObject rd = rds.get(0);
    assertEquals("1.0", rd.getField("npe03__Amount__c"));
    assertEquals("Open", rd.getField("npe03__Open_Ended_Status__c"));
    assertEquals("Multiply By", rd.getField("npe03__Schedule_Type__c"));
    assertEquals("Monthly", rd.getField("npe03__Installment_Period__c"));
    assertEquals(nowDate, rd.getField("npe03__Date_Established__c"));
    assertEquals("Stripe", rd.getField("Payment_Gateway_Name__c"));
    assertEquals(customer.getId(), rd.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals(subscription.getId(), rd.getField("Payment_Gateway_Subscription_Id__c"));

    // verify the Closed Won opp
    List<SObject> opps = sfdcClient.getDonationsByAccountId(accountId);
    assertEquals(1, opps.size());
    SObject opp = opps.get(0);
    assertEquals(rd.getId(), opp.getField("npe03__Recurring_Donation__c"));
    assertEquals("Stripe", opp.getField("Payment_Gateway_Name__c"));
    assertEquals(paymentIntent.getId(), opp.getField("Payment_Gateway_Transaction_Id__c"));
    assertEquals(customer.getId(), opp.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals("Closed Won", opp.getField("StageName"));
    assertEquals(nowDate, opp.getField("CloseDate"));
    assertEquals(customer.getName() + " Donation", opp.getField("Name"));
    assertEquals("1.0", opp.getField("Amount"));

    // only delete if the test passed -- keep failures in SFDC for analysis
    clearSfdc(customer.getName());
  }

  @Test
  public void coreSubscriptionFrequency() throws Exception {
    Customer customer = StripeUtil.createCustomer(env);
    Subscription subscription = StripeUtil.createSubscription(customer, env, PlanCreateParams.Interval.YEAR);
    List<PaymentIntent> paymentIntents = env.stripeClient().getPaymentIntentsFromCustomer(customer.getId());
    PaymentIntent paymentIntent = env.stripeClient().getPaymentIntent(paymentIntents.get(0).getId());
    String json = StripeUtil.createEventJson("payment_intent.succeeded", paymentIntent.getRawJsonObject(), paymentIntent.getCreated());

    // play as a Stripe webhook
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    SfdcClient sfdcClient = env.sfdcClient();

    Optional<SObject> contactO = sfdcClient.searchContacts(ContactSearch.byEmail(customer.getEmail())).getSingleResult();
    assertTrue(contactO.isPresent());
    SObject contact = contactO.get();
    String accountId = contact.getField("AccountId").toString();
    Optional<SObject> accountO = sfdcClient.getAccountById(accountId);
    assertTrue(accountO.isPresent());


    List<SObject> rds = sfdcClient.getRecurringDonationsByAccountId(accountId);
    assertEquals(1, rds.size());
    SObject rd = rds.get(0);
    String sfdcPeriod = rd.getField("npe03__Installment_Period__c").toString();
    String sfdcPeriodFormatted = sfdcPeriod.endsWith("ly") ? sfdcPeriod.substring(0, sfdcPeriod.length() - 2) : sfdcPeriod;
    assertEquals("year", sfdcPeriodFormatted.toString().toLowerCase());


    // only delete if the test passed -- keep failures in SFDC for analysis
    clearSfdc(customer.getName());
  }

  @Test
  public void missingData() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    Customer customer = StripeUtil.createCustomer(env);

    SObject existingAccount = new SObject("Account");
    existingAccount.setField("Name", customer.getName());
    existingAccount.setField("BillingCountry", "US");
    String existingAccountId = sfdcClient.insert(existingAccount).getId();
    SObject existingContact = new SObject("Contact");
    existingContact.setField("AccountId", existingAccountId);
    // no FirstName
    existingContact.setField("LastName", customer.getName().split(" ")[1]);
    existingContact.setField("Email", customer.getEmail());
    String existingContactId = sfdcClient.insert(existingContact).getId();

    String nowDate = DateTimeFormatter.ofPattern("yyyy-M-d").format(now("UTC"));

    Charge charge = StripeUtil.createCharge(customer, env);
    String json = StripeUtil.createEventJson("charge.succeeded", charge.getRawJsonObject(), charge.getCreated());

    // play as a Stripe webhook
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // verify ContactService -> SfdcCrmService
    Optional<SObject> contactO = sfdcClient.searchContacts(ContactSearch.byEmail(customer.getEmail())).getSingleResult();
    assertTrue(contactO.isPresent());
    SObject contact = contactO.get();
    String accountId = contact.getField("AccountId").toString();
    Optional<SObject> accountO = sfdcClient.getAccountById(accountId);
    assertTrue(accountO.isPresent());
    SObject account = accountO.get();
    assertEquals(customer.getName(), account.getField("Name"));
    assertEquals("123 Somewhere St", account.getField("BillingStreet"));
    assertEquals("Fort Wayne", account.getField("BillingCity"));
    assertEquals("IN", account.getField("BillingState"));
    assertEquals("46814", account.getField("BillingPostalCode"));
    assertEquals("US", account.getField("BillingCountry"));
    assertEquals(customer.getName().split(" ")[0], contact.getField("FirstName"));
    assertEquals(customer.getName().split(" ")[1], contact.getField("LastName"));
    assertEquals(customer.getEmail(), contact.getField("Email"));
    assertEquals(customer.getPhone(), contact.getField("MobilePhone"));

    // verify DonationService -> SfdcCrmService
    List<SObject> opps = sfdcClient.getDonationsByAccountId(accountId);
    assertEquals(1, opps.size());
    SObject opp = opps.get(0);
    assertNull(opp.getField("Npe03__Recurring_Donation__c"));
    assertEquals("Stripe", opp.getField("Payment_Gateway_Name__c"));
    assertEquals(charge.getId(), opp.getField("Payment_Gateway_Transaction_Id__c"));
    assertEquals(charge.getCustomer(), opp.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals("Closed Won", opp.getField("StageName"));
    assertEquals(nowDate, opp.getField("CloseDate"));
    assertEquals(customer.getName() + " Donation", opp.getField("Name"));
    assertEquals("1.0", opp.getField("Amount"));

    // only delete if the test passed -- keep failures in SFDC for analysis
    clearSfdc(customer.getName());
  }

  @Test
  public void preserveData() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    Customer customer = StripeUtil.createCustomer(env);

    SObject existingAccount = new SObject("Account");
    existingAccount.setField("Name", customer.getName());
    existingAccount.setField("BillingStreet", "123 Another St");
    existingAccount.setField("BillingCity", "Kendallville");
    existingAccount.setField("BillingState", "IN");
    existingAccount.setField("BillingPostalCode", "46755");
    existingAccount.setField("BillingCountry", "US");
    String existingAccountId = sfdcClient.insert(existingAccount).getId();
    SObject existingContact = new SObject("Contact");
    existingContact.setField("AccountId", existingAccountId);
    existingContact.setField("FirstName", customer.getName().split(" ")[0]);
    // DIFFERENT NAME
    existingContact.setField("LastName", customer.getName().split(" ")[1] + "2");
    existingContact.setField("Email", customer.getEmail());
    existingContact.setField("MobilePhone", "260-987-6543");
    String existingContactId = sfdcClient.insert(existingContact).getId();

    String nowDate = DateTimeFormatter.ofPattern("yyyy-M-d").format(now("UTC"));

    Charge charge = StripeUtil.createCharge(customer, env);
    String json = StripeUtil.createEventJson("charge.succeeded", charge.getRawJsonObject(), charge.getCreated());

    // play as a Stripe webhook
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // verify ContactService -> SfdcCrmService
    Optional<SObject> contactO = sfdcClient.searchContacts(ContactSearch.byEmail(customer.getEmail())).getSingleResult();
    assertTrue(contactO.isPresent());
    SObject contact = contactO.get();
    String accountId = contact.getField("AccountId").toString();
    Optional<SObject> accountO = sfdcClient.getAccountById(accountId);
    assertTrue(accountO.isPresent());
    SObject account = accountO.get();
    assertEquals(customer.getName(), account.getField("Name"));
    assertEquals("123 Another St", account.getField("BillingStreet"));
    assertEquals("Kendallville", account.getField("BillingCity"));
    assertEquals("IN", account.getField("BillingState"));
    assertEquals("46755", account.getField("BillingPostalCode"));
    assertEquals("US", account.getField("BillingCountry"));
    assertEquals(customer.getName().split(" ")[0], contact.getField("FirstName"));
    assertEquals(customer.getName().split(" ")[1] + "2", contact.getField("LastName"));
    assertEquals(customer.getEmail(), contact.getField("Email"));
    assertEquals("260-987-6543", contact.getField("MobilePhone"));

    // verify DonationService -> SfdcCrmService
    List<SObject> opps = sfdcClient.getDonationsByAccountId(accountId);
    assertEquals(1, opps.size());
    SObject opp = opps.get(0);
    assertNull(opp.getField("Npe03__Recurring_Donation__c"));
    assertEquals("Stripe", opp.getField("Payment_Gateway_Name__c"));
    assertEquals(charge.getId(), opp.getField("Payment_Gateway_Transaction_Id__c"));
    assertEquals(charge.getCustomer(), opp.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals("Closed Won", opp.getField("StageName"));
    assertEquals(nowDate, opp.getField("CloseDate"));
    // Allow the donation to accurately reflect the transaction's details, even if it doesn't exactly match
    // the Contact/Account the CRM itself (data wasn't overwritten).
    assertEquals(customer.getName() + " Donation", opp.getField("Name"));
    assertEquals("1.0", opp.getField("Amount"));

    // only delete if the test passed -- keep failures in SFDC for analysis
    clearSfdc(customer.getName());
  }
}
