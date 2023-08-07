/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.google.common.io.Resources;
import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.VirtuousClient;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.junit.jupiter.api.Test;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StripeToVirtuousIT extends AbstractIT {

  protected StripeToVirtuousIT() {
    super(new App(envFactoryVirtuousStripe));
  }

  @Test
  public void testOneTime() throws Exception {
    clearVirtuous();

    // play a Stripe webhook, captured directly from our Stripe account itself
    String json = Resources.toString(Resources.getResource("stripe-charge-success.json"), StandardCharsets.UTF_8);
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    CrmService virtuousCrmService = env.crmService("virtuous");
    VirtuousClient virtuousClient = env.virtuousClient();

    Optional<CrmContact> contactO = virtuousCrmService.searchContacts(ContactSearch.byEmail("team+integration+tester@impactupgrade.com")).getSingleResult();
    assertTrue(contactO.isPresent());
    CrmContact contact = contactO.get();
    assertEquals("Integration Tester", contact.getFullName());
    assertEquals("13022 Redding Drive", contact.mailingAddress.street);
    assertEquals("Fort Wayne", contact.mailingAddress.city);
    assertEquals("IN", contact.mailingAddress.state);
    assertEquals("46814", contact.mailingAddress.postalCode);
    assertEquals("United States", contact.mailingAddress.country);
    assertEquals("team+integration+tester@impactupgrade.com", contact.email);
    assertEquals("+12603495732", contact.mobilePhone);

    VirtuousClient.Gifts gifts = virtuousClient.getGiftsByContact(Integer.parseInt(contact.id));
    assertEquals(1, gifts.list.size());
    VirtuousClient.Gift gift = gifts.list.get(0);
    gift = virtuousClient.getGiftById(gift.id); // need the full object, searches return limited fields
    assertEquals(0, gift.recurringGiftPayments.size());
    assertEquals("Stripe", gift.transactionSource);
    assertEquals("pi_1ImrOLHAwJOu5brrpQ71F1G9", gift.transactionId);
    assertEquals("2021-05-03T00:00:00", gift.giftDate);
    assertEquals("100.00", gift.amount);
  }

  @Test
  public void testSubscription() throws Exception {
    clearVirtuous();

    // play a Stripe webhook, captured directly from our Stripe account itself
    String json = Resources.toString(Resources.getResource("stripe-subscription-charge-success.json"), StandardCharsets.UTF_8);
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    CrmService virtuousCrmService = env.crmService("virtuous");
    VirtuousClient virtuousClient = env.virtuousClient();

    Optional<CrmContact> contactO = virtuousCrmService.searchContacts(ContactSearch.byEmail("team+integration+tester@impactupgrade.com")).getSingleResult();
    assertTrue(contactO.isPresent());
    CrmContact contact = contactO.get();
    assertEquals("Integration Tester", contact.getFullName());
    assertEquals("13022 Redding Drive", contact.mailingAddress.street);
    assertEquals("Fort Wayne", contact.mailingAddress.city);
    assertEquals("IN", contact.mailingAddress.state);
    assertEquals("46814", contact.mailingAddress.postalCode);
    assertEquals("United States", contact.mailingAddress.country);
    assertEquals("team+integration+tester@impactupgrade.com", contact.email);
    assertEquals("+12603495732", contact.mobilePhone);

    List<VirtuousClient.RecurringGift> rds = virtuousClient.getRecurringGiftsByContact(Integer.parseInt(contact.id)).list;
    assertEquals(1, rds.size());
    VirtuousClient.RecurringGift rd = rds.get(0);
    assertEquals(105.0, rd.amount);
    assertEquals("UpToDate", rd.status);
    assertEquals("Monthly", rd.frequency);
    assertEquals("2023-08-06T00:00:00", rd.startDate.toString());
    assertEquals("2023-09-06T00:00:00", rd.nextExpectedPaymentDate.toString());
    assertEquals("Stripe", rd.paymentGatewayName(env));
    assertEquals("sub_1NcEBfHAwJOu5brrwWSlKSWx", rd.paymentGatewaySubscriptionId(env));

    VirtuousClient.Gifts gifts = virtuousClient.getGiftsByContact(Integer.parseInt(contact.id));
    assertEquals(1, gifts.list.size());
    VirtuousClient.Gift gift = gifts.list.get(0);
    gift = virtuousClient.getGiftById(gift.id); // need the full object, searches return limited fields
    assertEquals(1, gift.recurringGiftPayments.size());
    assertEquals("Stripe", gift.transactionSource);
    assertEquals("pi_3NcEBfHAwJOu5brr1mtwduxc", gift.transactionId);
    assertEquals("2023-08-06T00:00:00", gift.giftDate);
    assertEquals("105.00", gift.amount);

    // then delete the gift, reprocess it, and ensure the RD isn't recreated as a duplicate

    virtuousClient.deleteGift(gift.id);

    gifts = virtuousClient.getGiftsByContact(Integer.parseInt(contact.id));
    assertEquals(0, gifts.list.size());

    json = Resources.toString(Resources.getResource("stripe-subscription-charge-success.json"), StandardCharsets.UTF_8);
    response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    rds = virtuousClient.getRecurringGiftsByContact(Integer.parseInt(contact.id)).list;
    assertEquals(1, rds.size());
    rd = rds.get(0);
    assertEquals(105.0, rd.amount);
    assertEquals("UpToDate", rd.status);
    assertEquals("Monthly", rd.frequency);
    assertEquals("2023-08-06T00:00:00", rd.startDate.toString());
    assertEquals("2023-09-06T00:00:00", rd.nextExpectedPaymentDate.toString());
    assertEquals("Stripe", rd.paymentGatewayName(env));
    assertEquals("sub_1NcEBfHAwJOu5brrwWSlKSWx", rd.paymentGatewaySubscriptionId(env));

    gifts = virtuousClient.getGiftsByContact(Integer.parseInt(contact.id));
    assertEquals(1, gifts.list.size());
    gift = gifts.list.get(0);
    gift = virtuousClient.getGiftById(gift.id); // need the full object, searches return limited fields
    assertEquals(1, gift.recurringGiftPayments.size());
    assertEquals("Stripe", gift.transactionSource);
    assertEquals("pi_3NcEBfHAwJOu5brr1mtwduxc", gift.transactionId);
    assertEquals("2023-08-06T00:00:00", gift.giftDate);
    assertEquals("105.00", gift.amount);
  }

  @Test
  public void testDeposit() throws Exception {
    clearVirtuous();

    // play a Stripe webhook, captured directly from our Stripe account itself
    String json = Resources.toString(Resources.getResource("stripe-charge-success.json"), StandardCharsets.UTF_8);
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    json = Resources.toString(Resources.getResource("stripe-payout.json"), StandardCharsets.UTF_8);
    response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    CrmService virtuousCrmService = env.crmService("virtuous");
    VirtuousClient virtuousClient = env.virtuousClient();

    Optional<CrmContact> contactO = virtuousCrmService.searchContacts(ContactSearch.byEmail("team+integration+tester@impactupgrade.com")).getSingleResult();
    assertTrue(contactO.isPresent());
    CrmContact contact = contactO.get();

    VirtuousClient.Gifts gifts = virtuousClient.getGiftsByContact(Integer.parseInt(contact.id));
    assertEquals(1, gifts.list.size());
    VirtuousClient.Gift gift = gifts.list.get(0);
    gift = virtuousClient.getGiftById(gift.id); // need the full object, searches return limited fields
    assertEquals("Stripe", gift.transactionSource);
    assertEquals("pi_1ImrOLHAwJOu5brrpQ71F1G9", gift.transactionId);
    assertEquals("2021-05-03T00:00:00", gift.giftDate);
    assertEquals("100.00", gift.amount);
    assertEquals("po_1JXFdqHAwJOu5brrPx0caF45", gift.paymentGatewayDepositId(env));
    assertEquals("9/8/2021", gift.paymentGatewayDepositDate(env));
    assertEquals("3.2", gift.paymentGatewayDepositFee(env));
    assertEquals("96.8", gift.paymentGatewayDepositNetAmount(env));
  }

  @Test
  public void testCancel() throws Exception {
    clearVirtuous();

    String json = Resources.toString(Resources.getResource("stripe-subscription-charge-success.json"), StandardCharsets.UTF_8);
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    CrmService virtuousCrmService = env.crmService("virtuous");
    VirtuousClient virtuousClient = env.virtuousClient();

    Optional<CrmContact> contactO = virtuousCrmService.searchContacts(ContactSearch.byEmail("team+integration+tester@impactupgrade.com")).getSingleResult();
    assertTrue(contactO.isPresent());
    CrmContact contact = contactO.get();

    List<VirtuousClient.RecurringGift> rds = virtuousClient.getRecurringGiftsByContact(Integer.parseInt(contact.id)).list;
    assertEquals(1, rds.size());
    VirtuousClient.RecurringGift rd = rds.get(0);

    virtuousCrmService.closeRecurringDonation(new CrmRecurringDonation(rd.id + ""));

    rds = virtuousClient.getRecurringGiftsByContact(Integer.parseInt(contact.id)).list;
    assertEquals(1, rds.size());
    rd = rds.get(0);

    assertEquals("Cancelled", rd.status);
  }
}
