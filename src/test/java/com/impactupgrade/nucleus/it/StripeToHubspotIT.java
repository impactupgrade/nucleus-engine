/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.impactupgrade.integration.hubspot.Deal;
import com.impactupgrade.integration.hubspot.crm.v3.HubSpotCrmV3Client;
import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.HubSpotClientFactory;
import com.impactupgrade.nucleus.it.util.StripeUtil;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.service.segment.HubSpotCrmService;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Subscription;
import com.stripe.param.PlanCreateParams;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StripeToHubspotIT extends AbstractIT {

  protected StripeToHubspotIT() {
    super(new App(envFactoryHubspotStripe));
  }

  @Test
  public void coreOneTime() throws Exception {
    Customer customer = StripeUtil.createCustomer(env);
    Charge charge = StripeUtil.createCharge(customer, env);
    String json = StripeUtil.createEventJson("charge.succeeded", charge.getRawJsonObject(), charge.getCreated());

    // play as a Stripe webhook
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // TODO: HS needs time to catch up, and we arbitrarily have to keep increasing this...
    Thread.sleep(30000);

    HubSpotCrmService hsCrmService = (HubSpotCrmService) env.crmService("hubspot");

    Optional<CrmContact> contactO = hsCrmService.searchContacts(ContactSearch.byEmail(customer.getEmail())).getSingleResult();
    assertTrue(contactO.isPresent());
    CrmContact contact = contactO.get();
    String accountId = contact.account.id;
    Optional<CrmAccount> accountO = hsCrmService.getAccountById(accountId);
    assertTrue(accountO.isPresent());
    CrmAccount account = accountO.get();
    assertEquals(customer.getName(), account.name);
    assertEquals("123 Somewhere St", account.billingAddress.street);
    assertEquals("Fort Wayne", account.billingAddress.city);
    assertEquals("IN", account.billingAddress.state);
    assertEquals("46814", account.billingAddress.postalCode);
    assertEquals("US", account.billingAddress.country);
    assertEquals(customer.getName().split(" ")[0], contact.firstName);
    assertEquals(customer.getName().split(" ")[1], contact.lastName);
    assertEquals(customer.getEmail(), contact.email);
    assertEquals(customer.getPhone(), contact.mobilePhone);

    List<CrmDonation> donations = hsCrmService.getDonationsByAccountId(accountId);
    assertEquals(1, donations.size());
    CrmDonation donation = donations.get(0);
    Deal deal = (Deal) donation.rawObject;
    // TODO: assert no association to an RD
    assertEquals("Stripe", donation.gatewayName);
    assertEquals(charge.getId(), deal.getProperties().getOtherProperties().get("payment_gateway_transaction_id"));
    assertEquals(customer.getId(), deal.getProperties().getOtherProperties().get("payment_gateway_customer_id"));
    assertEquals(CrmDonation.Status.SUCCESSFUL, donation.status);
//    assertEquals("2021-05-02", new SimpleDateFormat("yyyy-MM-dd").format(donation.closeDate.getTime()));
    assertEquals("Donation: " + customer.getName(), donation.name);
    assertEquals(1.0, donation.amount);

    // only delete if the test passed -- keep failures in SFDC for analysis
    clearHubspot(customer.getName());
  }

  @Test
  public void coreSubscription() throws Exception {
    String nowDate = new SimpleDateFormat("yyyy-MM-dd").format(Calendar.getInstance().getTime());

    Customer customer = StripeUtil.createCustomer(env);
    Subscription subscription = StripeUtil.createSubscription(customer, env, PlanCreateParams.Interval.MONTH);
    List<PaymentIntent> paymentIntents = env.stripeClient().getPaymentIntentsFromCustomer(customer.getId());
    PaymentIntent paymentIntent = env.stripeClient().getPaymentIntent(paymentIntents.get(0).getId());
    String json = StripeUtil.createEventJson("payment_intent.succeeded", paymentIntent.getRawJsonObject(), paymentIntent.getCreated());

    // play as a Stripe webhook
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // TODO: HS needs time to catch up, and we arbitrarily have to keep increasing this...
    Thread.sleep(30000);

    HubSpotCrmService hsCrmService = (HubSpotCrmService) env.crmService("hubspot");

    Optional<CrmContact> contactO = hsCrmService.searchContacts(ContactSearch.byEmail(customer.getEmail())).getSingleResult();
    assertTrue(contactO.isPresent());
    CrmContact contact = contactO.get();
    String accountId = contact.account.id;
    Optional<CrmAccount> accountO = hsCrmService.getAccountById(accountId);
    assertTrue(accountO.isPresent());
    CrmAccount account = accountO.get();
    assertEquals(customer.getName(), account.name);
    assertEquals("123 Somewhere St", account.billingAddress.street);
    assertEquals("Fort Wayne", account.billingAddress.city);
    assertEquals("IN", account.billingAddress.state);
    assertEquals("46814", account.billingAddress.postalCode);
    assertEquals("US", account.billingAddress.country);
    assertEquals(customer.getName().split(" ")[0], contact.firstName);
    assertEquals(customer.getName().split(" ")[1], contact.lastName);
    assertEquals(customer.getEmail(), contact.email);
    assertEquals(customer.getPhone(), contact.mobilePhone);

    Optional<CrmRecurringDonation> _rd = hsCrmService.getRecurringDonationBySubscriptionId(subscription.getId(), null, null);
    assertTrue(_rd.isPresent());
    CrmRecurringDonation rd = _rd.get();
    Deal rdDeal = (Deal) rd.rawObject;
    assertEquals(1.0, rd.amount);
    assertTrue(rd.active);
    assertEquals(CrmRecurringDonation.Frequency.MONTHLY, rd.frequency);
    assertEquals(nowDate, new SimpleDateFormat("yyyy-MM-dd").format(rdDeal.getProperties().getClosedate().getTime()));
    assertEquals("Stripe", rd.gatewayName);
    assertEquals(customer.getId(), rd.customerId);
    assertEquals(subscription.getId(), rd.subscriptionId);

    // verify the Posted opp
    List<CrmDonation> donations = hsCrmService.getDonationsByAccountId(accountId);
    assertEquals(1, donations.size());
    CrmDonation donation = donations.get(0);
    Deal deal = (Deal) donation.rawObject;
    // TODO: assert association to an RD
    assertEquals("Stripe", donation.gatewayName);
    assertEquals(paymentIntent.getId(), deal.getProperties().getOtherProperties().get("payment_gateway_transaction_id"));
    assertEquals(customer.getId(), deal.getProperties().getOtherProperties().get("payment_gateway_customer_id"));
    assertEquals(CrmDonation.Status.SUCCESSFUL, donation.status);
//    assertEquals("2021-05-02", new SimpleDateFormat("yyyy-MM-dd").format(donation.closeDate.getTime()));
    assertEquals("Donation: " + customer.getName(), donation.name);
    assertEquals(1.0, donation.amount);

    // only delete if the test passed -- keep failures in SFDC for analysis
    clearHubspot(customer.getName());
  }

  @Test
  public void invalidEmail() throws Exception {
    Customer customer = StripeUtil.createCustomer(RandomStringUtils.randomAlphabetic(8), RandomStringUtils.randomAlphabetic(8), "bademail@test.asdfasdf", env);
    Charge charge = StripeUtil.createCharge(customer, env);
    String json = StripeUtil.createEventJson("charge.succeeded", charge.getRawJsonObject(), charge.getCreated());

    // play as a Stripe webhook
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // TODO: HS needs time to catch up, and we arbitrarily have to keep increasing this...
    Thread.sleep(30000);

    HubSpotCrmService hsCrmService = (HubSpotCrmService) env.crmService("hubspot");

    // the contact shouldn't exist, but ensure the orphaned account was deleted
    Optional<CrmContact> contactO = hsCrmService.searchContacts(ContactSearch.byEmail(customer.getEmail())).getSingleResult();
    assertFalse(contactO.isPresent());
    HubSpotCrmV3Client hsClient = HubSpotClientFactory.crmV3Client(env);
    assertEquals(0, hsClient.company().searchByName(customer.getName().split(" ")[1], Collections.emptyList()).getTotal());

    // and ensure the rest of the process halted
    Optional<CrmDonation> donationO = hsCrmService.getDonationByTransactionId(charge.getId());
    assertFalse(donationO.isPresent());

    // only delete if the test passed -- keep failures in SFDC for analysis
    clearHubspot(customer.getName());
  }
}
