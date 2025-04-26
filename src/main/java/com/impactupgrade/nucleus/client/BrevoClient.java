package com.impactupgrade.nucleus.client;

import brevo.ApiClient;
import brevo.Configuration;
import brevo.auth.ApiKeyAuth;
import brevoApi.ContactsApi;
import brevoModel.GetContactDetails;
import brevoModel.GetContacts;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class BrevoClient {

  private static final Logger log = LoggerFactory.getLogger(BrevoClient.class);

  protected final ApiClient apiClient;
  protected final Environment env;

  private static final Integer CONTACTS_API_LIMIT = 100;

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

  public void importContacts(List<GetContactDetails> contactDetails) throws Exception {
    ContactsApi contactsApi = new ContactsApi(apiClient);
    //TODO:
    contactsApi.importContacts(null);
  }

  public List<GetContactDetails> getContactsFromList(String listId) throws Exception {
    Long id;
    try {
      id = Long.parseLong(listId);
    } catch (NumberFormatException e) {
      log.error("Failed to parse list id: '" + listId + "'!");
      return null;
    }

    ContactsApi contactsApi = new ContactsApi();
    Long offset = 0L;
    GetContacts result = contactsApi.getContactsFromList(id, null, CONTACTS_API_LIMIT.longValue(), offset, null);
    List<GetContactDetails> contacts = new ArrayList<>();
    while (result.getCount() > contacts.size()) {
      offset = Long.valueOf(contacts.size());
      env.logJobInfo("retrieving list {} contacts (offset {} of total {})", listId, offset, result.getCount());
      result = contactsApi.getContactsFromList(id, null, CONTACTS_API_LIMIT.longValue(), offset, null);
      result.getContacts().stream().forEach(c -> contacts.add((GetContactDetails) c));
    }
    return contacts;
  }


}
