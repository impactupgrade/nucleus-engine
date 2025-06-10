package com.impactupgrade.nucleus.client;

import brevo.ApiClient;
import brevo.ApiException;
import brevo.Configuration;
import brevo.auth.ApiKeyAuth;
import brevoApi.ContactsApi;
import brevoModel.CreateAttribute;
import brevoModel.CreateContact;
import brevoModel.CreatedProcessId;
import brevoModel.GetAttributes;
import brevoModel.GetAttributesAttributes;
import brevoModel.GetContactDetails;
import brevoModel.GetContacts;
import brevoModel.PostContactInfo;
import brevoModel.RemoveContactFromList;
import brevoModel.RequestContactImport;
import brevoModel.RequestContactImportJsonBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;

public class BrevoClient {

  private static final Logger log = LoggerFactory.getLogger(BrevoClient.class);

  public static final String FIRSTNAME = "FIRSTNAME";
  public static final String LASTNAME = "LASTNAME";
  public static final String TAGS = "TAGS";
  public static final String SMS = "SMS";

  protected final ApiClient apiClient;
  protected final Environment env;

  private static final Integer CONTACTS_API_LIMIT = 100;
  private static final String DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
  private static final ObjectMapper objectMapper = new ObjectMapper();

  public BrevoClient(EnvironmentConfig.CommunicationPlatform brevoConfig, Environment env) {
    this.env = env;
    this.apiClient = Configuration.getDefaultApiClient();
    ApiKeyAuth apiKey = (ApiKeyAuth) apiClient.getAuthentication("api-key");
    apiKey.setApiKey(brevoConfig.secretKey);
    // Uncomment the following line to set a prefix for the API key, e.g. "Token" (defaults to null)
    //apiKey.setApiKeyPrefix("Token");

    // Configure API key authorization: partner-key
    //ApiKeyAuth partnerKey = (ApiKeyAuth) apiClient.getAuthentication("partner-key");
    //partnerKey.setApiKey("YOUR PARTNER KEY");
    // Uncomment the following line to set a prefix for the API key, e.g. "Token" (defaults to null)
    //partnerKey.setApiKeyPrefix("Token");
  }

  public void createContact(String listId, GetContactDetails getContactDetails) throws ApiException {
    Long id = parseLong(listId);
    CreateContact createContact = new CreateContact();
    createContact.setListIds(List.of(id));
    createContact.setEmail(getContactDetails.getEmail());
    createContact.setAttributes(getContactDetails.getAttributes());
    createContact.setEmailBlacklisted(false);
    createContact.setSmsBlacklisted(false);
    createContact.setUpdateEnabled(true); // to allow upsert
    ContactsApi api = new ContactsApi();
    api.createContact(createContact);
  }

  public List<GetContactDetails> getContacts(Calendar modifiedSince, Calendar createdSince) throws ApiException {
    Long offset = 0L;
    ContactsApi contactsApi = new ContactsApi();
    GetContacts contacts = contactsApi.getContacts(CONTACTS_API_LIMIT.longValue(), offset, toDateString(modifiedSince), toDateString(createdSince), null, null, null);
    List<GetContactDetails> allContacts = new ArrayList<>(toGetContactDetails(contacts));

    while (contacts.getCount() > allContacts.size()) {
      offset = (long) allContacts.size();
      env.logJobInfo("retrieving contacts (offset {} of total {})", offset, contacts.getCount());
      contacts = contactsApi.getContacts(CONTACTS_API_LIMIT.longValue(), offset, toDateString(modifiedSince), toDateString(createdSince), null, null, null);
      allContacts.addAll(toGetContactDetails(contacts));
    }
    return allContacts;
  }

  public List<GetContactDetails> getContactsFromList(String listId) throws ApiException {
    return getContactsFromList(listId, null);
  }

  public List<GetContactDetails> getContactsFromList(String listId, Calendar modifiedSince) throws ApiException {
    Long id = parseLong(listId);
    Long offset = 0L;
    ContactsApi contactsApi = new ContactsApi();
    GetContacts contactsFromList = contactsApi.getContactsFromList(id, toDateString(modifiedSince), CONTACTS_API_LIMIT.longValue(), offset, null);
    List<GetContactDetails> contacts = new ArrayList<>(toGetContactDetails(contactsFromList));

    while (contactsFromList.getCount() > contacts.size()) {
      offset = (long) contacts.size();
      env.logJobInfo("retrieving list {} contacts (offset {} of total {})", listId, offset, contactsFromList.getCount());
      contactsFromList = contactsApi.getContactsFromList(id, null, CONTACTS_API_LIMIT.longValue(), offset, null);
      contacts.addAll(toGetContactDetails(contactsFromList));
    }
    return contacts;
  }

  public String importContacts(String listId, List<GetContactDetails> contactDetails) throws ApiException {
    Long id = parseLong(listId);
    List<RequestContactImportJsonBody> jsonBody = contactDetails.stream().map(this::toJsonBody).toList();

    RequestContactImport requestContactImport = new RequestContactImport();
    requestContactImport.setListIds(List.of(id));
    requestContactImport.setJsonBody(jsonBody);

    requestContactImport.setEmailBlacklist(false);
    requestContactImport.setSmsBlacklist(false);
    requestContactImport.setUpdateExistingContacts(true);
    requestContactImport.setEmptyContactsAttributes(true);

    ContactsApi contactsApi = new ContactsApi();
    CreatedProcessId createdProcessId = contactsApi.importContacts(requestContactImport);
    return createdProcessId.getProcessId().toString();
  }

  public String deleteContacts(String listId, Set<String> contactEmails) throws ApiException {
    if (contactEmails.isEmpty()) {
      return null;
    }
    Long id = parseLong(listId);
    RemoveContactFromList removeContactFromList = new RemoveContactFromList();
    removeContactFromList.setEmails(contactEmails.stream().toList());
    ContactsApi contactsApi = new ContactsApi();
    PostContactInfo postContactInfo = contactsApi.removeContactFromList(id, removeContactFromList);
    return postContactInfo.getContacts().getProcessId().toString();
  }

  public List<GetAttributesAttributes> getAttributes() throws ApiException {
    ContactsApi contactsApi = new ContactsApi();
    GetAttributes response = contactsApi.getAttributes();
    return response.getAttributes();
  }

  public void createAttribute(String name, CreateAttribute.TypeEnum type) throws ApiException {
    ContactsApi contactsApi = new ContactsApi();
    CreateAttribute createAttribute = new CreateAttribute();
    createAttribute.setType(type);
    //TODO: specific attribute category?
    contactsApi.createAttribute("normal", name, createAttribute);
  }

  // Utils
  private List<GetContactDetails> toGetContactDetails(GetContacts getContacts) {
    return getContacts.getContacts().stream()
        .map(o -> objectMapper.convertValue(o, GetContactDetails.class))
        .toList();
  }

  private RequestContactImportJsonBody toJsonBody(GetContactDetails contact) {
    RequestContactImportJsonBody jsonBody = new RequestContactImportJsonBody();
    jsonBody.setEmail(contact.getEmail());
    jsonBody.setAttributes(contact.getAttributes());
    return jsonBody;
  }

  private Long parseLong(String s) {
    Long l = null;
    try {
      l = Long.parseLong(s);
    } catch (NumberFormatException e) {
      log.error("Failed to parse long from string '" + s + "'!");
    }
    return l;
  }

  private String toDateString(Calendar calendar) {
    if (calendar == null) {
      return null;
    }
    Date date = calendar.getTime();
    ZonedDateTime zdt = date.toInstant().atZone(ZoneId.of("UTC"));
    return zdt.format(DateTimeFormatter.ofPattern(DATE_TIME_FORMAT));
  }
}
