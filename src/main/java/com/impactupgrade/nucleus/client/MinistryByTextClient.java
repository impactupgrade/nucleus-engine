/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.collect.Lists;
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
  protected static Integer BULK_API_LIMIT = 100;

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

  public List<Subscriber> upsertSubscribersBulk(List<CrmContact> crmContacts, EnvironmentConfig.MBT mbtConfig, EnvironmentConfig.CommunicationList communicationList) {
    List<Subscriber> subscribers = crmContacts.stream()
            .map(this::toMBTSubscriber)
            .collect(Collectors.toList());
    List<List<Subscriber>> subscribersBatches = Lists.partition(subscribers, BULK_API_LIMIT);
    int i = 0;
    for (List<Subscriber> subscribersBatch: subscribersBatches) {
      env.logJobInfo("Processing subscribers batch {} of total {}...", i++, subscribersBatches.size());
      BulkOperationResponse bulkOperationResponse = post(API_BASE_URL + "/campuses/" + mbtConfig.campusId + "/groups/" + communicationList.id + "/new-subscribers/bulk",
              subscribersBatch, APPLICATION_JSON, headers(), BulkOperationResponse.class);
      if (bulkOperationResponse != null && !bulkOperationResponse.isError) {
        env.logJobInfo("Submitted subscribers batch. Batch id={}", bulkOperationResponse.data.batchId);
      } else {
        env.logJobWarn("Failed to process subscribers batch {} of total {}! Error message={}", i, subscribersBatches.size(), bulkOperationResponse.message);
      }
    }
    return subscribers;
  }

  public void upsertNotificationSetting(NotificationSetting notificationSetting,
      EnvironmentConfig.MBT mbtConfig, EnvironmentConfig.CommunicationList communicationList) throws IOException, InterruptedException {
    patch(API_BASE_URL + "campuses/" + mbtConfig.campusId + "/groups/" + communicationList.id + "/notification-url", notificationSetting, APPLICATION_JSON, headers());
  }

  public NotificationSettingResponse getNotificationSetting(EnvironmentConfig.MBT mbtConfig, EnvironmentConfig.CommunicationList communicationList) throws IOException, InterruptedException {
    return get(API_BASE_URL + "campuses/" + mbtConfig.campusId + "/groups/" + communicationList.id + "/notification-url", headers(), new GenericType<>() {});
  }

  public List<Contact> upsertContactsBulk(List<CrmContact> crmContacts, EnvironmentConfig.MBT mbtConfig) {
    List<Contact> contacts = crmContacts.stream()
            .map(this::toMBTContact)
            .collect(Collectors.toList());
    List<List<Contact>> contactsBatches = Lists.partition(contacts, BULK_API_LIMIT);
    int i = 0;
    for (List<Contact> contactsBatch: contactsBatches) {
      env.logJobInfo("Processing contacts batch {} of total {}...", i++, contactsBatches.size());
      BulkOperationResponse bulkOperationResponse = post(API_BASE_URL + "/campuses/" + mbtConfig.campusId + "/contacts/bulk",
              contactsBatch, APPLICATION_JSON, headers(), BulkOperationResponse.class);
      if (bulkOperationResponse != null && !bulkOperationResponse.isError) {
        env.logJobInfo("Submitted contacts batch. Batch id={}", bulkOperationResponse.data.batchId);
      } else {
        env.logJobWarn("Failed to process contacts batch {} of total {}! Error message={}", i, contactsBatches.size(), bulkOperationResponse.message);
      }
    }
    return contacts;
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
}
