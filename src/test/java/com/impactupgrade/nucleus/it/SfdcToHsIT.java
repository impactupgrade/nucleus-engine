/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.impactupgrade.integration.hubspot.Company;
import com.impactupgrade.integration.hubspot.Contact;
import com.impactupgrade.integration.hubspot.ContactResults;
import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.HubSpotClientFactory;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.sforce.soap.partner.sobject.SObject;
import org.junit.jupiter.api.Test;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SfdcToHsIT extends AbstractIT {

  protected SfdcToHsIT() {
    super(new App(new EnvironmentFactory("environment-it-sfdc-primary-hs-secondary.json")));
  }

  @Test
  public void coreSync() throws Exception {
    clearSfdc();
    clearHubspot();

    SfdcClient sfdcClient = env.sfdcClient();

    SObject sfdcContact = new SObject("Contact");
    sfdcContact.setField("FirstName", "Integration");
    sfdcContact.setField("LastName", "Tester");
    sfdcContact.setField("Email", "team+integration+tester@impactupgrade.com");
    String sfdcContactId = sfdcClient.insert(sfdcContact).getId();

    sfdcContact = sfdcClient.getContactById(sfdcContactId).get();
    assertNotNull(sfdcContact.getField("AccountId"));

    MultivaluedMap<String, String> formData = new MultivaluedHashMap<>();
    formData.add("id", (String) sfdcContact.getField("AccountId"));
    formData.add("type", "Account");
    Response response = target("/api/crm/sync-to-secondary").request().post(Entity.form(formData));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // TODO: HS needs time to catch up, and we arbitrarily have to keep increasing this...
    Thread.sleep(30000);

    formData = new MultivaluedHashMap<>();
    formData.add("id", sfdcContactId);
    formData.add("type", "Contact");
    response = target("/api/crm/sync-to-secondary").request().post(Entity.form(formData));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    // TODO: HS needs time to catch up, and we arbitrarily have to keep increasing this...
    Thread.sleep(30000);

    ContactResults hsContactResults = HubSpotClientFactory.crmV3Client(env).contact().searchByEmail("team+integration+tester@impactupgrade.com", List.of());
    assertEquals(1, hsContactResults.getResults().size());
    Contact hsContact = hsContactResults.getResults().get(0);

    assertEquals("Integration", hsContact.getProperties().getFirstname());
    assertEquals("Tester", hsContact.getProperties().getLastname());
    assertEquals("team+integration+tester@impactupgrade.com", hsContact.getProperties().getEmail());

    Company hsCompany = HubSpotClientFactory.crmV3Client(env).company().read(hsContact.getProperties().getAssociatedcompanyid(), List.of());
    assertNotNull(hsCompany);

    assertEquals("Tester Household", hsCompany.getProperties().getName());
  }
}
