/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.it.util.StripeUtil;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.sforce.soap.partner.sobject.SObject;
import com.stripe.model.Charge;
import com.stripe.param.ChargeCreateParams;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
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
    String nowDate = new SimpleDateFormat("yyyy-MM-dd").format(Calendar.getInstance().getTime());

    String randomFirstName = RandomStringUtils.randomAlphabetic(8);
    String randomLastName = RandomStringUtils.randomAlphabetic(8);
    String randomEmail = RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@test.com";

    // Custom Donations uses metadata instead of Customers.
    StripeClient stripeClient = env.stripeClient();
    Map<String, String> metadata = Map.of(
        "First Name", randomFirstName,
        "Last Name", randomLastName,
        "Donor Email", randomEmail,
        "Phone Number", "260-123-4567",
        "Street Address", "123 Somewhere St",
        "City", "Fort Wayne",
        "State", "IN",
        "Postal Code", "46814",
        "Country", "US"
    );
    ChargeCreateParams.Builder chargeBuilder = ChargeCreateParams.builder()
        .setSource("tok_visa")
        .setAmount(100L)
        .setCurrency("USD")
        .putAllMetadata(metadata);
    Charge charge = stripeClient.createCharge(chargeBuilder);
    String json = StripeUtil.createEventJson("charge.succeeded", charge.getRawJsonObject(), charge.getCreated());

    // play as a Stripe webhook
    Response response = target("/api/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    SfdcClient sfdcClient = env.sfdcClient();

    // verify ContactService -> SfdcCrmService
    Optional<SObject> contactO = sfdcClient.searchContacts(ContactSearch.byEmail(randomEmail)).getSingleResult();
    assertTrue(contactO.isPresent());
    SObject contact = contactO.get();
    String accountId = contact.getField("AccountId").toString();
    Optional<SObject> accountO = sfdcClient.getAccountById(accountId);
    assertTrue(accountO.isPresent());
    SObject account = accountO.get();
    assertEquals(randomFirstName + " " + randomLastName, account.getField("Name"));
    assertEquals("123 Somewhere St", account.getField("BillingStreet"));
    assertEquals("Fort Wayne", account.getField("BillingCity"));
    assertEquals("IN", account.getField("BillingState"));
    assertEquals("46814", account.getField("BillingPostalCode"));
    assertEquals("US", account.getField("BillingCountry"));
    assertEquals(randomFirstName, contact.getField("FirstName"));
    assertEquals(randomLastName, contact.getField("LastName"));
    assertEquals(randomEmail, contact.getField("Email"));
    assertEquals("260-123-4567", contact.getField("MobilePhone"));

    // verify DonationService -> SfdcCrmService
    List<SObject> opps = sfdcClient.getDonationsByAccountId(accountId);
    assertEquals(1, opps.size());
    SObject opp = opps.get(0);
    assertNull(opp.getField("Npe03__Recurring_Donation__c"));
    assertEquals("Stripe", opp.getField("Payment_Gateway_Name__c"));
    assertEquals(charge.getId(), opp.getField("Payment_Gateway_Transaction_Id__c"));
    assertNull(opp.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals("Closed Won", opp.getField("StageName"));
    assertEquals(nowDate, opp.getField("CloseDate"));
    assertEquals(randomFirstName + " " + randomLastName + " Donation", opp.getField("Name"));
    assertEquals("1.0", opp.getField("Amount"));

    // TODO: Allocation Name (singular) and Allocations List (plural)
    // TODO: Campaign

    // only delete if the test passed -- keep failures in SFDC for analysis
    clearSfdc(randomLastName);
  }

  // Custom Donations handles recurring gifts with standard Customer/Subscription data. No need to retest that!
}
