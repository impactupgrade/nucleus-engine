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

import static com.impactupgrade.nucleus.util.HttpClient.get;
import static com.impactupgrade.nucleus.util.HttpClient.isOk;
import static com.impactupgrade.nucleus.util.HttpClient.patch;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

public class MinistryByTextClient extends OAuthClient {

  protected static String AUTH_ENDPOINT = "https://login.ministrybytext.com/connect/token";
  protected static String API_ENDPOINT_BASE = "https://api.ministrybytext.com/";

  protected final EnvironmentConfig.MBT mbtConfig;

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
    return new ClientCredentialsOAuthContext(mbtConfig, AUTH_ENDPOINT, false);
  }

  public List<Group> getGroups(EnvironmentConfig.MBT mbtConfig) {
    return get(API_ENDPOINT_BASE + "campuses/" + mbtConfig.campusId + "/groups", headers(), new GenericType<>() {});
  }

  public Subscriber upsertSubscriber(CrmContact crmContact, EnvironmentConfig.MBT mbtConfig, EnvironmentConfig.CommunicationList communicationList) {
    Subscriber subscriber = toMBTSubscriber(crmContact);
    return post(API_ENDPOINT_BASE + "campuses/" + mbtConfig.campusId + "/groups/" + communicationList.id + "/subscribers", subscriber, APPLICATION_JSON, headers(), Subscriber.class);
  }

  public void upsertNotificationSetting(NotificationSetting notificationSetting,
      EnvironmentConfig.MBT mbtConfig, EnvironmentConfig.CommunicationList communicationList) throws IOException, InterruptedException {
    patch(API_ENDPOINT_BASE + "campuses/" + mbtConfig.campusId + "/groups/" + communicationList.id + "/notification-url", notificationSetting, APPLICATION_JSON, headers());
  }

  public NotificationSettingResponse getNotificationSetting(EnvironmentConfig.MBT mbtConfig, EnvironmentConfig.CommunicationList communicationList) throws IOException, InterruptedException {
    return get(API_ENDPOINT_BASE + "campuses/" + mbtConfig.campusId + "/groups/" + communicationList.id + "/notification-url", headers(), new GenericType<>() {});
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

    @Override
    public String toString() {
      return "CallbackSetting{" +
          "callBackUrl='" + callBackUrl + '\'' +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class StatusSetting {
    public String statusUrl;

    @Override
    public String toString() {
      return "StatusSetting{" +
          "statusUrl='" + statusUrl + '\'' +
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
}
