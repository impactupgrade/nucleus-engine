/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.util.GoogleSheetsUtil;
import com.impactupgrade.nucleus.util.Utils;
import com.sforce.soap.partner.sobject.SObject;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
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
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Path("/sfdc")
public class SfdcController {

  protected final EnvironmentFactory envFactory;

  public SfdcController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  // TODO: Make this generic through CrmController?
  @Path("/bulk-delete")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkDelete(
      @FormDataParam("google-sheet-url") String gsheetUrl,
      @FormDataParam("file") InputStream inputStream,
      @FormDataParam("file") FormDataContentDisposition fileDisposition,
      @FormDataParam("nucleus-username") String nucleusUsername,
      @Context HttpServletRequest request) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    // Important to do this outside of the new thread -- ensures the InputStream is still open.
    List<Map<String, String>> data;
    if (!Strings.isNullOrEmpty(gsheetUrl)) {
      data = GoogleSheetsUtil.getSheetData(gsheetUrl);
    } else if (inputStream != null) {
      data = Utils.getCsvData(inputStream);
    } else {
      env.logJobWarn("no GSheet/CSV provided; skipping");
      return Response.status(400).build();
    }

    Runnable thread = () -> {
      try {
        String jobName = "SFDC: Bulk Delete";
        env.startJobLog(JobType.PORTAL_TASK, nucleusUsername, jobName, "Sfdc");

        SfdcClient sfdcClient = env.sfdcClient();

        Set<String> opportunityIds = new HashSet<>();
        Set<String> contactIds = new HashSet<>();
        Set<String> accountIds = new HashSet<>();

        int size = data.size();
        for (int i = 0; i < size; i++) {
          Map<String, String> row = data.get(i);

          env.logJobInfo("processing row {} of {}: {}", i + 2, size + 1, row);

          if (row.containsKey("Opportunity ID")) {
            opportunityIds.add(row.get("Opportunity ID"));
          }
          if (row.containsKey("Contact ID")) {
            contactIds.add(row.get("Contact ID"));

          }
          if (row.containsKey("Account ID")) {
            accountIds.add(row.get("Account ID"));
          }
          env.logJobInfo("{} row of {} processed", (i + 1), size);
        }

        for (String opportunityId : opportunityIds) {
          SObject opportunity = new SObject("Opportunity");
          opportunity.setId(opportunityId);
          sfdcClient.batchDelete(opportunity);
        }
        env.logJobInfo("batch delete opportunities done");

        for (String contactId : contactIds) {
          SObject contact = new SObject("Contact");
          contact.setId(contactId);
          sfdcClient.batchDelete(contact);
        }
        env.logJobInfo("batch delete contacts done");

        for (String accountId : accountIds) {
          SObject account = new SObject("Account");
          account.setId(accountId);
          sfdcClient.batchDelete(account);
        }
        env.logJobInfo("batch delete accounts done");

        sfdcClient.batchFlush();
        env.endJobLog(JobStatus.DONE);
      } catch (Exception e) {
        env.logJobError("bulkDelete failed", e);
        env.logJobError(e.getMessage());
        env.endJobLog(JobStatus.FAILED);
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
      @FormParam("nucleus-username") String nucleusUsername,
      @Context HttpServletRequest request
  ) {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      try {
        String jobName = "SFDC: Add Picklist Value";
        env.startJobLog(JobType.PORTAL_TASK, nucleusUsername, jobName, "Sfdc");
        env.sfdcMetadataClient().addValueToPicklist(globalPicklistApiName, newValue, recordTypeFieldApiNames);
        env.logJobInfo("FINISHED: {}", globalPicklistApiName);
        env.endJobLog(JobStatus.DONE);
      } catch (Exception e) {
        env.logJobError("{} failed", globalPicklistApiName, e);
        env.logJobError(e.getMessage());
        env.endJobLog(JobStatus.FAILED);
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
      @FormDataParam("file") InputStream inputStream,
      @FormDataParam("file") FormDataContentDisposition fileDisposition,
      @FormDataParam("nucleus-username") String nucleusUsername,
      @Context HttpServletRequest request
  ) {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      try {
        String jobName = "SFDC: iWave Import";
        env.startJobLog(JobType.PORTAL_TASK, nucleusUsername, jobName, "Sfdc");

        // Unfortunately, the iWave CSV exports do not include SF identifiers, only email. So we must first retrieve
        // all contacts by email and insert their SF IDs into the CSV.

        java.nio.file.Path combinedFile = File.createTempFile("iwave_import.csv", null).toPath();

        try (
            CSVParser csvParser = CSVParser.parse(
                inputStream,
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
            ))
        ) {
          int counter = 1; // let the loop start with 2 to account for the CSV header
          for (CSVRecord csvRecord : csvParser) {
            counter++;

            String email = csvRecord.get("Email");
            env.logJobInfo("processing row {}: {}", counter, email);

            if (!Strings.isNullOrEmpty(email)) {
              Optional<SObject> contact = env.sfdcClient().searchContacts(ContactSearch.byEmail(email)).stream().findFirst();
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
                env.logJobWarn("Could not find contact: {}", email);
              }
            }
          }
        }

        env.sfdcBulkClient().uploadIWaveFile(combinedFile.toFile());
        env.endJobLog(JobStatus.DONE);
      } catch (Exception e) {
        env.logJobError("iwave update failed", e);
        env.logJobError(e.getMessage());
        env.endJobLog(JobStatus.FAILED);
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
      @FormDataParam("file") InputStream inputStream,
      @FormDataParam("file") FormDataContentDisposition fileDisposition,
      @FormDataParam("nucleus-username") String nucleusUsername,
      @Context HttpServletRequest request
  ) {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      try {
        String jobName = "SFDC: Windfall Import";
        env.startJobLog(JobType.PORTAL_TASK, nucleusUsername, jobName, "Sfdc");
        env.sfdcBulkClient().uploadWindfallFile(inputStream);
        env.endJobLog(JobStatus.DONE);
      } catch (Exception e) {
        env.logJobError("Windfall update failed", e);
        env.logJobError(e.getMessage());
        env.endJobLog(JobStatus.FAILED);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }
}
