package com.impactupgrade.nucleus.integration;

import com.google.common.io.Resources;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.security.SecurityExceptionMapper;
import com.impactupgrade.nucleus.util.TestUtil;
import com.sforce.soap.partner.sobject.SObject;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO: JerseyTest not yet compatible with JUnit 5 -- suggested workaround
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class StripeToSalesforceIntegrationTest extends JerseyTest {

  protected IntegrationTestEnvironment env;

  // TODO: JerseyTest not yet compatible with JUnit 5 -- suggested workaround
  // do not name this setUp()
  @BeforeAll
  public void before() throws Exception {
    super.setUp();
    TestUtil.SKIP_NEW_THREADS = true;
  }
  // do not name this tearDown()
  @AfterAll
  public void after() throws Exception {
    super.tearDown();
  }

  // TODO: Might be better to start App directly and use JerseyTest's external container, but the embedded Jetty
  //  test container is good enough for now...
  // TODO: If we do keep this, how to configure the test container to use the /api root?
  @Override
  protected Application configure() {
    enable(TestProperties.LOG_TRAFFIC);
    enable(TestProperties.DUMP_ENTITY);

    env = new IntegrationTestEnvironment();

    ResourceConfig apiConfig = new ResourceConfig();

    apiConfig.register(new SecurityExceptionMapper());
    apiConfig.register(MultiPartFeature.class);

    apiConfig.register(env.stripeController());

    return apiConfig;
  }

  @Test
  public void stripeToSfdc() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    // first delete the opps/contact/account using the payload's email address
    Optional<SObject> existingContact = sfdcClient.getContactByEmail("team+integration+tester@impactupgrade.com");
    if (existingContact.isPresent()) {
      String accountId = existingContact.get().getField("AccountId").toString();
      Optional<SObject> existingAccount = sfdcClient.getAccountById(accountId);
      List<SObject> existingOpps = sfdcClient.getDonationsByAccountId(accountId);
      for (SObject existingOpp : existingOpps) {
        sfdcClient.delete(existingOpp);
      }
      sfdcClient.delete(existingContact.get());
      sfdcClient.delete(existingAccount.get());
    }

    // ensure we're actually clean
    assertFalse(sfdcClient.getContactByEmail("team+integration+tester@impactupgrade.com").isPresent());

    // play a Stripe webhook, captured directly from our Stripe account itself
    String json = Resources.toString(Resources.getResource("stripe-charge-success.json"), StandardCharsets.UTF_8);
    Response response = target("/stripe/webhook").request().post(Entity.json(json));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

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
    assertEquals("+12603495732", contact.getField("MobilePhone"));

    // verify DonationService -> SfdcCrmService
    List<SObject> opps = sfdcClient.getDonationsByAccountId(accountId);
    assertEquals(1, opps.size());
    SObject opp = opps.get(0);
    assertNull(opp.getField("Npe03__Recurring_Donation__c"));
    assertEquals("Stripe", opp.getField("Payment_Gateway_Name__c"));
    assertEquals("pi_1ImrOLHAwJOu5brrpQ71F1G9", opp.getField("Payment_Gateway_Transaction_Id__c"));
    assertEquals("cus_JPgkris8GTsXIH", opp.getField("Payment_Gateway_Customer_Id__c"));
    assertEquals("Posted", opp.getField("StageName"));
    // assertEquals("TODO", opp.getField("CampaignId"));
    assertEquals("2021-05-03", opp.getField("CloseDate"));
//    assertEquals("TODO", opp.getField("Description"));
    assertEquals("Integration Tester Donation", opp.getField("Name"));
  }
}
