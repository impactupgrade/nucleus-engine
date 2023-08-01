package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.HttpClient;

import javax.ws.rs.core.Form;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;

import static com.impactupgrade.nucleus.util.HttpClient.get;
import static com.impactupgrade.nucleus.util.HttpClient.isOk;
import static javax.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

public class MinistryByTextCommunicationService extends AbstractCommunicationService {

  protected static String AUTH_ENDPOINT = "https://login-qa.ministrybytext.com/connect/token";
  protected static String API_ENDPOINT_BASE = "https://api-qa.ministrybytext.com/";

  protected String accessToken;
  protected Calendar accessTokenExpiration;

  @Override
  public String name() {
    return "ministrybytext";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().ministrybytext != null && !env.getConfig().ministrybytext.isEmpty();
  }

  @Override
  public void syncContacts(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.CommunicationPlatform mbtConfig : env.getConfig().ministrybytext) {
      for (EnvironmentConfig.CommunicationList communicationList : mbtConfig.lists) {
        List<CrmContact> crmContacts = env.primaryCrmService().getSmsContacts(lastSync, communicationList);

        for (CrmContact crmContact : crmContacts) {
          if (!Strings.isNullOrEmpty(crmContact.phoneNumberForSMS())) {
            env.logJobInfo("upserting contact {} {} on list {}", crmContact.id, crmContact.phoneNumberForSMS(), communicationList.id);
//            upsertSubscriber(crmContact, communicationList.id);
          }
        }
      }
    }
  }

  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    // TODO
  }

  @Override
  public void upsertContact(String contactId) throws Exception {
    CrmService crmService = env.primaryCrmService();

    for (EnvironmentConfig.CommunicationPlatform mbtConfig : env.getConfig().ministrybytext) {
      for (EnvironmentConfig.CommunicationList communicationList : mbtConfig.lists) {
        Optional<CrmContact> crmContact = crmService.getFilteredContactById(contactId, communicationList.crmFilter);

        if (crmContact.isPresent() && !Strings.isNullOrEmpty(crmContact.get().phoneNumberForSMS())) {
          env.logJobInfo("upserting contact {} {} on list {}", crmContact.get().id, crmContact.get().phoneNumberForSMS(), communicationList.id);
//          upsertSubscriber(crmContact.get(), communicationList.id);
        }
      }
    }
  }

  protected List<Group> getGroups(String campusId) {
    return get(API_ENDPOINT_BASE + "campuses/" + campusId + "/groups", headers(), new GenericType<>() {});
  }

  protected Subscriber upsertSubscriber(CrmContact crmContact, String groupId) {
    Subscriber subscriber = toMBTSubscriber(crmContact);
    return post(API_ENDPOINT_BASE + "campuses/" + env.getConfig().mbt.campusId + "/groups/" + groupId + "/subscribers", subscriber, APPLICATION_JSON, headers(), Subscriber.class);
  }

  protected HttpClient.HeaderBuilder headers() {
    if (isAccessTokenInvalid()) {
      env.logJobInfo("Getting new access token...");
      HttpClient.TokenResponse tokenResponse = getAccessToken();
      accessToken = tokenResponse.accessToken;
      Calendar onehour = Calendar.getInstance();
      onehour.add(Calendar.SECOND, tokenResponse.expiresIn);
      accessTokenExpiration = onehour;
    }
    System.out.println(accessToken);
    return HttpClient.HeaderBuilder.builder().authBearerToken(accessToken);
  }

  protected boolean isAccessTokenInvalid() {
    Calendar now = Calendar.getInstance();
    return Strings.isNullOrEmpty(accessToken) || now.after(accessTokenExpiration);
  }

  protected HttpClient.TokenResponse getAccessToken() {
    // TODO: Map.of should be able to be used instead of Form (see VirtuousClient), but getting errors about no writer
    return post(
        AUTH_ENDPOINT,
        new Form()
            .param("client_id", env.getConfig().mbt.clientId)
            .param("client_secret", env.getConfig().mbt.clientSecret)
            .param("grant_type", "client_credentials"),
        APPLICATION_FORM_URLENCODED,
        HttpClient.HeaderBuilder.builder(),
        HttpClient.TokenResponse.class
    );
  }

  // Having to modify this due to MBT's limited API. There's no upsert concept, and we want to avoid having to retrieve
  // contacts on every sync. So simply swallow those errors.
  protected <S, T> T post(String url, S entity, String mediaType, HttpClient.HeaderBuilder headerBuilder, Class<T> clazz) {
    Response response = HttpClient.post(url, entity, mediaType, headerBuilder);
    if (isOk(response)) {
      if (clazz != null) {
        return response.readEntity(clazz);
      }
    } else {
      String message = response.readEntity(String.class);
      if (!message.contains("Subscriber exist")) {
        env.logJobError("POST failed: url={} code={} message={}", url, response.getStatus(), message);
      }
    }
    return null;
  }

  protected Subscriber toMBTSubscriber(CrmContact crmContact) {
    Subscriber subscriber = new Subscriber();
    subscriber.firstName = crmContact.firstName;
    subscriber.lastName = crmContact.lastName;
    subscriber.mobileNo = crmContact.phoneNumberForSMS();

    // TODO: address, using mailing or billing
    // TODO: relations

    return subscriber;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Group {
    public String id;
    public String name;
    public String description;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Subscriber {
    public String id;
    public String firstName;
    public String lastName;
    public String mobileNo;
    public SubscriberAddress address = new SubscriberAddress();
    public List<SubscriberRelation> relations = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class SubscriberAddress {
    public String address1;
    public String address2;
    public String state;
    public String postalCode;
    public String countryCode;
    public String mobileCountryCode;
    public Integer age;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class SubscriberRelation {
    public String name;
    public String relationship;
  }

  public static void main(String[] args) {
    Environment env = new Environment() {
      @Override
      public EnvironmentConfig getConfig() {
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.mbt.clientId = "GVU05RE7VMACNUU96ME8";
        envConfig.mbt.clientSecret = "CPMw462MQdLTW6MDDlagUWlOhxXXJgDRc0D8cBHuUqhO=g6ELo";
        envConfig.mbt.campusId = "cf774a3b-4910-4b16-b6b0-608f80d216a4";
        return envConfig;
      }
    };
    MinistryByTextCommunicationService mbtClient = new MinistryByTextCommunicationService();
    mbtClient.init(env);

    CrmContact crmContact = new CrmContact();
    crmContact.id = "98765";
    crmContact.firstName = "Brett";
    crmContact.lastName = "Meyer";
    crmContact.mobilePhone = "(260) 267-0709";
    Subscriber subscriber = mbtClient.upsertSubscriber(crmContact, "c64ecadf-bbfa-4cd4-8f19-a64e5d661b2b");
    System.out.println(subscriber);
  }
}
