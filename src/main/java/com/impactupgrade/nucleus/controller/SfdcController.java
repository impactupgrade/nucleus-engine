/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.util.GoogleSheetsUtil;
import com.sforce.soap.partner.sobject.SObject;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("/sfdc")
public class SfdcController {

  private static final Logger log = LogManager.getLogger(SfdcController.class.getName());

  protected final EnvironmentFactory envFactory;

  public SfdcController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

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
  // TODO: Make this generic through CrmController?
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
    Environment env = envFactory.init(request);

    Runnable thread = () -> {
      try {
        List<Map<String, String>> data = GoogleSheetsUtil.getSheetData(url);

        // cache all users by name
        // TODO: Terrible idea for any SF instance with a large number of users -- filter to non-guest only?
        List<SObject> users = env.sfdcClient().getActiveUsers();
        Map<String, String> userNameToId = new HashMap<>();
        for (SObject user : users) {
          userNameToId.put(user.getField("FirstName") + " " + user.getField("LastName"), user.getId());
          log.info("caching user {}: {} {}", user.getId(), user.getField("FirstName"), user.getField("LastName"));
        }

        for (int i = 0; i < data.size(); i++) {
          Map<String, String> row = data.get(i);

          log.info("processing row {} of {}: {}", i + 1, data.size(), row);

          bulkUpdate("Account", row, userNameToId, optionalFieldColumnName, env);
          bulkUpdate("Contact", row, userNameToId, optionalFieldColumnName, env);
        }

        // update anything left in the batch queues
        env.sfdcClient().batchFlush();
      } catch (Exception e) {
        log.error("bulkUpdate failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  private void bulkUpdate(String type, Map<String, String> row, Map<String, String> userNameToId,
      String optionalFieldColumnName, Environment env) throws InterruptedException {
    String id = row.get(type + " ID").trim();
    if (Strings.nullToEmpty(id).trim().isEmpty()) {
      // TODO: if ID is not included, support first retrieving by name, etc.
      log.warn("blank ID; did not {}}", type);
    } else {
      SObject sObject = new SObject(type);
      sObject.setId(id);

      String newOwner = row.get("New " + type + " Owner").trim();
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
      if (optionalFieldColumnName != null && optionalFieldColumnName.startsWith(type)) {
        String optionalFieldValue = row.get(optionalFieldColumnName).trim();
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

      env.sfdcClient().batchUpdate(sObject);
    }
  }

  // TODO: Make this generic through CrmController?
  @Path("/bulk-delete")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkDelete(
      @FormDataParam("google-sheet-url") String gsheetUrl,
      @FormDataParam("file") File file,
      @FormDataParam("file") FormDataContentDisposition fileDisposition,
      @Context HttpServletRequest request) {
    SecurityUtil.verifyApiKey(request);
    Environment env = envFactory.init(request);

    Runnable thread = () -> {
      try {
        List<Map<String, String>> data;
        if (!Strings.isNullOrEmpty(gsheetUrl)) {
          data = GoogleSheetsUtil.getSheetData(gsheetUrl);
        } else if (file != null) {
          CSVParser csvParser = CSVParser.parse(
              file,
              Charset.defaultCharset(),
              CSVFormat.DEFAULT
                  .withFirstRecordAsHeader()
                  .withIgnoreHeaderCase()
                  .withTrim()
          );
          data = new ArrayList<>();
          for (CSVRecord csvRecord : csvParser) {
            data.add(csvRecord.toMap());
          }
        } else {
          log.warn("no GSheet/CSV provided; skipping");
          return;
        }

        for (int i = 0; i < data.size(); i++) {
          Map<String, String> row = data.get(i);

          log.info("processing row {} of {}: {}", i + 2, data.size() + 1, row);

          // TODO: support others
          String opportunityId = row.get("Opportunity ID");
          SObject opportunity = new SObject("Opportunity");
          opportunity.setId(opportunityId);
          env.sfdcClient().batchDelete(opportunity);
        }
        env.sfdcClient().batchFlush();;
      } catch (Exception e) {
        log.error("bulkDelete failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  /**
   * Adding a new value to custom picklists involves adding it to the picklist itself, then enabling the new value
   * across all Account/Contact/Donation/RecurringDonation/Campaign record types. This endpoint automates the entire
   * process, end to end.
   */
  @Path("/picklist")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_PLAIN)
  public Response addValueToPicklist(
      @FormParam("globalPicklistApiName") String globalPicklistApiName,
      @FormParam("value") String newValue,
      @FormParam("recordTypeFieldApiNames") List<String> recordTypeFieldApiNames,
      @Context HttpServletRequest request
  ) {
    SecurityUtil.verifyApiKey(request);
    Environment env = envFactory.init(request);

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      try {
        env.sfdcMetadataClient().addValueToPicklist(globalPicklistApiName, newValue, recordTypeFieldApiNames);
        log.info("FINISHED: {}", globalPicklistApiName);
      } catch (Exception e) {
        log.error("{} failed", globalPicklistApiName, e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  /**
   * Imports a new iWave export file into SFDC.
   */
  @Path("/iwave")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response iwave(
      @FormDataParam("file") File file,
      @FormDataParam("file") FormDataContentDisposition fileDisposition,
      @Context HttpServletRequest request
  ) {
    SecurityUtil.verifyApiKey(request);
    Environment env = envFactory.init(request);

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      try {
        // Unfortunately, the iWave CSV exports do not include SF identifiers, only email. So we must first retrieve
        // all contacts by email and insert their SF IDs into the CSV.

        java.nio.file.Path combinedFile = File.createTempFile("iwave_import.csv", null).toPath();

        try (
            CSVParser csvParser = CSVParser.parse(
                file,
                Charset.defaultCharset(),
                CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .withIgnoreHeaderCase()
                    .withTrim()
            );

            BufferedWriter writer = Files.newBufferedWriter(combinedFile);

            CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader(
                "Id",
                "Date Scored",
                "Profile ID",
                "Profile URL",
                "iWave Score",
                "Propensity Rating",
                "Affinity Rating",
                "Primary Affinity Rating",
                "Secondary Affinity Rating",
                "Capacity Rating",
                "Est. Capacity Value",
                "Capacity Range",
                "Est. Capacity Source",
                "Planned Giving Bequest",
                "Planned Giving Annuity",
                "Planned Giving Trust",
                "RFM Score",
                "RFM Recency Rating",
                "RFM Frequency Rating",
                "RFM Monetary Rating"
            ));
        ) {
          int counter = 1; // let the loop start with 2 to account for the CSV header
          for (CSVRecord csvRecord : csvParser) {
            String email = csvRecord.get("Email");
            log.info("processing row {}: {}", counter++, email);

            if (!Strings.isNullOrEmpty(email)) {
              Optional<SObject> contact = env.sfdcClient().getContactByEmail(email);
              if (contact.isPresent()) {
                // SF expects date in yyyy-MM-dd'T'HH:mm:ss.SSS'Z, but iWave gives yyyy-MM-dd HH:mm
                Date date = new SimpleDateFormat("yyyy-MM-dd HH:mm").parse(csvRecord.get("Date Scored"));

                csvPrinter.printRecord(
                    contact.get().getId(),
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(date),
                    csvRecord.get("Profile ID"),
                    csvRecord.get("Profile URL"),
                    csvRecord.get("iWave Score"),
                    csvRecord.get("Propensity Rating"),
                    csvRecord.get("Affinity Rating"),
                    csvRecord.get("Primary Affinity Rating"),
                    csvRecord.get("Secondary Affinity Rating"),
                    csvRecord.get("Capacity Rating"),
                    csvRecord.get("Est. Capacity Value"),
                    csvRecord.get("Capacity Range"),
                    csvRecord.get("Est. Capacity Source"),
                    csvRecord.get("Planned Giving Bequest"),
                    csvRecord.get("Planned Giving Annuity"),
                    csvRecord.get("Planned Giving Trust"),
                    csvRecord.get("RFM Score"),
                    csvRecord.get("RFM Recency Rating"),
                    csvRecord.get("RFM Frequency Rating"),
                    csvRecord.get("RFM Monetary Rating")
                );
              } else {
                log.warn("Could not find contact: {}", email);
              }
            }
          }
        }

        env.sfdcBulkClient().uploadIWaveFile(combinedFile.toFile());
        log.info("FINISHED: iwave");
      } catch (Exception e) {
        log.error("iwave update failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  /**
   * Imports a new Windfall CSV file into SFDC.
   */
  @Path("/windfall")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response windfall(
      @FormDataParam("file") File file,
      @FormDataParam("file") FormDataContentDisposition fileDisposition,
      @Context HttpServletRequest request
  ) {
    SecurityUtil.verifyApiKey(request);
    Environment env = envFactory.init(request);

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      try {
        env.sfdcBulkClient().uploadWindfallFile(file);
        log.info("FINISHED: windfall");
      } catch (Exception e) {
        log.error("Windfall update failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }
}
