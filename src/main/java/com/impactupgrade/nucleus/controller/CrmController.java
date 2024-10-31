/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.SfdcMetadataClient;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.AccountSearch;
import com.impactupgrade.nucleus.model.ContactFormData;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmContactListType;
import com.impactupgrade.nucleus.model.CrmCustomField;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.util.GoogleSheetsUtil;
import com.impactupgrade.nucleus.util.Utils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
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
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.util.Utils.noWhitespace;
import static com.impactupgrade.nucleus.util.Utils.trim;

@Path("/crm")
public class CrmController {

  protected final EnvironmentFactory envFactory;
  private SfdcMetadataClient sfdcMetadataClient;

  public CrmController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @Path("/contact")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getContact(
          @QueryParam("keyword") String keywords,
          @QueryParam("email") String email,
          @QueryParam("phone") String phone,
          @QueryParam("crmType") String crmType,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    keywords = trim(keywords);
    email = noWhitespace(email);
    phone = trim(phone);

    CrmService crmService = getCrmService(env, crmType);

    Optional<CrmContact> contact = Optional.empty();
    if (!Strings.isNullOrEmpty(keywords)) {
      env.logJobInfo("searching keyword={}", keywords);
      contact = crmService.searchContacts(ContactSearch.byKeywords(keywords)).getSingleResult();
    } else if (!Strings.isNullOrEmpty(email)) {
      env.logJobInfo("searching email={}", email);
      contact = crmService.searchContacts(ContactSearch.byEmail(email)).getSingleResult();
    } else if (!Strings.isNullOrEmpty(phone)) {
      env.logJobInfo("searching phone={}", phone);
      contact = crmService.searchContacts(ContactSearch.byPhone(phone)).getSingleResult();
    } else {
      env.logJobWarn("no search params provided");
    }

    if (contact.isPresent()) {
      env.logJobInfo("returning Contact {}", contact.get().id);
      return Response.status(200).entity(contact.get()).build();
    } else {
      env.logJobInfo("Contact not found");
      return Response.status(404).build();
    }
  }

  @Path("/contact")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response upsertContact(
          @FormParam("account_owner_email") String accountOwnerEmail,
          @FormParam("account_id") String accountId,
          @FormParam("account_name") String accountName,
          @FormParam("account_email") String accountEmail,
          @FormParam("account_phone") String accountPhone,
          @FormParam("account_website") String accountWebsite,
          @FormParam("account_type") String accountType,
          @FormParam("account_street") String accountStreet,
          @FormParam("account_street_2") String accountStreet2,
          @FormParam("account_city") String accountCity,
          @FormParam("account_state") String accountState,
          @FormParam("account_zip") String accountZip,
          @FormParam("account_country") String accountCountry,
          @FormParam("contact_owner_email") String contactOwnerEmail,
          @FormParam("contact_first_name") String contactFirstName,
          @FormParam("contact_last_name") String contactLastName,
          @FormParam("contact_email") String contactEmail,
          @FormParam("contact_phone_pref") String contactPhonePref,
          @FormParam("contact_home_phone") String contactHomePhone,
          @FormParam("contact_work_phone") String contactWorkPhone,
          @FormParam("contact_mobile_phone") String contactMobilePhone,
          @FormParam("contact_street") String contactStreet,
          @FormParam("contact_street_2") String contactStreet2,
          @FormParam("contact_city") String contactCity,
          @FormParam("contact_state") String contactState,
          @FormParam("contact_zip") String contactZip,
          @FormParam("contact_country") String contactCountry,
          @QueryParam("crmType") String crmType,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    try {
      CrmService crmService = getCrmService(env, crmType);

      // TODO: update support

      if (Strings.isNullOrEmpty(accountId)) {
        CrmAccount newAccount = new CrmAccount();

        if (crmService.getUserByEmail(accountOwnerEmail).isPresent()) {
          newAccount.ownerId = crmService.getUserByEmail(accountOwnerEmail).get().id();
        }
        newAccount.name = accountName;
        newAccount.email = accountPhone;
        newAccount.phone = accountPhone;
        newAccount.website = accountWebsite;
        if (!Strings.isNullOrEmpty(accountType)) {
          newAccount.recordType = EnvironmentConfig.AccountType.valueOf(accountType.toUpperCase(Locale.ROOT));
        }
        newAccount.billingAddress.street = accountStreet;
        if (!Strings.isNullOrEmpty(accountStreet2)) {
          newAccount.billingAddress.street = newAccount.billingAddress.street + "," + accountStreet2;
        }
        newAccount.billingAddress.city = accountCity;
        newAccount.billingAddress.state = accountState;
        newAccount.billingAddress.postalCode = accountZip;
        newAccount.billingAddress.country = accountCountry;

        accountId = crmService.insertAccount(newAccount);
      }

      CrmContact newContact = new CrmContact();

      if (crmService.getUserByEmail(contactOwnerEmail).isPresent()) {
        newContact.ownerId = crmService.getUserByEmail(contactOwnerEmail).get().id();
      }
      newContact.firstName = contactFirstName;
      newContact.lastName = contactLastName;
      newContact.email = contactEmail;
      newContact.preferredPhone = CrmContact.PreferredPhone.valueOf(contactPhonePref);
      newContact.homePhone = contactHomePhone;
      newContact.mobilePhone = contactMobilePhone;
      newContact.workPhone = contactWorkPhone;
      newContact.account.id = accountId;
      newContact.mailingAddress.street = contactStreet;
      if (!Strings.isNullOrEmpty(contactStreet2)) {
        newContact.mailingAddress.street = newContact.mailingAddress.street + "," + contactStreet2;
      }
      newContact.mailingAddress.city = contactCity;
      newContact.mailingAddress.state = contactState;
      newContact.mailingAddress.postalCode = contactZip;
      newContact.mailingAddress.country = contactCountry;

      crmService.insertContact(newContact);

      return Response.ok().build();
    } catch (Exception e) {
      env.logJobError("failed to create account/contact", e);
      return Response.serverError().build();
    }
  }

  @Path("/account")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getAllAccounts(
          @QueryParam("crmType") String crmType,
          @Context HttpServletRequest request
  ) {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    try {
      CrmService crmService = getCrmService(env, crmType);
      AccountSearch accountSearch = new AccountSearch();
      accountSearch.basicSearch = true;
      List<CrmAccount> accounts = crmService.searchAccounts(accountSearch);
      return Response.ok().entity(accounts).build();
    } catch (Exception e) {
      env.logJobError("failed to get accounts", e);
      return Response.serverError().build();
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
          @QueryParam("crmType") String crmType,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    String jobName = "Bulk Import: File";
    env.startJobLog(JobType.PORTAL_TASK, nucleusUsername, jobName, "Nucleus Portal");

    try {
      // Important to do this outside of the new thread -- ensures the InputStream is still open.
      List<Map<String, String>> data = toListOfMap(inputStream, fileDisposition);
      List<CrmImportEvent> importEvents = toCrmImportEvents(data);

      Runnable thread = () -> {
        try {
          env.primaryCrmService().processBulkImport(importEvents);
          env.endJobLog(JobStatus.DONE);
        } catch (Exception e) {
          env.logJobError("bulkImport failed", e);
          env.endJobLog(JobStatus.FAILED);
        }
      };
      new Thread(thread).start();

      return Response.status(200).build();
    } catch (Exception e) {
      env.logJobError("bulkImport failed", e);
      env.endJobLog(JobStatus.FAILED);
      throw e;
    }
  }

  @Path("/bulk-import/gsheet")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkImport(
          @FormParam("google-sheet-url") String gsheetUrl,
          @FormParam("nucleus-username") String nucleusUsername,
          @QueryParam("crmType") String crmType,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    gsheetUrl = noWhitespace(gsheetUrl);

    List<Map<String, String>> data = GoogleSheetsUtil.getSheetData(gsheetUrl);
    List<CrmImportEvent> importEvents = toCrmImportEvents(data);
    CrmService crmService = getCrmService(env, crmType);

    Runnable thread = () -> {
      try {
        String jobName = "Bulk Import: Google Sheet";
        env.startJobLog(JobType.PORTAL_TASK, nucleusUsername, jobName, "Nucleus Portal");
        crmService.processBulkImport(importEvents);
        env.endJobLog(JobStatus.DONE);
      } catch (Exception e) {
        env.logJobError("bulkImport failed", e);
        env.logJobError(e.getMessage());
        env.endJobLog(JobStatus.FAILED);
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
          @QueryParam("crmType") String crmType,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    // Important to do this outside of the new thread -- ensures the InputStream is still open.
    List<Map<String, String>> data = toListOfMap(inputStream, fileDisposition);
    List<CrmImportEvent> importEvents = toCrmImportEvents(data, this::fromFBFundraiser);
    CrmService crmService = getCrmService(env, crmType);

    Runnable thread = () -> {
      try {
        String jobName = "Bulk Import: Facebook";
        env.startJobLog(JobType.PORTAL_TASK, nucleusUsername, jobName, "Nucleus Portal");
        crmService.processBulkImport(importEvents);
        env.endJobLog(JobStatus.DONE);
      } catch (Exception e) {
        env.logJobError("bulkImport failed", e);
        env.logJobError(e.getMessage());
        env.endJobLog(JobStatus.FAILED);
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
          @QueryParam("crmType") String crmType,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    // Important to do this outside of the new thread -- ensures the InputStream is still open.
    // Excel is expected. Greater Giving has an Excel report export purpose built for SFDC.
    // TODO: But, what if a different CRM is targeted?
    List<Map<String, String>> data = toListOfMap(inputStream, fileDisposition);
    List<CrmImportEvent> importEvents = toCrmImportEvents(data, this::fromGreaterGiving);

    CrmService crmService = getCrmService(env, crmType);

    Runnable thread = () -> {
      try {
        crmService.processBulkImport(importEvents);
      } catch (Exception e) {
        env.logJobError("bulkImport failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  @Path("/bulk-import/classy")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response bulkImportClassy(
          @FormDataParam("file") InputStream inputStream,
          @FormDataParam("file") FormDataContentDisposition fileDisposition,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    List<Map<String, String>> data = toListOfMap(inputStream, fileDisposition);
    List<CrmImportEvent> importEvents = toCrmImportEvents(data, this::fromClassy);

    Runnable thread = () -> {
      try {
        env.primaryCrmService().processBulkImport(importEvents);
      } catch (Exception e) {
        env.logJobError("bulkImport failed", e);
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
    env.logJobInfo("Filter: {}", filter);

    if (Strings.isNullOrEmpty(filter)) {
      return Response.status(Response.Status.BAD_REQUEST).build();
    }

    double donationsTotal = env.donationsCrmService().getDonationsTotal(filter);
    return Response.status(200).entity(donationsTotal).build();
  }

  @Path("/provision-fields/file")
  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.TEXT_PLAIN)
  public Response provisionFields(
          @FormDataParam("file") InputStream inputStream,
          @FormDataParam("file") FormDataContentDisposition fileDisposition,
          @QueryParam("crmType") String crmType,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    List<CrmCustomField> customFields = new ArrayList<>();

    // Important to do this outside of the new thread -- ensures the InputStream is still open.
    CSVParser csvParser = CSVParser.parse(
            inputStream,
            Charset.defaultCharset(),
            CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .withIgnoreHeaderCase()
                    .withTrim()
    );
    for (CSVRecord csvRecord : csvParser) {
      Map<String, String> data = csvRecord.toMap();
      customFields.add(CrmCustomField.fromGeneric(data));
    }

    CrmService crmService = getCrmService(env, crmType);

    Runnable thread = () -> {
      try {
        crmService.insertCustomFields(customFields);
      } catch (Exception e) {
        env.logJobError("provisionFields failed", e);
      }
    };
    new Thread(thread).start();

    return Response.ok().build();
  }

  @Path("/provision-fields/gsheet")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.TEXT_PLAIN)
  public Response provisionFields(
          @FormParam("google-sheet-url") String gsheetUrl,
          @QueryParam("crmType") String crmType,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    gsheetUrl = noWhitespace(gsheetUrl);

    List<Map<String, String>> data = GoogleSheetsUtil.getSheetData(gsheetUrl);
    List<CrmCustomField> customFields = CrmCustomField.fromGeneric(data);
    CrmService crmService = getCrmService(env, crmType);

    Runnable thread = () -> {
      try {
        crmService.insertCustomFields(customFields);
      } catch (Exception e) {
        env.logJobError("provisionFields failed", e);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  @Path("/contact-lists")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getContactLists(
          @QueryParam("crmType") String crmType,
          @QueryParam("listType") String _listType,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    CrmContactListType listType;
    if (Strings.isNullOrEmpty(_listType)) {
      listType = CrmContactListType.ALL;
    } else {
      listType = CrmContactListType.valueOf(_listType.trim().toUpperCase(Locale.ROOT));
    }

    CrmService crmService = getCrmService(env, crmType);
    Map<String, String> lists = crmService.getContactLists(listType);

    return Response.status(200).entity(lists).build();
  }

  @Path("/contact-fields")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getContactFields(
          @QueryParam("type") String type,
          @QueryParam("crmType") String crmType,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);
    CrmService crmService = getCrmService(env, crmType);
    String filter = "";

    if (Strings.isNullOrEmpty(type)) {
      filter = "(?s).*";
    } else {
      switch (type) {
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
    }

    Map<String, String> fullList = crmService.getFieldOptions("contact");

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

  @Path("/contact-list-members")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getContactListMembers(
          @QueryParam("listId") String listId,
          @QueryParam("crmType") String crmType,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);
    CrmService crmService = getCrmService(env, crmType);
    List<CrmContact> contacts = crmService.getContactsFromList(listId);

    return Response.status(200).entity(contacts).build();
  }

  protected List<Map<String, String>> toListOfMap(InputStream inputStream, FormDataContentDisposition fileDisposition) throws Exception {
    String fileExtension = Utils.getFileExtension(fileDisposition.getFileName());
    List<Map<String, String>> data = new ArrayList<>();

    if ("csv".equals(fileExtension)) {
      CSVParser csvParser = CSVParser.parse(
              inputStream,
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
    } else if ("xlsx".equals(fileExtension)) {
      data = Utils.getExcelData(inputStream, 0);
    } else {
      throw new RuntimeException("Unsupported file extension: " + fileExtension);
    }
    return data;
  }

  protected List<CrmImportEvent> toCrmImportEvents(List<Map<String, String>> data) {
    return toCrmImportEvents(data, CrmImportEvent::fromGeneric);
  }

  protected List<CrmImportEvent> toCrmImportEvents(List<Map<String, String>> data, Function<Map<String, String>, CrmImportEvent> mappingFunction) {
    return data.stream().map(mappingFunction::apply).filter(Objects::nonNull).collect(Collectors.toList());
  }

  // TODO: move to a service layer instead?
  protected CrmImportEvent fromFBFundraiser(Map<String, String> data) {
//  TODO: 'S' means a standard charge, but will likely need to eventually support other types like refunds, etc.
    if (data.get("Charge Action Type").equalsIgnoreCase("S")) {
      CrmImportEvent importEvent = new CrmImportEvent();

//    TODO: support for initial amount, any fees, and net amount
//    importEvent. = data.get("Donation Amount");
//    importEvent. = data.get("FB Fee");
//    importEvent. = getAmount(data, "Net Payout Amount");
      importEvent.opportunityAmount = CrmImportEvent.getAmount(data, "Donation Amount");

//      TODO: support for different currencies will likely be needed in the future
//      importEvent. = data.get("Payout Currency");
//      importEvent. = data.get("Sender Currency");
      if (data.get("Fundraiser Type").contains("Fundraiser")) {
        importEvent.opportunityName = "Facebook Fundraiser: " + data.get("Fundraiser Title");
      } else if (!Strings.isNullOrEmpty(data.get("Fundraiser Title"))) {
        importEvent.opportunityName = "Facebook Fundraiser: " + data.get("Fundraiser Title") + " (" + data.get("Fundraiser Type") + ")";
      } else {
        importEvent.opportunityName = "Facebook Fundraiser: " + data.get("Fundraiser Type");
      }
      try {
        importEvent.opportunityDate = Calendar.getInstance();
        importEvent.opportunityDate.setTime(new SimpleDateFormat("yyyy-MM-dd").parse(data.get("Charge Date")));
      } catch (ParseException e) {
        throw new RuntimeException("failed to parse date", e);
      }

      importEvent.contactFirstName = Utils.nameToTitleCase(data.get("First Name"));
      importEvent.contactLastName = Utils.nameToTitleCase(data.get("Last Name"));
      importEvent.contactPersonalEmail = data.get("Email Address");
      importEvent.opportunitySource = (!Strings.isNullOrEmpty(data.get("Fundraiser Title"))) ? data.get("Fundraiser Title") : data.get("Fundraiser Type");
      importEvent.opportunityTerminal = data.get("Payment Processor");
      importEvent.opportunityStageName = "Posted";

      if (data.containsKey("CRM Campaign ID")) {
        importEvent.opportunityCampaignId = data.get("CRM Campaign ID");
      }

      List<String> description = new ArrayList<>();
      description.add("Fundraiser Title: " + data.get("Fundraiser Title"));
      description.add("Fundraiser Type: " + data.get("Fundraiser Type"));
      description.add("Campaign Owner Name: " + data.get("Campaign Owner Name"));
      // Depending on the context, Campaign ID might be the CRM, but it might be vendor-specific (ie, Facebook)
      description.add("Campaign ID: " + data.get("Campaign ID"));
      description.add("CRM Campaign ID: " + data.get("CRM Campaign ID"));
      description.add("Permalink: " + data.get("Permalink"));
      description.add("Payment ID: " + data.get("Payment ID"));
      description.add("Source Name: " + data.get("Source Name"));
      importEvent.opportunityDescription = Joiner.on("\n").join(description);

      return importEvent;
    } else {
      return null;
    }
  }

  protected CrmImportEvent fromGreaterGiving(Map<String, String> data) {
    // TODO: Not mapped:
    // Household Phone
    // Account1 Phone
    // Account1 Street
    // Account1 City
    // Account1 State/Province
    // Account1 Zip/Postal Code
    // Account1 Country
    // Account1 Website
    // Payment Check/Reference Number
    // Payment Method
    // Contact1 Salutation
    // Contact1 Title
    // Contact1 Birthdate
    // Contact1 Work Email
    // Contact1 Alternate Email
    // Contact1 Preferred Email
    // Contact1 Other Phone
    // Contact2 Salutation
    // Contact2 First Name
    // Contact2 Last Name
    // Contact2 Birthdate
    // Contact2 Title
    // Contact2 Personal Email
    // Contact2 Work Email
    // Contact2 Alternate Email
    // Contact2 Preferred Email
    // Contact2 Home Phone
    // Contact2 Work Phone
    // Contact2 Mobile Phone
    // Contact2 Other Phone
    // Contact2 Preferred Phone
    // Donation Donor (IE, Contact1 or Contact2)
    // Donation Member Level
    // Donation Membership Start Date
    // Donation Membership End Date
    // Donation Membership Origin
    // Donation Record Type Name
    // Campaign Member Status

    // TODO: Other types? Skipping gift-in-kind
    if (data.get("Donation Type").equalsIgnoreCase("Donation") || data.get("Donation Type").equalsIgnoreCase("Auction")) {
      CrmImportEvent importEvent = new CrmImportEvent();

      importEvent.account.name = data.get("Account1 Name");
      importEvent.account.billingAddress.street = data.get("Home Street");
      importEvent.account.billingAddress.city = data.get("Home City");
      importEvent.account.billingAddress.state = data.get("Home State/Province");
      importEvent.account.billingAddress.postalCode = data.get("Home Zip/Postal Code");
      importEvent.account.billingAddress.country = data.get("Home Country");

      importEvent.contactFirstName = data.get("Contact1 First Name");
      importEvent.contactLastName = data.get("Contact1 Last Name");
      importEvent.contactPersonalEmail = data.get("Contact1 Personal Email");
      importEvent.contactWorkEmail = data.get("Contact1 Work Email");
      importEvent.contactOtherEmail = data.get("Contact1 Other Email");
      importEvent.contactMobilePhone = data.get("Contact1 Mobile Phone");
      importEvent.contactHomePhone = data.get("Contact1 Home Phone");
      importEvent.contactWorkPhone = data.get("Contact1 Work Phone");
      importEvent.contactOtherPhone = data.get("Contact1 Other Phone");
      importEvent.contactPhonePreference = CrmImportEvent.ContactPhonePreference.fromName(data.get("Contact Preferred Phone"));

      importEvent.opportunityAmount = CrmImportEvent.getAmount(data, "Donation Amount");
      if (!Strings.isNullOrEmpty(data.get("Donation Name"))) {
        importEvent.opportunityName = data.get("Donation Name");
      } else {
        importEvent.opportunityName = "Greater Giving: " + data.get("Donation Type");
      }
      try {
        importEvent.opportunityDate = Calendar.getInstance();
        importEvent.opportunityDate.setTime(new SimpleDateFormat("yyyy-MM-dd").parse(data.get("Donation Date")));
      } catch (ParseException e) {
        throw new RuntimeException("failed to parse date", e);
      }
      importEvent.opportunitySource = data.get("Donation Type");
      importEvent.opportunityStageName = data.get("Donation Stage");
      importEvent.opportunityDescription = data.get("Donation Description");
      importEvent.opportunityCampaignName = data.get("Campaign Name");

      return importEvent;
    } else {
      return null;
    }
  }

  protected CrmImportEvent fromClassy(Map<String, String> data) {
    CrmImportEvent importEvent = new CrmImportEvent();

    // Contact
    importEvent.contactPersonalEmail = data.get("Donor Email");
    importEvent.contactFirstName = data.get("Supporter First Name");
    importEvent.contactLastName = data.get("Supporter Last Name");
    importEvent.contactMobilePhone = data.get("Donor Phone Number");
    importEvent.contactOptInEmail = "true".equalsIgnoreCase(data.get("Email Opt In"));

    // Address
    importEvent.account.billingAddress.street = data.get("Billing Address");
    if (!Strings.isNullOrEmpty(data.get("Billing Address 2"))) {
      importEvent.account.billingAddress.street += ", " + data.get("Billing Address 2");
    }
    importEvent.account.billingAddress.city = data.get("Billing City");
    importEvent.account.billingAddress.state = data.get("Billing State");
    importEvent.account.billingAddress.postalCode = data.get("Billing Postal Code");
    importEvent.account.billingAddress.country = data.get("Billing Country");

    // TODO: Should "Campaign ID" go to an extref?
//    importEvent.opportunityCampaignName = data.get("Campaign Name");

    // Opportunity
    importEvent.opportunityAmount = BigDecimal.valueOf(Double.valueOf(data.get("Charged Intended Donation Amount")));
    importEvent.opportunityName = "Classy: " + data.get("Campaign Name");
    importEvent.opportunityTransactionId = data.get("Transaction ID");
    try {
      importEvent.opportunityDate = Calendar.getInstance();
      importEvent.opportunityDate.setTime(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(data.get("Transaction Date")));
    } catch (ParseException e) {
      throw new RuntimeException("failed to parse date", e);
    }
    importEvent.opportunitySource = "Classy";

    return importEvent;
  }

  private CrmService getCrmService(Environment env, String crmType) {
    // default crm service
    CrmService crmService = env.primaryCrmService();
    if ("donations".equalsIgnoreCase(crmType)) {
      crmService = env.donationsCrmService();
    } else if ("messaging".equalsIgnoreCase(crmType)) {
      crmService = env.messagingCrmService();
    }
    return crmService;
  }
}