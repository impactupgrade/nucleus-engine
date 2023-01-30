/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.SfdcMetadataClient;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ContactFormData;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmCustomField;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.impactupgrade.nucleus.util.Utils.noWhitespace;
import static com.impactupgrade.nucleus.util.Utils.trim;

@Path("/crm")
public class CrmController {

  private static final Logger log = LogManager.getLogger(CrmController.class.getName());

  protected final EnvironmentFactory envFactory;
  private SfdcMetadataClient sfdcMetadataClient;

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

    id = noWhitespace(id);
    email = noWhitespace(email);
    phone = trim(phone);

    CrmService crmService = env.primaryCrmService();

    Optional<CrmContact> contact = Optional.empty();
    if (!Strings.isNullOrEmpty(id)) {
      log.info("searching id={}", id);
      contact = crmService.getContactById(id);
    } else if (!Strings.isNullOrEmpty(email)) {
      log.info("searching email={}", email);
      contact = crmService.searchContacts(ContactSearch.byEmail(email)).getSingleResult();
    } else if (!Strings.isNullOrEmpty(phone)) {
      log.info("searching phone={}", phone);
      contact = crmService.searchContacts(ContactSearch.byPhone(phone)).getSingleResult();
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
      @FormDataParam("nucleus-username") String nucleusUsername,
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

    List<CrmImportEvent> importEvents = CrmImportEvent.fromGeneric(data);

    Runnable thread = () -> {
      try {
        String jobName = "Bulk Import: File";
        env.startJobLog(JobType.PORTAL_TASK, nucleusUsername, jobName, "Nucleus Portal");
        env.primaryCrmService().processBulkImport(importEvents);
        env.endJobLog(jobName);
      } catch (Exception e) {
        log.error("bulkImport failed", e);
        env.logJobError(e.getMessage());
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
      @FormParam("nucleus-username") String nucleusUsername,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    gsheetUrl = noWhitespace(gsheetUrl);

    List<Map<String, String>> data = GoogleSheetsUtil.getSheetData(gsheetUrl);
    List<CrmImportEvent> importEvents = CrmImportEvent.fromGeneric(data);

    Runnable thread = () -> {
      try {
        String jobName = "Bulk Import: Google Sheet";
        env.startJobLog(JobType.PORTAL_TASK, nucleusUsername, jobName, "Nucleus Portal");
        env.primaryCrmService().processBulkImport(importEvents);
        env.endJobLog(jobName);
      } catch (Exception e) {
        log.error("bulkImport failed", e);
        env.logJobError(e.getMessage());
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
      @FormDataParam("nucleus-username") String nucleusUsername,
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

    List<CrmImportEvent> importEvents = CrmImportEvent.fromFBFundraiser(data);

    Runnable thread = () -> {
      try {
        String jobName = "Bulk Import: Facebook";
        env.startJobLog(JobType.PORTAL_TASK, nucleusUsername, jobName, "Nucleus Portal");
        env.primaryCrmService().processBulkImport(importEvents);
        env.endJobLog("job completed");
      } catch (Exception e) {
        log.error("bulkImport failed", e);
        env.logJobError(e.getMessage());
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  @Path("/bulk-import/greater-giving")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkImportGreaterGiving(
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

    List<CrmImportEvent> importEvents = CrmImportEvent.fromGreaterGiving(data);

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
  public Response searchRecurringDonations(
      @FormParam("name") String name,
      @FormParam("email") String email,
      @FormParam("phone") String phone,
      Form rawFormData,
      @Context HttpServletRequest request
  ) throws Exception {
    // other env context might be passed in raw form data, so use this init method
    Environment env = envFactory.init(request, rawFormData.asMap());
    SecurityUtil.verifyApiKey(env);

    name = trim(name);
    email = noWhitespace(email);
    phone = trim(phone);

    List<CrmRecurringDonation> recurringDonations = env.donationsCrmService().searchAllRecurringDonations(
        Optional.ofNullable(Strings.emptyToNull(name)),
        Optional.ofNullable(Strings.emptyToNull(email)),
        Optional.ofNullable(Strings.emptyToNull(phone))
    );
    return Response.status(200).entity(recurringDonations).build();
  }

  @Path("/donations-total")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public Response donationsTotal(
      @QueryParam("filter") String filter,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);

    double donationsTotal = env.donationsCrmService().getDonationsTotal(filter);
    return Response.status(200).entity(donationsTotal).build();
  }

  // TODO: This will need updated depending on the Portal onboarding strategy. More likely, we'll have checkboxes and
  //  drop-downs to provision fields based on contexts (add all payment gateway fields, add text messaging fields, etc.)
  //  Or in the very least, set this up to receive multiple field definitions at once (JSON)? And it likely needs combined
  //  with the code we have in CrmSetupUtils, creating the fields as well (would need the type, length, etc)
  @Path("/provision-fields")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response provisionFields(
          @FormParam("layout-name") String layoutName, //TODO: custom request model?
          List<CrmCustomField> crmCustomFields,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    // TODO: use input fields
    List<CrmCustomField> defaultCustomFields = List.of(
            new CrmCustomField("Opportunity", "Payment_Gateway_Name__c", "Payment Gateway Name", CrmCustomField.Type.TEXT, 100),
            new CrmCustomField("Opportunity", "Payment_Gateway_Transaction_ID__c", "Payment Gateway Transaction ID", CrmCustomField.Type.TEXT, 100),
            new CrmCustomField("Opportunity", "Payment_Gateway_Customer_ID__c", "Payment Gateway Customer ID", CrmCustomField.Type.TEXT, 100),
            new CrmCustomField("npe03__Recurring_Donation__c", "Payment_Gateway_Customer_ID__c", "Payment Gateway Customer ID", CrmCustomField.Type.TEXT, 100),
            new CrmCustomField("npe03__Recurring_Donation__c", "Payment_Gateway_Subscription_ID__c", "Payment Gateway Subscription ID", CrmCustomField.Type.TEXT, 100),
            new CrmCustomField("Opportunity", "Payment_Gateway_Deposit_ID__c", "Payment Gateway Deposit ID", CrmCustomField.Type.TEXT, 100),
            new CrmCustomField("Opportunity", "Payment_Gateway_Deposit_Date__c", "Payment Gateway Deposit Date", CrmCustomField.Type.DATE, 16, 2),
            new CrmCustomField("Opportunity", "Payment_Gateway_Deposit_Net_Amount__c", "Payment Gateway Deposit Net Amount", CrmCustomField.Type.CURRENCY, 18, 2),
            new CrmCustomField("Opportunity", "Payment_Gateway_Deposit_Fee__c", "Payment Gateway Deposit Fee", CrmCustomField.Type.CURRENCY, 18, 2)
    );
    List<CrmCustomField> insertedFields = env.primaryCrmService().insertCustomFields(layoutName, defaultCustomFields);

    return Response.ok(insertedFields).build();
  }

  @Path("/contact-lists")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getContactLists(
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);
    CrmService crmService = env.primaryCrmService();
    Map<String, String> lists = crmService.getContactLists();

    return Response.status(200).entity(lists).build();
  }

  @Path("/contact-fields")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getContactFields(
          @QueryParam("type") String type,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);
    CrmService crmService = env.primaryCrmService();
    String filter = "";

    switch (type){
      case "sms":
        filter = ".*(?i:sms|opt|subscri|text|sign).*";
        break;
      case "contactLanguage":
        filter = ".*(?i:prefer|lang).*";
        break;
      default:
        filter = "(?s).*";
        break;
    }

    Map<String, String> fullList = crmService.getFieldOptions("contact" );

    Map<String, String> filteredList = new HashMap<>();
    Pattern pattern = Pattern.compile(filter);

    for (Map.Entry<String, String> entry : fullList.entrySet()) {
      String value = entry.getValue();
      if (pattern.matcher(value).matches()) {
        filteredList.put(entry.getKey(), value);
      }
    }

    return Response.status(200).entity(filteredList).build();
  }
}