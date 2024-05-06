/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.google.common.io.Resources;
import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.VirtuousClient;
import com.impactupgrade.nucleus.it.util.StripeUtil;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Subscription;
import com.stripe.param.PlanCreateParams;
import org.junit.jupiter.api.Test;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static com.impactupgrade.nucleus.util.Utils.now;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StripeToVirtuousIT extends AbstractIT {

  protected StripeToVirtuousIT() {
    super(new App(envFactoryVirtuousStripe));
  }

  @Test
  public void testOneTime() throws Exception {
    String nowDate = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(now("UTC"));

    Customer customer = StripeUtil.createCustomer(env);
    Charge charge = StripeUtil.createCharge(customer, env);
    String json = StripeUtil.createEventJson("charge.succeeded", charge.getRawJsonObject(), charge.getCreated());

    // play as a Stripe webhook
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    CrmService virtuousCrmService = env.crmService("virtuous");
    VirtuousClient virtuousClient = env.virtuousClient();

    Optional<CrmContact> contactO = virtuousCrmService.searchContacts(ContactSearch.byEmail(customer.getEmail())).getSingleResult();
    assertTrue(contactO.isPresent());
    CrmContact contact = contactO.get();
    assertEquals(customer.getName(), contact.getFullName());
    assertEquals("123 Somewhere St", contact.mailingAddress.street);
    assertEquals("Fort Wayne", contact.mailingAddress.city);
    assertEquals("IN", contact.mailingAddress.state);
    assertEquals("46814", contact.mailingAddress.postalCode);
    assertEquals("United States", contact.mailingAddress.country);
    assertEquals(customer.getEmail(), contact.email);
    assertEquals(customer.getPhone(), contact.mobilePhone);

    VirtuousClient.Gifts gifts = virtuousClient.getGiftsByContact(Integer.parseInt(contact.id));
    assertEquals(1, gifts.list.size());
    VirtuousClient.Gift gift = gifts.list.get(0);
    gift = virtuousClient.getGiftById(gift.id); // need the full object, searches return limited fields
    assertEquals(0, gift.recurringGiftPayments.size());
    assertEquals("Stripe", gift.transactionSource);
    assertEquals(charge.getId(), gift.transactionId);
    assertEquals(nowDate + "T00:00:00", gift.giftDate);
    assertEquals("1.00", gift.amount);

    // only delete if the test passed -- keep failures in SFDC for analysis
    clearVirtuous(customer.getName());
  }

  @Test
  public void testSubscription() throws Exception {
    String nowDate = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(now("UTC"));

    Customer customer = StripeUtil.createCustomer(env);
    Subscription subscription = StripeUtil.createSubscription(customer, env, PlanCreateParams.Interval.MONTH);
    List<PaymentIntent> paymentIntents = env.stripeClient().getPaymentIntentsFromCustomer(customer.getId());
    PaymentIntent paymentIntent = env.stripeClient().getPaymentIntent(paymentIntents.get(0).getId());
    String json = StripeUtil.createEventJson("payment_intent.succeeded", paymentIntent.getRawJsonObject(), paymentIntent.getCreated());

    // play as a Stripe webhook
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    CrmService virtuousCrmService = env.crmService("virtuous");
    VirtuousClient virtuousClient = env.virtuousClient();

    Optional<CrmContact> contactO = virtuousCrmService.searchContacts(ContactSearch.byEmail(customer.getEmail())).getSingleResult();
    assertTrue(contactO.isPresent());
    CrmContact contact = contactO.get();
    assertEquals(customer.getName(), contact.getFullName());
    assertEquals("123 Somewhere St", contact.mailingAddress.street);
    assertEquals("Fort Wayne", contact.mailingAddress.city);
    assertEquals("IN", contact.mailingAddress.state);
    assertEquals("46814", contact.mailingAddress.postalCode);
    assertEquals("United States", contact.mailingAddress.country);
    assertEquals(customer.getEmail(), contact.email);
    assertEquals(customer.getPhone(), contact.mobilePhone);

    List<VirtuousClient.RecurringGift> rds = virtuousClient.getRecurringGiftsByContact(Integer.parseInt(contact.id)).list;
    assertEquals(1, rds.size());
    VirtuousClient.RecurringGift rd = rds.get(0);
    assertEquals(1.0, rd.amount);
    assertEquals("UpToDate", rd.status);
    assertEquals("Monthly", rd.frequency);
    assertEquals(nowDate + "T00:00:00", rd.startDate.toString());
    assertEquals("Stripe", rd.paymentGatewayName(env));
    assertEquals(subscription.getId(), rd.paymentGatewaySubscriptionId(env));

    VirtuousClient.Gifts gifts = virtuousClient.getGiftsByContact(Integer.parseInt(contact.id));
    assertEquals(1, gifts.list.size());
    VirtuousClient.Gift gift = gifts.list.get(0);
    gift = virtuousClient.getGiftById(gift.id); // need the full object, searches return limited fields
    assertEquals(1, gift.recurringGiftPayments.size());
    assertEquals("Stripe", gift.transactionSource);
    assertEquals(paymentIntent.getId(), gift.transactionId);
    assertEquals(nowDate + "T00:00:00", gift.giftDate);
    assertEquals("1.00", gift.amount);

    // then delete the gift, reprocess it, and ensure the RD isn't recreated as a duplicate

    virtuousClient.deleteGift(gift.id);

    gifts = virtuousClient.getGiftsByContact(Integer.parseInt(contact.id));
    assertEquals(0, gifts.list.size());

    response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    rds = virtuousClient.getRecurringGiftsByContact(Integer.parseInt(contact.id)).list;
    assertEquals(1, rds.size());
    rd = rds.get(0);
    assertEquals(1.0, rd.amount);
    assertEquals("UpToDate", rd.status);
    assertEquals("Monthly", rd.frequency);
    assertEquals(nowDate + "T00:00:00", rd.startDate.toString());
    assertEquals("Stripe", rd.paymentGatewayName(env));
    assertEquals(subscription.getId(), rd.paymentGatewaySubscriptionId(env));

    gifts = virtuousClient.getGiftsByContact(Integer.parseInt(contact.id));
    assertEquals(1, gifts.list.size());
    gift = gifts.list.get(0);
    gift = virtuousClient.getGiftById(gift.id); // need the full object, searches return limited fields
    assertEquals(1, gift.recurringGiftPayments.size());
    assertEquals("Stripe", gift.transactionSource);
    assertEquals(paymentIntent.getId(), gift.transactionId);
    assertEquals(nowDate + "T00:00:00", gift.giftDate);
    assertEquals("1.00", gift.amount);

    // only delete if the test passed -- keep failures in SFDC for analysis
    clearVirtuous(customer.getName());
  }

  // TODO: Doesn't appear to be possible to programmatically create a Payout? Do we need to use Mockito for this one?
  @Test
  public void testDeposit() throws Exception {
    clearVirtuous("Tester");

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
    Customer customer = StripeUtil.createCustomer(env);
    Subscription subscription = StripeUtil.createSubscription(customer, env, PlanCreateParams.Interval.MONTH);
    List<PaymentIntent> paymentIntents = env.stripeClient().getPaymentIntentsFromCustomer(customer.getId());
    PaymentIntent paymentIntent = env.stripeClient().getPaymentIntent(paymentIntents.get(0).getId());
    String json = StripeUtil.createEventJson("payment_intent.succeeded", paymentIntent.getRawJsonObject(), paymentIntent.getCreated());

    // play as a Stripe webhook
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    CrmService virtuousCrmService = env.crmService("virtuous");
    VirtuousClient virtuousClient = env.virtuousClient();

    Optional<CrmContact> contactO = virtuousCrmService.searchContacts(ContactSearch.byEmail(customer.getEmail())).getSingleResult();
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

    // only delete if the test passed -- keep failures in SFDC for analysis
    clearVirtuous(customer.getName());
  }
}
