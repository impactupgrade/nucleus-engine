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

  protected static String AUTH_ENDPOINT = "https://login.ministrybytext.com/connect/token";
  protected static String API_ENDPOINT_BASE = "https://api.ministrybytext.com/";

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
    for (EnvironmentConfig.MBT mbtConfig : env.getConfig().ministrybytext) {
      for (EnvironmentConfig.CommunicationList communicationList : mbtConfig.lists) {
        List<CrmContact> crmContacts = env.primaryCrmService().getSmsContacts(lastSync, communicationList);

        for (CrmContact crmContact : crmContacts) {
          if (!Strings.isNullOrEmpty(crmContact.phoneNumberForSMS())) {
            env.logJobInfo("upserting contact {} {} on list {}", crmContact.id, crmContact.phoneNumberForSMS(), communicationList.id);
            upsertSubscriber(crmContact, mbtConfig, communicationList);
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

    for (EnvironmentConfig.MBT mbtConfig : env.getConfig().ministrybytext) {
      for (EnvironmentConfig.CommunicationList communicationList : mbtConfig.lists) {
        Optional<CrmContact> crmContact = crmService.getFilteredContactById(contactId, communicationList.crmFilter);

        if (crmContact.isPresent() && !Strings.isNullOrEmpty(crmContact.get().phoneNumberForSMS())) {
          env.logJobInfo("upserting contact {} {} on list {}", crmContact.get().id, crmContact.get().phoneNumberForSMS(), communicationList.id);
          upsertSubscriber(crmContact.get(), mbtConfig, communicationList);
        }
      }
    }
  }

  protected List<Group> getGroups(EnvironmentConfig.MBT mbtConfig) {
    return get(API_ENDPOINT_BASE + "campuses/" + mbtConfig.campusId + "/groups", headers(mbtConfig), new GenericType<>() {});
  }

  protected Subscriber upsertSubscriber(CrmContact crmContact, EnvironmentConfig.MBT mbtConfig, EnvironmentConfig.CommunicationList communicationList) {
    Subscriber subscriber = toMBTSubscriber(crmContact);
    return post(API_ENDPOINT_BASE + "campuses/" + mbtConfig.campusId + "/groups/" + communicationList.id + "/subscribers", subscriber, APPLICATION_JSON, headers(mbtConfig), Subscriber.class);
  }

  protected HttpClient.HeaderBuilder headers(EnvironmentConfig.MBT mbtConfig) {
    if (isAccessTokenInvalid()) {
      env.logJobInfo("Getting new access token...");
      HttpClient.TokenResponse tokenResponse = getAccessToken(mbtConfig);
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

  protected HttpClient.TokenResponse getAccessToken(EnvironmentConfig.MBT mbtConfig) {
    // TODO: Map.of should be able to be used instead of Form (see VirtuousClient), but getting errors about no writer
    return post(
        AUTH_ENDPOINT,
        new Form()
            .param("client_id", mbtConfig.clientId)
            .param("client_secret", mbtConfig.clientSecret)
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
}
