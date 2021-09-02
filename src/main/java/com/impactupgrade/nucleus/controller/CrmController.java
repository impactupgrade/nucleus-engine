/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ContactFormData;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.util.GoogleSheetsUtil;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/crm")
public class CrmController {

  private static final Logger log = LogManager.getLogger(CrmController.class.getName());

  protected final EnvironmentFactory envFactory;

  public CrmController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @Path("/bulk-import/file")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkImport(
      @FormDataParam("file") File file,
      @FormDataParam("file") FormDataContentDisposition fileDisposition,
      @Context HttpServletRequest request) {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    Runnable thread = () -> {
      try {
        CSVParser csvParser = CSVParser.parse(
            file,
            Charset.defaultCharset(),
            CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .withIgnoreHeaderCase()
                .withTrim()
        );
        List<Map<String, String>> data = new ArrayList<>();
        for (CSVRecord csvRecord : csvParser) {
          data.add(csvRecord.toMap());
        }

        List<CrmImportEvent> importEvents = CrmImportEvent.fromGeneric(data, env);
        env.primaryCrmService().processBulkImport(importEvents);
      } catch (Exception e) {
        log.error("bulkImport failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  @Path("/bulk-import/gsheet")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkImport(
      @FormParam("google-sheet-url") String gsheetUrl,
      @Context HttpServletRequest request) {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    Runnable thread = () -> {
      try {
        List<Map<String, String>> data = GoogleSheetsUtil.getSheetData(gsheetUrl);
        List<CrmImportEvent> importEvents = CrmImportEvent.fromGeneric(data, env);
        env.primaryCrmService().processBulkImport(importEvents);
      } catch (Exception e) {
        log.error("bulkImport failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  // TODO: Wasn't ultimately used by STS, and needs further testing, but keeping it for now...
  @Path("/bulk-import/fb-fundraisers")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkImportFBFundraisers(
      @FormDataParam("file") File file,
      @FormDataParam("file") FormDataContentDisposition fileDisposition,
      @Context HttpServletRequest request) {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    Runnable thread = () -> {
      try {
        CSVParser csvParser = CSVParser.parse(
            file,
            Charset.defaultCharset(),
            CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .withIgnoreHeaderCase()
                .withTrim()
        );
        List<Map<String, String>> data = new ArrayList<>();
        for (CSVRecord csvRecord : csvParser) {
          data.add(csvRecord.toMap());
        }

        List<CrmImportEvent> importEvents = CrmImportEvent.fromFBFundraiser(data, env);
        env.primaryCrmService().processBulkImport(importEvents);
      } catch (Exception e) {
        log.error("bulkImport failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  @Path("/bulk-update/file")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkUpdate(
      @FormDataParam("file") File file,
      @FormDataParam("file") FormDataContentDisposition fileDisposition,
      @Context HttpServletRequest request) {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    Runnable thread = () -> {
      try {
        CSVParser csvParser = CSVParser.parse(
            file,
            Charset.defaultCharset(),
            CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .withIgnoreHeaderCase()
                .withTrim()
        );
        List<Map<String, String>> data = new ArrayList<>();
        for (CSVRecord csvRecord : csvParser) {
          data.add(csvRecord.toMap());
        }

        List<CrmUpdateEvent> updateEvents = CrmUpdateEvent.fromGeneric(data, env);
        env.primaryCrmService().processBulkUpdate(updateEvents);
      } catch (Exception e) {
        log.error("bulkImport failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  @Path("/bulk-update/gsheet")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkUpdate(
      @FormParam("google-sheet-url") String gsheetUrl,
      @Context HttpServletRequest request) {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    Runnable thread = () -> {
      try {
        List<Map<String, String>> data = GoogleSheetsUtil.getSheetData(gsheetUrl);
        List<CrmUpdateEvent> updateEvents = CrmUpdateEvent.fromGeneric(data, env);
        env.primaryCrmService().processBulkUpdate(updateEvents);
      } catch (Exception e) {
        log.error("bulkImport failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  @Path("/contact-form")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_PLAIN)
  public Response contactForm(@BeanParam ContactFormData formData, @Context HttpServletRequest request) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    env.donorService().processContactFormSignup(formData);

    return Response.status(200).build();
  }
}