/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.google.common.base.Strings;
import com.impactupgrade.integration.hubspot.AssociationSearchResult;
import com.impactupgrade.integration.hubspot.AssociationSearchResults;
import com.impactupgrade.integration.hubspot.Company;
import com.impactupgrade.integration.hubspot.CompanyResults;
import com.impactupgrade.integration.hubspot.HasId;
import com.impactupgrade.integration.hubspot.crm.v3.HubSpotCrmV3Client;
import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.HubSpotClientFactory;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.client.VirtuousClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.util.TestUtil;
import com.sforce.soap.partner.sobject.SObject;
import org.apache.commons.lang3.RandomStringUtils;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.FileDataBodyPart;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.glassfish.jersey.test.external.ExternalTestContainerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;

// TODO: JerseyTest not yet compatible with JUnit 5 -- suggested workaround
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractIT extends JerseyTest {

  static {
    System.setProperty("jersey.test.host", "localhost");
    System.setProperty("jersey.config.test.container.port", "9009");
  }

  // TODO: Complete hack. We currently have issues since multiple tests revolve around the same integration test
  //  donor and email address. For now, ensure only one test method runs at a time.
  private static final Lock LOCK = new ReentrantLock();

  // definitions of common environments
  protected static final EnvironmentFactory envFactoryHubspotStripe = new EnvironmentFactory("environment-it-hubspot-stripe.json");
  protected static final EnvironmentFactory envFactorySfdcStripe = new EnvironmentFactory("environment-it-sfdc-stripe.json");
  protected static final EnvironmentFactory envFactoryVirtuousStripe = new EnvironmentFactory("environment-it-virtuous-stripe.json");

  protected final App app;
  protected final Environment env;

  protected AbstractIT(App app) {
    super(new ExternalTestContainerFactory());

    this.app = app;
    this.env = app.getEnvironmentFactory().init((HttpServletRequest) null);
  }

  @Override
  public void setUp() throws Exception {
    LOCK.lock();
    super.setUp();
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    LOCK.unlock();
  }

  // TODO: JerseyTest not yet compatible with JUnit 5 -- suggested workaround
  // do not name this setUp()
  @BeforeAll
  public void beforeAll() throws Exception {
    TestUtil.SKIP_NEW_THREADS = true;

    app.start();

    super.setUp();
  }
  // ditto (see above) -- do not name this tearDown()
  @AfterAll
  public void afterAll() throws Exception {
    app.stop();

    super.tearDown();
  }

  @Override
  protected Application configure() {
    enable(TestProperties.LOG_TRAFFIC);
    enable(TestProperties.DUMP_ENTITY);
    // This seems stupid, but I can't figure out how to get Jersey's test framework to skip config if using
    // the external container. They likely do it this way in case a test needs to add a custom controller.
    return new ResourceConfig();
  }

  @Override
  protected void configureClient(ClientConfig config) {
    config.register(MultiPartFeature.class);
  }

  protected void clearSfdc(String name) throws Exception {
    if (Strings.isNullOrEmpty(name)) {
      return;
    }

    SfdcClient sfdcClient = env.sfdcClient();

    List<SObject> existingAccounts = sfdcClient.getAccountsByName(name);
    for (SObject existingAccount : existingAccounts) {
      List<SObject> existingOpps = sfdcClient.getDonationsByAccountId(existingAccount.getId());
      for (SObject existingOpp : existingOpps) {
        // TODO: may need to delete activities -- ran into an issue in TER where an opp couldn't be nuked until that was done
        sfdcClient.delete(existingOpp);
      }

      List<SObject> existingRDs = sfdcClient.getRecurringDonationsByAccountId(existingAccount.getId());
      for (SObject existingRD : existingRDs) {
        sfdcClient.delete(existingRD);
      }

      // TODO: may need to delete activities -- ran into an issue in TER where an opp couldn't be nuked until that was done
      // TODO: does this always delete the Contacts as well?
      sfdcClient.delete(existingAccount);
    }

    // ensure we're actually clean
    assertEquals(0, sfdcClient.getAccountsByName(name).size());
  }

  protected SObject randomContactSfdc() throws Exception {
    String randomLastName = RandomStringUtils.randomAlphabetic(8);

    SfdcClient sfdcClient = env.sfdcClient();

    SObject account = new SObject("Account");
    account.setField("Name", randomLastName + " Household");
    String accountId = sfdcClient.insert(account).getId();

    SObject contact = new SObject("Contact");
    contact.setField("AccountId", accountId);
    contact.setField("FirstName", RandomStringUtils.randomAlphabetic(8));
    contact.setField("LastName", randomLastName);
    contact.setField("Email", RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@test.com");
    String contactId = sfdcClient.insert(contact).getId();
    contact.setId(contactId);

    return contact;
  }

  protected void clearHubspot(String name) throws Exception {
    if (Strings.isNullOrEmpty(name)) {
      return;
    }

    HubSpotCrmV3Client hsClient = HubSpotClientFactory.crmV3Client(env);

    CompanyResults existingAccounts = hsClient.company().searchByName(name, Collections.emptyList());
    for (Company existingAccount : existingAccounts.getResults()) {
      // will find transactional deals AND recurring deals
      AssociationSearchResults existingOpps = hsClient.association().search("company", existingAccount.getId(), "deal");
      for (AssociationSearchResult existingOpp : existingOpps.getResults()) {
        for (HasId to : existingOpp.getTo()) {
          hsClient.deal().delete(to.getId());
        }
      }

      AssociationSearchResults contacts = hsClient.association().search("company", existingAccount.getId(), "contact");
      for (AssociationSearchResult contact : contacts.getResults()) {
        for (HasId to : contact.getTo()) {
          hsClient.contact().delete(to.getId());
        }
      }

      hsClient.company().delete(existingAccount.getId());
    }

    // TODO: HS needs time to catch up, and we arbitrarily have to keep increasing this...
    Thread.sleep(15000);

    // ensure we're actually clean
    assertEquals(0, hsClient.company().searchByName(name, Collections.emptyList()).getResults().size());
  }

  protected void clearVirtuous(String name) throws Exception {
    if (Strings.isNullOrEmpty(name)) {
      return;
    }

    CrmService crmService = env.crmService("virtuous");
    VirtuousClient virtuousClient = env.virtuousClient();

    List<CrmContact> existingContacts = crmService.searchContacts(ContactSearch.byKeywords("Tester"))
        .getResultSets().stream().flatMap(rs -> rs.getRecords().stream()).toList();
    for (CrmContact existingContact : existingContacts) {
      VirtuousClient.Gifts gifts = virtuousClient.getGiftsByContact(Integer.parseInt(existingContact.id));
      for (VirtuousClient.Gift gift : gifts.list) {
        virtuousClient.deleteGift(gift.id);
      }

      virtuousClient.deleteContact(Integer.parseInt(existingContact.id));
    }

    // ensure we're actually clean
    assertEquals(0, crmService.searchContacts(ContactSearch.byKeywords("Tester")).getResultSets()
        .stream().flatMap(rs -> rs.getRecords().stream()).toList().size());
  }

  protected void postToBulkImport(List<Object> values) throws Exception {
    final CsvMapper csvMapper = new CsvMapper();
    File file = File.createTempFile("nucleus-it-", ".csv");
    csvMapper.writeValue(file, values);

    final FileDataBodyPart filePart = new FileDataBodyPart("file", file);
    FormDataMultiPart formDataMultiPart = new FormDataMultiPart();
    final FormDataMultiPart multiPart = (FormDataMultiPart) formDataMultiPart.bodyPart(filePart);
    Response response = target("/api/crm/bulk-import/file").request().header("Nucleus-Api-Key", "abc123")
            .post(Entity.entity(multiPart, multiPart.getMediaType()));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // The endpoint spins off an async thread, so give it time to complete. May need to bump this up if we introduce
    // tests with a larger number of import rows.
    Thread.sleep(30000);
  }
}
