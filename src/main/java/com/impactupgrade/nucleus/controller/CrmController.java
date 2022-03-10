/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ContactFormData;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.service.segment.CrmService;
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
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("/crm")
public class CrmController {

  private static final Logger log = LogManager.getLogger(CrmController.class.getName());

  protected final EnvironmentFactory envFactory;

  public CrmController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  /**
   * Retrieves a contact from the primary CRM using a variety of optional parameters. For use in external integrations,
   * like Twilio Studio's retrieval of the CRM's Contact ID by phone number.
   */
  @Path("/contact")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getContact(
      @QueryParam("id") String id,
      @QueryParam("email") String email,
      @QueryParam("phone") String phone,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    CrmService crmService = env.primaryCrmService();

    Optional<CrmContact> contact = Optional.empty();
    if (!Strings.isNullOrEmpty(id)) {
      log.info("searching id={}", id);
      contact = crmService.getContactById(id);
    } else if (!Strings.isNullOrEmpty(email)) {
      log.info("searching email={}", email);
      contact = crmService.getContactByEmail(email);
    } else if (!Strings.isNullOrEmpty(phone)) {
      log.info("searching phone={}", phone);
      contact = crmService.getContactByPhone(phone);
    } else {
      log.warn("no search params provided");
    }

    if (contact.isPresent()) {
      log.info("returning Contact {}", contact.get().id);
      return Response.status(200).entity(contact.get()).build();
    } else {
      log.info("Contact not found");
      return Response.status(404).build();
    }
  }

  @Path("/bulk-import/file")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkImport(
      @FormDataParam("file") InputStream inputStream,
      @FormDataParam("file") FormDataContentDisposition fileDisposition,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    // Important to do this outside of the new thread -- ensures the InputStream is still open.
    CSVParser csvParser = CSVParser.parse(
        inputStream,
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

    Runnable thread = () -> {
      try {
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
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    List<Map<String, String>> data = GoogleSheetsUtil.getSheetData(gsheetUrl);
    List<CrmImportEvent> importEvents = CrmImportEvent.fromGeneric(data, env);

    Runnable thread = () -> {
      try {
        env.primaryCrmService().processBulkImport(importEvents);
      } catch (Exception e) {
        log.error("bulkImport failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  @Path("/bulk-import/fb-fundraisers")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkImportFBFundraisers(
      @FormDataParam("file") InputStream inputStream,
      @FormDataParam("file") FormDataContentDisposition fileDisposition,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    // Important to do this outside of the new thread -- ensures the InputStream is still open.
    CSVParser csvParser = CSVParser.parse(
        inputStream,
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

    Runnable thread = () -> {
      try {
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
      @FormDataParam("file") InputStream inputStream,
      @FormDataParam("file") FormDataContentDisposition fileDisposition,
      @Context HttpServletRequest request) {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    Runnable thread = () -> {
      try {
        CSVParser csvParser = CSVParser.parse(
            inputStream,
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
    // no auth

    env.contactService().processContactForm(formData);

    return Response.status(200).build();
  }

  @Path("/recurring-donations/search")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response donorRecurringDonations(
      @FormParam("name") String name,
      @FormParam("email") String email,
      @FormParam("phone") String phone,
      Form rawFormData,
      @Context HttpServletRequest request
  ) throws Exception {
    // other env context might be passed in raw form data, so use this init method
    Environment env = envFactory.init(request, rawFormData.asMap());

    List<CrmRecurringDonation> recurringDonations = env.primaryCrmService().searchOpenRecurringDonations(
        Optional.ofNullable(Strings.emptyToNull(name)),
        Optional.ofNullable(Strings.emptyToNull(email)),
        Optional.ofNullable(Strings.emptyToNull(phone))
    );
    return Response.status(200).entity(recurringDonations).build();
  }
}