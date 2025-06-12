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
import com.impactupgrade.nucleus.util.Utils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Set;

public class BrevoClient {

  public static final String FIRSTNAME = "FIRSTNAME";
  public static final String LASTNAME = "LASTNAME";
  public static final String TAGS = "TAGS";
  public static final String SMS = "SMS";

  protected final ApiClient apiClient;
  protected final Environment env;

  private static final Integer CONTACTS_API_LIMIT = 1000;
  private static final Integer CONTACTS_LIST_API_LIMIT = 500;
  private static final String DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
  private static final ObjectMapper objectMapper = new ObjectMapper();

  public BrevoClient(EnvironmentConfig.CommunicationPlatform brevoConfig, Environment env) {
    this.env = env;
    this.apiClient = Configuration.getDefaultApiClient();
    ApiKeyAuth apiKey = (ApiKeyAuth) apiClient.getAuthentication("api-key");
    apiKey.setApiKey(brevoConfig.secretKey);
  }

  public void createContact(String listId, CreateContact createContact) throws ApiException {
    Long id = Utils.parseLong(listId);
    createContact.setListIds(List.of(id));
    ContactsApi api = new ContactsApi();
    api.createContact(createContact);
  }

  public List<GetContactDetails> getContacts(Calendar modifiedSince, Calendar createdSince, List<Long> listIds) throws ApiException {
    Long offset = 0L;
    ContactsApi contactsApi = new ContactsApi();
    GetContacts contacts = contactsApi.getContacts(CONTACTS_API_LIMIT.longValue(), offset, Utils.toDateString(modifiedSince, DATE_TIME_FORMAT), Utils.toDateString(createdSince, DATE_TIME_FORMAT), null, null, listIds);
    List<GetContactDetails> allContacts = new ArrayList<>(toGetContactDetails(contacts));

    while (contacts.getCount() > allContacts.size()) {
      offset = (long) allContacts.size();
      env.logJobInfo("retrieving contacts (offset {} of total {})", offset, contacts.getCount());
      contacts = contactsApi.getContacts(CONTACTS_API_LIMIT.longValue(), offset, Utils.toDateString(modifiedSince, DATE_TIME_FORMAT), null, null, null, null);
      allContacts.addAll(toGetContactDetails(contacts));
    }
    return allContacts;
  }

  public List<GetContactDetails> getContactsFromList(String listId) throws ApiException {
    return getContactsFromList(listId, null);
  }

  public List<GetContactDetails> getContactsFromList(String listId, Calendar modifiedSince) throws ApiException {
    Long id = Utils.parseLong(listId);
    Long offset = 0L;
    ContactsApi contactsApi = new ContactsApi();
    GetContacts contactsFromList = contactsApi.getContactsFromList(id, Utils.toDateString(modifiedSince, DATE_TIME_FORMAT), CONTACTS_LIST_API_LIMIT.longValue(), offset, null);
    List<GetContactDetails> contacts = new ArrayList<>(toGetContactDetails(contactsFromList));

    while (contactsFromList.getCount() > contacts.size()) {
      offset = (long) contacts.size();
      env.logJobInfo("retrieving list {} contacts (offset {} of total {})", listId, offset, contactsFromList.getCount());
      contactsFromList = contactsApi.getContactsFromList(id, null, CONTACTS_LIST_API_LIMIT.longValue(), offset, null);
      contacts.addAll(toGetContactDetails(contactsFromList));
    }
    return contacts;
  }

  public String importContacts(String listId, List<CreateContact> createContacts) throws ApiException {
    Long id = Utils.parseLong(listId);
    List<RequestContactImportJsonBody> jsonBody = createContacts.stream().map(this::toJsonBody).toList();

    RequestContactImport requestContactImport = new RequestContactImport();
    requestContactImport.setListIds(List.of(id));
    requestContactImport.setJsonBody(jsonBody);

    requestContactImport.setUpdateExistingContacts(true);

    ContactsApi contactsApi = new ContactsApi();
    CreatedProcessId createdProcessId = contactsApi.importContacts(requestContactImport);
    return createdProcessId.getProcessId().toString();
  }

  public String removeContactsFromList(String listId, Set<String> contactEmails) throws ApiException {
    if (contactEmails.isEmpty()) {
      return null;
    }
    Long id = Utils.parseLong(listId);
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

  private RequestContactImportJsonBody toJsonBody(CreateContact createContact) {
    RequestContactImportJsonBody jsonBody = new RequestContactImportJsonBody();
    jsonBody.setEmail(createContact.getEmail());
    jsonBody.setAttributes(createContact.getAttributes());
    return jsonBody;
  }
}
