package com.impactupgrade.nucleus.client;

import brevo.ApiClient;
import brevo.ApiException;
import brevo.Configuration;
import brevo.auth.ApiKeyAuth;
import brevoApi.ContactsApi;
import brevoModel.GetContacts;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;

public class BrevoClient {

  protected final ApiClient apiClient;
  protected final Environment env;

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

  public void getContacts() {
    ContactsApi contactsApi = new ContactsApi();
    try {
      GetContacts result = contactsApi.getContacts(
          //Long limit, Long offset, String modifiedSince, String createdSince, String sort, Long segmentId, List<Long> listIds
          100L, 0L, null, null, null, null, null);
      System.out.println(result);
    } catch (ApiException e) {
      System.err.println(e.getResponseBody());
    }
  }
}
