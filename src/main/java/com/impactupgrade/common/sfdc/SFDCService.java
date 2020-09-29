package com.impactupgrade.common.sfdc;

import com.google.common.base.Strings;
import com.impactupgrade.common.security.SecurityUtil;
import com.impactupgrade.common.util.GoogleSheetsUtil;
import com.sforce.soap.partner.sobject.SObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/sfdc")
public class SFDCService {

  private static final Logger log = LogManager.getLogger(SFDCService.class.getName());

  private static final SFDCClient sfdcClient = new SFDCClient();

  /**
   * Bulk update records using the given GSheet.
   *
   * Why not use the SF Bulk API? Great question! Although this is initially focused on SF, it may eventually shift
   * to ownership within a variety of platforms. Keep the one-by-one flexibility, for now.
   * 
   * Additionally, using the normal SF API allows us to do things like base updates on non-ID fields, etc. That
   * unfortunately isn't easily done with the Bulk API (or Data Loader) without jumping through hoops...
   *
   * TODO: Document the specific column headers we're supporting!
   */
  @Path("/bulk-update")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkUpdate(
      @FormParam("google-sheet-url") String url,
      @FormParam("optional-field-column-name") String optionalFieldColumnName,
      @Context HttpServletRequest request
  ) {
    SecurityUtil.verifyApiKey(request);

    Runnable thread = () -> {
      try {
        List<Map<String, String>> data = GoogleSheetsUtil.getSheetData(url);

        // cache all users by name
        // TODO: Terrible idea for any SF instance with a large number of users -- filter to non-guest only?
        List<SObject> users = sfdcClient.getActiveUsers();
        Map<String, String> userNameToId = new HashMap<>();
        for (SObject user : users) {
          userNameToId.put(user.getField("FirstName") + " " + user.getField("LastName"), user.getId());
          log.info("caching user {}: {} {}", user.getId(), user.getField("FirstName"), user.getField("LastName"));
        }

        for (int i = 0; i < data.size(); i++) {
          Map<String, String> row = data.get(i);

          log.info("processing row {} of {}: {}", i + 1, data.size(), row);

          bulkUpdate("Account", row, userNameToId, optionalFieldColumnName);
          bulkUpdate("Contact", row, userNameToId, optionalFieldColumnName);
        }

        // update anything left in the batch queues
        sfdcClient.batchFlush();
      } catch (Exception e) {
        log.error("bulkUpdate failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  private void bulkUpdate(String type, Map<String, String> row, Map<String, String> userNameToId,
      String optionalFieldColumnName) throws InterruptedException {
    String id = row.get(type + " ID");
    if (Strings.nullToEmpty(id).trim().isEmpty()) {
      // TODO: if ID is not included, support first retrieving by name, etc.
      log.warn("blank ID; did not {}}", type);
    } else {
      SObject sObject = new SObject(type);
      sObject.setId(id);

      String newOwner = row.get("New " + type + " Owner");
      if (!Strings.nullToEmpty(newOwner).trim().isEmpty()) {
        String newOwnerId = userNameToId.get(newOwner);
        if (newOwnerId == null) {
          log.warn("user ({}) not found in SFDC; did not update owner", newOwner);
        } else {
          sObject.setField("OwnerId", newOwnerId);
        }
      }

      // If an optional field was provided, the column name will be Type + Field name. Ex: "Account Top_Donor__c".
      // Make sure the column name starts with the SObject type we're working with!
      if (optionalFieldColumnName.startsWith(type)) {
        String optionalFieldValue = row.get(optionalFieldColumnName);
        if (!Strings.nullToEmpty(optionalFieldValue).trim().isEmpty()) {
          // strip the type from the beginning
          String field = optionalFieldColumnName.replace(type + " ", "");

          // special cases for the value
          Object value;
          if ("true".equalsIgnoreCase(optionalFieldValue)) {
            value = true;
          } else if ("false".equalsIgnoreCase(optionalFieldValue)) {
            value = false;
          } else {
            value = optionalFieldValue;
          }

          sObject.setField(field, value);
        }
      }

      System.out.println(sObject);
      sfdcClient.batchUpdate(sObject);
    }
  }
}
