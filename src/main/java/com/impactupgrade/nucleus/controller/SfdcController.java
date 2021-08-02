/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.SfdcBulkClient;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.client.SfdcMetadataClient;
import com.impactupgrade.nucleus.environment.Environment;
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
import org.springframework.stereotype.Controller;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

@Controller
@Path("/sfdc")
public class SfdcController {

  private static final Logger log = LogManager.getLogger(SfdcController.class.getName());

  protected final Environment env;
  protected final SfdcClient sfdcClient;
  protected final SfdcBulkClient sfdcBulkClient;
  protected final SfdcMetadataClient sfdcMetadataClient;

  public SfdcController(Environment env, SfdcClient sfdcClient, SfdcBulkClient sfdcBulkClient,
          SfdcMetadataClient sfdcMetadataClient) {
    this.env = env;
    this.sfdcClient = sfdcClient;
    this.sfdcBulkClient = sfdcBulkClient;
    this.sfdcMetadataClient = sfdcMetadataClient;
  }

  // TODO: Make this generic through CrmController?
  @Path("/bulk-delete")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkDelete(
      @FormDataParam("google-sheet-url") String gsheetUrl,
      @FormDataParam("file") File file,
      @FormDataParam("file") FormDataContentDisposition fileDisposition) {
    SecurityUtil.verifyApiKey(env);

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
          sfdcClient.batchDelete(opportunity);
        }
        sfdcClient.batchFlush();;
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
      @FormParam("recordTypeFieldApiNames") List<String> recordTypeFieldApiNames
  ) {
    SecurityUtil.verifyApiKey(env);

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      try {
        sfdcMetadataClient.addValueToPicklist(globalPicklistApiName, newValue, recordTypeFieldApiNames);
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
      @FormDataParam("file") FormDataContentDisposition fileDisposition
  ) {
    SecurityUtil.verifyApiKey(env);

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
              Optional<SObject> contact = sfdcClient.getContactByEmail(email);
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

        sfdcBulkClient.uploadIWaveFile(combinedFile.toFile());
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
      @FormDataParam("file") FormDataContentDisposition fileDisposition
  ) {
    SecurityUtil.verifyApiKey(env);

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      try {
        sfdcBulkClient.uploadWindfallFile(file);
        log.info("FINISHED: windfall");
      } catch (Exception e) {
        log.error("Windfall update failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }
}
