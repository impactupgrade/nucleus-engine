package com.impactupgrade.nucleus.client;

import brevo.ApiClient;
import brevo.ApiException;
import brevo.Configuration;
import brevo.auth.ApiKeyAuth;
import brevoApi.ContactsApi;
import brevoModel.CreateAttribute;
import brevoModel.CreateContact;
import brevoModel.CreateUpdateContactModel;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BrevoClient {

  private static final Logger log = LoggerFactory.getLogger(BrevoClient.class);

  public static final String FIRSTNAME = "FIRSTNAME";
  public static final String LASTNAME = "LASTNAME";
  public static final String SMS = "SMS";

  protected final ApiClient apiClient;
  protected final Environment env;

  private static final Integer CONTACTS_API_LIMIT = 100;
  private static final ObjectMapper objectMapper = new ObjectMapper();

  public BrevoClient(EnvironmentConfig.CommunicationPlatform brevoConfig, Environment env) {
    this.env = env;
    this.apiClient = Configuration.getDefaultApiClient();
    ApiKeyAuth apiKey = (ApiKeyAuth) apiClient.getAuthentication("api-key");
    apiKey.setApiKey(brevoConfig.secretKey);
    // Uncomment the following line to set a prefix for the API key, e.g. "Token" (defaults to null)
    //apiKey.setApiKeyPrefix("Token");

    // ?
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
    CreateUpdateContactModel response = api.createContact(createContact);
  }

  public List<GetContactDetails> getContactsFromList(String listId) throws ApiException {
    Long id = parseLong(listId);
    Long offset = 0L;
    ContactsApi contactsApi = new ContactsApi();
    GetContacts contactsFromList = contactsApi.getContactsFromList(id, null, CONTACTS_API_LIMIT.longValue(), offset, null);
    List<GetContactDetails> contacts = new ArrayList<>(toGetContactDetails(contactsFromList));

    while (contactsFromList.getCount() > contacts.size()) {
      offset = Long.valueOf(contacts.size());
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
    //    CreateAttributeEnumeration Beginner = new CreateAttributeEnumeration();
//    Beginner.setLabel("Beginner");
//    Beginner.setValue(1);
//    CreateAttributeEnumeration Intermediate = new CreateAttributeEnumeration();
//    Intermediate.setLabel("Intermediate");
//    Intermediate.setValue(2);
//    CreateAttributeEnumeration Expert = new CreateAttributeEnumeration();
//    Expert.setLabel("Expert");
//    Expert.setValue(3);
//    List<CreateAttributeEnumeration> enumerations = new ArrayList<CreateAttributeEnumeration>();
//    enumerations.add(Beginner);
//    enumerations.add(Intermediate);
//    enumerations.add(Expert);
//    createAttribute.setEnumeration(enumerations);

    ContactsApi contactsApi = new ContactsApi();
    String attributeName = name;
    CreateAttribute createAttribute = new CreateAttribute();
    createAttribute.setType(type);
    //TODO: specific attribute category?
    contactsApi.createAttribute("normal", attributeName, createAttribute);
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
}
