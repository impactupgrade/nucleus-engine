/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.SfdcMetadataClient;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ContactFormData;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmCustomField;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmUser;
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

  protected final EnvironmentFactory envFactory;
  private SfdcMetadataClient sfdcMetadataClient;

  public CrmController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @Path("/upsert-contact")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response upsertContact(
      @FormParam("user_email") String userEmail,
      @FormParam("contact_id") String contactId,
      @FormParam("first_name") String firstName,
      @FormParam("last_name") String lastName,
      @FormParam("email_lists") List<String> emailLists,
      @FormParam("email_address") String emailAddress,
      @FormParam("pref_phone") String prefPhone,
      @FormParam("h_phone") String hPhone,
      @FormParam("w_phone") String wPhone,
      @FormParam("m_phone") String mPhone,
      @FormParam("pri_street") String priStreet,
      @FormParam("pri_city") String priCity,
      @FormParam("pri_state") String priState,
      @FormParam("pri_zip") String priZip,
      @FormParam("pri_country") String priCountry,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    CrmService crmService = env.primaryCrmService();
    CrmContact contact = new CrmContact();
    CrmAccount account = new CrmAccount();
    Optional<CrmUser> user = Optional.empty();

    if (!Strings.isNullOrEmpty(contactId)) {
      //Existing contact is being updated
      if (crmService.getContactById(contactId).isPresent()) {
        contact = crmService.getContactById(contactId).get();
        //Check for an existing account
        if (crmService.getAccountById(contact.account.id).isPresent()) {
          account = crmService.getAccountById(contact.account.id).get();
        }
      } else {
        env.logJobError("No contact with the ID: {} found", contactId);
        return Response.serverError().build();
      }
    }

    try {
      //TODO: Had pulled the account building process from the old code, keeping it as is for now
      //only create the account if it doesn't exist
      if (!Strings.isNullOrEmpty(account.id)) {
        account.name = firstName + " " + lastName;

        // Assign ownership based on checkmark value
        env.logJobInfo("User Email: {}", userEmail);

        // Check for a user to assign as an account and contact owner
        if (!Strings.isNullOrEmpty(userEmail)) {
          user = crmService.getUserByEmail(userEmail);
          if (user.isEmpty()) {
            env.logJobInfo("Staff user not found by email {}", userEmail);
          } else {
            account.ownerId = user.get().id();
          }
        }

        crmService.insertAccount(account);
      }

      // Create Contact
      contact.firstName = firstName;
      contact.lastName = lastName;
      contact.email = emailAddress;
      contact.emailGroups = emailLists;
      contact.preferredPhone = CrmContact.PreferredPhone.valueOf(prefPhone);
      contact.homePhone = hPhone;
      contact.mobilePhone = mPhone;
      contact.workPhone = wPhone;

      //Create Address
      CrmAddress contactAddress = new CrmAddress();
      contactAddress.street = priStreet;
      contactAddress.city = priCity;
      contactAddress.state = priState;
      contactAddress.postalCode = priZip;
      contactAddress.country = priCountry;

      contact.mailingAddress = contactAddress;

      contact.account = account;

      if (user.isPresent()) {
        contact.ownerId = user.get().id();
      }

      //update or insert the contact
      if (Strings.isNullOrEmpty(contact.id)) {
        crmService.insertContact(contact);
      } else {
        crmService.updateContact(contact);
      }

      return Response.ok().build();
    } catch (Exception e) {
      env.logJobError("Contact create failed: {}", e.toString());
      return Response.serverError().build();
    }
  }

  @Path("/contact-and-account")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response addContactAndAccount(
      @FormParam("contact_owner_email") String contactOwnerEmail,
      @FormParam("first_name") String firstName,
      @FormParam("last_name") String lastName,
      @FormParam("email_address") String emailAddress,
      @FormParam("pref_phone") String prefPhone,
      @FormParam("h_phone") String hPhone,
      @FormParam("w_phone") String wPhone,
      @FormParam("m_phone") String mPhone,
      @FormParam("contact_line_one") String contactLineOne,
      @FormParam("contact_line_two") String contactLineTwo,
      @FormParam("contact_city") String contactCity,
      @FormParam("contact_state") String contactState,
      @FormParam("contact_zip") String contactZip,
      @FormParam("contact_country") String contactCountry,
      @FormParam("account_owner_email") String accountOwnerEmail,
      @FormParam("account_name") String accountName,
      @FormParam("account_email") String accountEmail,
      @FormParam("account_phone") String accountPhone,
      @FormParam("account_website") String accountWebsite,
      @FormParam("account_type") String accountType,
      @FormParam("account_line_one") String accountLineOne,
      @FormParam("account_line_two") String accountLineTwo,
      @FormParam("account_city") String accountCity,
      @FormParam("account_state") String accountState,
      @FormParam("account_zip") String accountZip,
      @FormParam("account_country") String accountCountry,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    CrmService crmService = env.primaryCrmService();

    try {
      //ACCOUNT
      CrmAccount newAccount = new CrmAccount();

      if (crmService.getUserByEmail(accountOwnerEmail).isPresent()){
        newAccount.ownerId = crmService.getUserByEmail(accountOwnerEmail).get().id();
      }
      newAccount.name = accountName;
      //TODO: do we have an account email concept/way to add that?
      newAccount.phone = accountPhone;
      newAccount.website = accountWebsite;
      newAccount.type = accountType;
      //TODO: use mailing or billing address?
      CrmAddress accountAddress = new CrmAddress();
      accountAddress.street = accountLineOne;
      if (!Strings.isNullOrEmpty(accountLineTwo)){
        accountAddress.street = accountAddress.street + "," + accountLineTwo;
      }
      accountAddress.city = accountCity;
      accountAddress.state = accountState;
      accountAddress.postalCode = accountZip;
      accountAddress.country = accountCountry;
      newAccount.mailingAddress = accountAddress;

      crmService.insertAccount(newAccount);

      //CONTACT
      CrmContact newContact = new CrmContact();

      if (crmService.getUserByEmail(contactOwnerEmail).isPresent()){
        newAccount.ownerId = crmService.getUserByEmail(contactOwnerEmail).get().id();
      }
      newContact.firstName = firstName;
      newContact.lastName = lastName;
      newContact.email = emailAddress;
      newContact.preferredPhone = CrmContact.PreferredPhone.valueOf(prefPhone);
      newContact.homePhone = hPhone;
      newContact.mobilePhone = mPhone;
      newContact.workPhone = wPhone;
      CrmAddress contactAddress = new CrmAddress();
      contactAddress.street = contactLineOne;
      if (!Strings.isNullOrEmpty(contactLineTwo)){
        contactAddress.street = contactAddress.street + "," + contactLineTwo;
      }
      contactAddress.city = contactCity;
      contactAddress.state = contactState;
      contactAddress.postalCode = contactZip;
      contactAddress.country = contactCountry;
      newContact.mailingAddress = contactAddress;
      return Response.ok().build();

    } catch (Exception e) {
      env.logJobError("Error Creating Contact/Account {}", e.getMessage());
      return Response.serverError().build();
    }
  }

  /**
   * Retrieves a contact from the primary CRM using a variety of optional parameters. For use in external integrations,
   * like Twilio Studio's retrieval of the CRM's Contact ID by phone number.
   */
  //TODO: LJI contact search included FirstName LastName & Address will we want that for the task or does our existing endpoint cover what we need?
  @Path("/contact")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getContact(
      @QueryParam("keyword") String keyword,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    CrmService crmService = env.primaryCrmService();

    List<CrmContact> contacts = new ArrayList<>();
    if (!Strings.isNullOrEmpty(keyword)) {
      contacts.addAll(crmService.searchContacts(ContactSearch.byKeywords(keyword)).getResults());
    } else {
      env.logJobWarn("no search params provided");
    }

    if (!contacts.isEmpty()) {
      env.logJobInfo("returning {} Contacts ", contacts.size());
      return Response.status(200).entity(contacts).build();
    } else {
      env.logJobInfo("Contacts not found");
      return Response.status(404).build();
    }
  }

  //TODO implement account search like existing ContactSearch
  @Path("/account")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getAccount(
      @QueryParam("id") String id,
      @QueryParam("customerId") String customerId,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    id = noWhitespace(id);
    customerId = trim(customerId);

    CrmService crmService = env.primaryCrmService();
    //TODO using the search methods we already have, same question for contacts, do we need to implement more?
    Optional<CrmAccount> account = Optional.empty();
    if (!Strings.isNullOrEmpty(id)) {
      env.logJobInfo("searching id={}", id);
      account = crmService.getAccountById(id);
    } else if (!Strings.isNullOrEmpty(customerId)) {
      env.logJobInfo("searching customerId={}", customerId);
      account = crmService.getAccountByCustomerId(customerId);
    } else {
      env.logJobWarn("no search params provided");
    }

    if (account.isPresent()) {
      env.logJobInfo("returning Account {}", account.get().id);
      return Response.status(200).entity(account.get()).build();
    } else {
      env.logJobInfo("Account not found");
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

    String jobName = "Bulk Import: File";
    env.startJobLog(JobType.PORTAL_TASK, nucleusUsername, jobName, "Nucleus Portal");

    try {
      // Important to do this outside of the new thread -- ensures the InputStream is still open.
      List<Map<String, String>> data = toListOfMap(inputStream, fileDisposition);
      List<CrmImportEvent> importEvents = CrmImportEvent.fromGeneric(data);

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
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    // Important to do this outside of the new thread -- ensures the InputStream is still open.
    List<Map<String, String>> data = toListOfMap(inputStream, fileDisposition);
    List<CrmImportEvent> importEvents = CrmImportEvent.fromFBFundraiser(data);

    Runnable thread = () -> {
      try {
        String jobName = "Bulk Import: Facebook";
        env.startJobLog(JobType.PORTAL_TASK, nucleusUsername, jobName, "Nucleus Portal");
        env.primaryCrmService().processBulkImport(importEvents);
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
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    // Important to do this outside of the new thread -- ensures the InputStream is still open.
    // Excel is expected. Greater Giving has an Excel report export purpose built for SFDC.
    // TODO: But, what if a different CRM is targeted?
    List<Map<String, String>> data = toListOfMap(inputStream, fileDisposition);

    List<CrmImportEvent> importEvents = CrmImportEvent.fromGreaterGiving(data);

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

    Runnable thread = () -> {
      try {
        env.primaryCrmService().insertCustomFields(customFields);
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
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    gsheetUrl = noWhitespace(gsheetUrl);

    List<Map<String, String>> data = GoogleSheetsUtil.getSheetData(gsheetUrl);
    List<CrmCustomField> customFields = CrmCustomField.fromGeneric(data);

    Runnable thread = () -> {
      try {
        env.primaryCrmService().insertCustomFields(customFields);
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
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);
    CrmService crmService = env.primaryCrmService();
    List<CrmContact> contacts = crmService.getContactsFromList(listId);

    return Response.status(200).entity(contacts).build();
  }

  private List<Map<String, String>> toListOfMap(InputStream inputStream, FormDataContentDisposition fileDisposition) throws Exception {
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
}