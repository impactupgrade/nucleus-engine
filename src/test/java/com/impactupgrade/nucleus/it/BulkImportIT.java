package com.impactupgrade.nucleus.it;

import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.sforce.soap.partner.sobject.SObject;
import org.apache.commons.lang3.RandomStringUtils;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.file.FileDataBodyPart;
import org.junit.jupiter.api.Test;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// TODO: This test relies on some picklists existing in our dev edition of SFDC. Make it more resilient to auto create
//  those fields if they don't already exist!

// IMPORTANT: When debugging these tests, note that the Bulk Import endpoint spins off an async thread. So a breakpoint
// in the code processing the import does NOT pause the IT code!

public class BulkImportIT extends AbstractIT {

  protected BulkImportIT() {
    super(new App(envFactorySfdcStripe));
  }

  @Test
  public void appendPicklist() throws Exception {
    SObject contact1 = randomContactSfdc();
    SObject contact2 = randomContactSfdc();
    SObject contact3 = randomContactSfdc();
    SObject contact4 = randomContactSfdc();
    SObject contact5 = randomContactSfdc();

    SfdcClient sfdcClient = env.sfdcClient();

    contact1.setField("Test_Multi_Select__c", "Value 1");
    contact2.setField("Test_Multi_Select__c", "Value 1;Value 3");
    contact3.setField("Test_Multi_Select__c", "Value 2");
    contact4.setField("Test_Multi_Select__c", "Value 2;Value 3");
    // nothing for contact 5
    sfdcClient.batchUpdate(contact1);
    sfdcClient.batchUpdate(contact2);
    sfdcClient.batchUpdate(contact3);
    sfdcClient.batchUpdate(contact4);
    sfdcClient.batchFlush();

    final List<Object> values = List.of(
        List.of("Contact ID", "Contact Custom Append Test_Multi_Select__c"),
        List.of(contact1.getId(), "Value 2"),
        List.of(contact2.getId(), "Value 2"),
        List.of(contact3.getId(), "Value 2"),
        List.of(contact4.getId(), "Value 2"),
        List.of(contact5.getId(), "Value 2")
    );
    postToBulkImport(values);

    contact1 = sfdcClient.getContactById(contact1.getId(), "Test_Multi_Select__c").get();
    contact2 = sfdcClient.getContactById(contact2.getId(), "Test_Multi_Select__c").get();
    contact3 = sfdcClient.getContactById(contact3.getId(), "Test_Multi_Select__c").get();
    contact4 = sfdcClient.getContactById(contact4.getId(), "Test_Multi_Select__c").get();
    contact5 = sfdcClient.getContactById(contact5.getId(), "Test_Multi_Select__c").get();

    assertEquals("Value 1;Value 2", contact1.getField("Test_Multi_Select__c").toString());
    assertEquals("Value 1;Value 2;Value 3", contact2.getField("Test_Multi_Select__c").toString());
    assertEquals("Value 2", contact3.getField("Test_Multi_Select__c").toString());
    assertEquals("Value 2;Value 3", contact4.getField("Test_Multi_Select__c").toString());
    assertEquals("Value 2", contact5.getField("Test_Multi_Select__c").toString());
  }

  @Test
  public void doNotOverwriteHouseholdName() throws Exception {
    SObject contact = randomContactSfdc();

    SfdcClient sfdcClient = env.sfdcClient();

    SObject accountOld = new SObject("Account");
    accountOld.setId((String) contact.getField("AccountId"));
    String accountOldExtRef = RandomStringUtils.randomAlphabetic(8);
    accountOld.setField("External_Reference__c", accountOldExtRef);
    sfdcClient.update(accountOld);

    SObject accountNew = new SObject("Account");
    accountNew.setField("Name", "FooBar");
    String accountNewExtRef = RandomStringUtils.randomAlphabetic(8);
    accountNew.setField("External_Reference__c", accountNewExtRef);
    sfdcClient.insert(accountNew);

    final List<Object> values = List.of(
        List.of("Contact ID", "Account ExtRef External_Reference__c"),
        List.of(contact.getId(), accountNewExtRef)
    );
    postToBulkImport(values);

    SObject updatedContact = sfdcClient.getContactById(contact.getId()).get();

    assertEquals(accountOld.getId(), updatedContact.getField("AccountId"));
  }

  /**
   * If a Contact belongs to an Account, and that Account is different than the Account identified by the import's
   * ExtRef, DO NOT move the Contact! Ex: SIS imports often have grandparents in the same household as the parents/student.
   * Staff will later move the grandparents into their own household. Since that new household is different than
   * what the SIS sync's import identifies as the Account ExtRef, ignore it so that their manual movements aren't nuked!
   */
  @Test
  public void doNotOverwriteHousehold() throws Exception {
    SObject contact = randomContactSfdc();

    SfdcClient sfdcClient = env.sfdcClient();

    final List<Object> values = List.of(
        List.of("Contact ID", "Account Name", "Contact Mobile Phone"),
        List.of(contact.getId(), "FooBar Household", "123-456-7890")
    );
    postToBulkImport(values);

    SObject account = sfdcClient.getAccountById(contact.getField("AccountId").toString()).get();

    // ensure the old household name (generated by NPSP) was kept
    assertEquals(contact.getField("LastName") + " Household", account.getField("Name").toString());
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
    Thread.sleep(5000L);
  }
}
