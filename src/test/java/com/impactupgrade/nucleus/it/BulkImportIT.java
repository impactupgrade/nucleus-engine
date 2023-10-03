package com.impactupgrade.nucleus.it;

import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.impactupgrade.nucleus.App;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.sforce.soap.partner.sobject.SObject;
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

public class BulkImportIT extends AbstractIT {

  protected BulkImportIT() {
    super(new App(envFactorySfdcStripe));
  }

  @Test
  public void appendPicklist() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    SObject contact1 = randomContactSfdc();
    SObject contact2 = randomContactSfdc();
    SObject contact3 = randomContactSfdc();
    SObject contact4 = randomContactSfdc();

    contact1.setField("Test_Multi_Select__c", "Value 1");
    contact2.setField("Test_Multi_Select__c", "Value 1;Value 3");
    contact3.setField("Test_Multi_Select__c", "Value 2");
    // nothing for contact 4
    sfdcClient.batchUpdate(contact1);
    sfdcClient.batchUpdate(contact2);
    sfdcClient.batchUpdate(contact3);
    sfdcClient.batchFlush();

    final CsvMapper csvMapper = new CsvMapper();
    final List<Object> values = List.of(
        List.of("Contact ID", "Contact Custom Append Test_Multi_Select__c"),
        List.of(contact1.getId(), "Value 2"),
        List.of(contact2.getId(), "Value 2"),
        List.of(contact3.getId(), "Value 2"),
        List.of(contact4.getId(), "Value 2")
    );

    File file = File.createTempFile("nucleus-it-", ".csv");
    csvMapper.writeValue(file, values);

    final FileDataBodyPart filePart = new FileDataBodyPart("file", file);
    FormDataMultiPart formDataMultiPart = new FormDataMultiPart();
    final FormDataMultiPart multiPart = (FormDataMultiPart) formDataMultiPart.bodyPart(filePart);
    Response response = target("/api/crm/bulk-import/file").request().header("Nucleus-Api-Key", "abc123")
        .post(Entity.entity(multiPart, multiPart.getMediaType()));
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

    Thread.sleep(5000L);

    contact1 = sfdcClient.getContactById(contact1.getId(), "Test_Multi_Select__c").get();
    contact2 = sfdcClient.getContactById(contact2.getId(), "Test_Multi_Select__c").get();
    contact3 = sfdcClient.getContactById(contact3.getId(), "Test_Multi_Select__c").get();
    contact4 = sfdcClient.getContactById(contact4.getId(), "Test_Multi_Select__c").get();

    assertEquals("Value 1;Value 2", contact1.getField("Test_Multi_Select__c").toString());
    assertEquals("Value 1;Value 2;Value 3", contact2.getField("Test_Multi_Select__c").toString());
    assertEquals("Value 2", contact3.getField("Test_Multi_Select__c").toString());
    assertEquals("Value 2", contact4.getField("Test_Multi_Select__c").toString());
  }
}
