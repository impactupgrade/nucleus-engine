/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.HttpClient;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.util.HttpClient.get;
import static com.impactupgrade.nucleus.util.HttpClient.isOk;
import static com.impactupgrade.nucleus.util.HttpClient.patch;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

public class MinistryByTextClient extends OAuthClient {

  protected static String AUTH_URL_PRODUCTION = "https://login.ministrybytext.com/connect/token";
  protected static String AUTH_URL_SANDBOX = "https://login-qa.poweredbytext.com/connect/token";
  protected static String API_BASE_URL_PRODUCTION = "https://api.ministrybytext.com";
  protected static String API_BASE_URL_SANDBOX = "https://api-qa.poweredbytext.com";

  protected final EnvironmentConfig.MBT mbtConfig;

  protected static final String AUTH_URL;
  protected static final String API_BASE_URL;
  static {
    String profile = System.getenv("PROFILE");
    if ("production".equalsIgnoreCase(profile)) {
      AUTH_URL = AUTH_URL_PRODUCTION;
      API_BASE_URL = API_BASE_URL_PRODUCTION;
    } else {
      AUTH_URL = AUTH_URL_SANDBOX;
      API_BASE_URL = API_BASE_URL_SANDBOX;
    }
  }

  public MinistryByTextClient(EnvironmentConfig.MBT mbtConfig, Environment env) {
    super("ministrybytext", env);
    this.mbtConfig = mbtConfig;
  }

  @Override
  protected JSONObject getClientConfigJson(JSONObject envJson) {
    JSONArray mbtArray = envJson.getJSONArray(name);
    for (int i = 0; i < mbtArray.length(); i++) {
      JSONObject mbtCampus = mbtArray.getJSONObject(i);
      if (mbtConfig.campusId.equalsIgnoreCase(mbtCampus.getString("campusId"))) {
        return mbtCampus;
      }
    }
    return null;
  }

  @Override
  protected OAuthContext oAuthContext() {
    return new ClientCredentialsOAuthContext(mbtConfig, AUTH_URL, false);
  }

  public List<Group> getGroups(EnvironmentConfig.MBT mbtConfig) {
    return get(API_BASE_URL + "campuses/" + mbtConfig.campusId + "/groups", headers(), new GenericType<>() {});
  }

  public Subscriber upsertSubscriber(CrmContact crmContact, EnvironmentConfig.MBT mbtConfig, EnvironmentConfig.CommunicationList communicationList) {
    Subscriber subscriber = toMBTSubscriber(crmContact);
    return post(API_BASE_URL + "campuses/" + mbtConfig.campusId + "/groups/" + communicationList.id + "/subscribers", subscriber, APPLICATION_JSON, headers(), Subscriber.class);
  }

  public BulkOperationResponse upsertSubscribersBulk(String orgunitId, String groupId, List<CrmContact> crmContacts) {
    List<Subscriber> subscribers = crmContacts.stream()
            .map(this::toMBTSubscriber)
            .collect(Collectors.toList());
    return post(API_BASE_URL + "/orgunit/" + orgunitId + "/groups/" + groupId + "/new-subscribers/bulk",
            subscribers, APPLICATION_JSON, headers(), BulkOperationResponse.class);
  }

  public void upsertNotificationSetting(NotificationSetting notificationSetting,
      EnvironmentConfig.MBT mbtConfig, EnvironmentConfig.CommunicationList communicationList) throws IOException, InterruptedException {
    patch(API_BASE_URL + "campuses/" + mbtConfig.campusId + "/groups/" + communicationList.id + "/notification-url", notificationSetting, APPLICATION_JSON, headers());
  }

  public NotificationSettingResponse getNotificationSetting(EnvironmentConfig.MBT mbtConfig, EnvironmentConfig.CommunicationList communicationList) throws IOException, InterruptedException {
    return get(API_BASE_URL + "campuses/" + mbtConfig.campusId + "/groups/" + communicationList.id + "/notification-url", headers(), new GenericType<>() {});
  }

  public BulkOperationResponse upsertContactsBulk(String orgunitId, List<CrmContact> crmContacts) {
    List<Contact> contacts = crmContacts.stream()
            .map(this::toMBTContact)
            .collect(Collectors.toList());
    return post(API_BASE_URL + "/orgunit/" + orgunitId + "/contacts/bulk", contacts, APPLICATION_JSON, headers(), BulkOperationResponse.class);
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
        env.logJobWarn("POST failed: url={} code={} message={}", url, response.getStatus(), message);
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

  protected Contact toMBTContact(CrmContact crmContact) {
    Contact contact = new Contact();
    contact.firstName = crmContact.firstName;
    contact.lastName = crmContact.lastName;
    //contact.gender = crmContact ?
    contact.mobileNo = crmContact.mobilePhone;
    // TODO: address, using mailing or billing
    //contact.region = crmContact ?
    return contact;
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

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class NotificationSetting {
    public Boolean callbackUrlEnabled;
    public Boolean statusUrlEnabled;
    public List<CallbackSetting> callbackUrlSettings = new ArrayList<>();
    public List<StatusSetting> statusUrlSettings = new ArrayList<>();

    @Override
    public String toString() {
      return "NotificationSetting{" +
          "callbackUrlEnabled=" + callbackUrlEnabled +
          ", statusUrlEnabled=" + statusUrlEnabled +
          ", callbackUrlSettings=" + callbackUrlSettings +
          ", statusUrlSettings=" + statusUrlSettings +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class CallbackSetting {
    public String callBackUrl;
    public String authenticationType;

    @Override
    public String toString() {
      return "CallbackSetting{" +
          "callBackUrl='" + callBackUrl + '\'' +
          ", authenticationType='" + authenticationType + '\'' +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class StatusSetting {
    public String statusUrl;
    public String authenticationType;

    @Override
    public String toString() {
      return "StatusSetting{" +
          "statusUrl='" + statusUrl + '\'' +
          ", authenticationType='" + authenticationType + '\'' +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class NotificationSettingResponse {
    public NotificationSetting data = new NotificationSetting();

    @Override
    public String toString() {
      return "NotificationSettingResponse{" +
          "data=" + data +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Contact {
    public String firstName;
    public String lastName;
    public String gender;
    public String mobileNo;
    public SubscriberAddress address;
    public String region;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class BulkOperationResponse {
    public String message;
    public boolean isError;
    public String appCode;
    public Data data;

    @Override
    public String toString() {
      return "BulkOperationResponse{" +
              "message='" + message + '\'' +
              ", isError=" + isError +
              ", appCode='" + appCode + '\'' +
              ", data=" + data +
              '}';
    }
  }

  public static class Data {
    public String batchId;

    @Override
    public String toString() {
      return "Data{" +
              "batchId='" + batchId + '\'' +
              '}';
    }
  }

  public static void main(String[] args) {
    Environment env = new Environment() {
      @Override
      public EnvironmentConfig getConfig() {

        EnvironmentConfig.MBT mbt = new EnvironmentConfig.MBT();
        mbt.clientId = "GVU05RE7VMACNUU96ME8";
        mbt.clientSecret = "CPMw462MQdLTW6MDDlagUWlOhxXXJgDRc0D8cBHuUqhO=g6ELo";
        mbt.campusId = "cf774a3b-4910-4b16-b6b0-608f80d216a4";

        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.ministrybytext = List.of(mbt);
        return envConfig;
      }
    };
    EnvironmentConfig.MBT mbt = env.getConfig().ministrybytext.get(0);
    MinistryByTextClient mbtClient = new MinistryByTextClient(mbt, env);

    CrmContact crmContact = new CrmContact();
    crmContact.id = "12345";
    crmContact.firstName = "Brett";
    crmContact.lastName = "Meyer";
    crmContact.mobilePhone = "260-349-5732";
    EnvironmentConfig.CommunicationList communicationList = new EnvironmentConfig.CommunicationList();
    communicationList.id = "c64ecadf-bbfa-4cd4-8f19-a64e5d661b2b";
    String orgunitId = "cf774a3b-4910-4b16-b6b0-608f80d216a4";
    BulkOperationResponse bulkOperationResponse = mbtClient.upsertSubscribersBulk(orgunitId, communicationList.id, List.of(crmContact));
    System.out.println(bulkOperationResponse);

    bulkOperationResponse = mbtClient.upsertContactsBulk(orgunitId, List.of(crmContact));
    System.out.println(bulkOperationResponse);

  }
}
