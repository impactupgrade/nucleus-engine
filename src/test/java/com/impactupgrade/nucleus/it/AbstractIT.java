/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.impactupgrade.integration.hubspot.v3.AssociationSearchResult;
import com.impactupgrade.integration.hubspot.v3.AssociationSearchResults;
import com.impactupgrade.integration.hubspot.v3.Company;
import com.impactupgrade.integration.hubspot.v3.CompanyResults;
import com.impactupgrade.integration.hubspot.v3.HasId;
import com.impactupgrade.integration.hubspot.v3.HubSpotV3Client;
import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.HubSpotClientFactory;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.TestUtil;
import com.sforce.soap.partner.sobject.SObject;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.glassfish.jersey.test.external.ExternalTestContainerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import javax.ws.rs.core.Application;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// TODO: JerseyTest not yet compatible with JUnit 5 -- suggested workaround
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractIT extends JerseyTest {

  static {
    System.setProperty("jersey.test.host", "localhost");
    System.setProperty("jersey.config.test.container.port", "9009");
    System.setProperty("nucleus.integration.test", "true");
  }

  protected AbstractIT() {
    super(new ExternalTestContainerFactory());
  }

  private final App app = getApp();

  // TODO: JerseyTest not yet compatible with JUnit 5 -- suggested workaround
  // do not name this setUp()
  @BeforeAll
  public void before() throws Exception {
    TestUtil.SKIP_NEW_THREADS = true;

    app.start();

    super.setUp();
  }
  // do not name this tearDown()
  @AfterAll
  public void after() throws Exception {
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

  protected App getApp() {
    return new App();
  }

  protected Environment env() {
    return app.envFactory().newEnv();
  }

  protected void clearSfdc() throws Exception {
    clearSfdcByName("Tester");
  }

  protected void clearSfdcByName(String name) throws Exception {
    SfdcClient sfdcClient = env().sfdcClient();

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
      sfdcClient.delete(existingAccount);
    }

    // ensure we're actually clean
    assertEquals(0, sfdcClient.getAccountsByName(name).size());
  }

  protected void clearHubspot() throws Exception {
    clearHubspotByName("Tester");
  }

  protected void clearHubspotByName(String name) throws Exception {
    HubSpotV3Client hsClient = HubSpotClientFactory.v3Client(env());

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

    // ensure we're actually clean
    assertEquals(0, hsClient.company().searchByName(name, Collections.emptyList()).getResults().size());
  }
}
