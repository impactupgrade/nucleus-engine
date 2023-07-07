package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.HttpClient;
import com.impactupgrade.nucleus.util.OAuth2Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.core.GenericType;
import java.util.ArrayList;
import java.util.List;

import static com.impactupgrade.nucleus.util.HttpClient.get;
import static com.impactupgrade.nucleus.util.HttpClient.post;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

// TODO: To eventually become a mbt-java-client open source lib?
public class MinistryByTextClient {

  protected static String AUTH_ENDPOINT = "https://login-qa.ministrybytext.com/connect/token";
  protected static String API_ENDPOINT_BASE = "https://api-qa.ministrybytext.com/";

  protected final Environment env;

  protected static OAuth2Util.Tokens tokens;

  private String clientId;
  private String clientSecret;

  public MinistryByTextClient(Environment env) {
    this.env = env;
    this.clientId = env.getConfig().mbt.clientId;
    this.clientSecret = env.getConfig().mbt.clientSecret;
  }

  public List<Group> getGroups(String campusId) {
    return get(API_ENDPOINT_BASE + "campuses/" + campusId + "/groups", headers(), new GenericType<>() {});
  }

  public Group createGroup(String name, String description) {
    Group group = new Group();
    group.name = name;
    group.description = description;
    return post(API_ENDPOINT_BASE + "campuses/" + env.getConfig().mbt.campusId + "/groups", group, APPLICATION_JSON, headers(), Group.class);
  }

  // TODO: confirm this upserts by phone num -- if it creates dups, we'll need to fetch first
  public Subscriber upsertSubscriber(CrmContact crmContact, String groupId) {
    Subscriber subscriber = toMBTSubscriber(crmContact);
    return post(API_ENDPOINT_BASE + "campuses/" + env.getConfig().mbt.campusId + "/groups/" + groupId + "/subscribers", subscriber, APPLICATION_JSON, headers(), Subscriber.class);
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

  protected HttpClient.HeaderBuilder headers() {
    tokens = OAuth2Util.refreshTokens(tokens, AUTH_ENDPOINT);
    if (tokens == null) {
      tokens = OAuth2Util.getTokensForClientCredentials(clientId, clientSecret, AUTH_ENDPOINT);
    }
    String accessToken = tokens != null ? tokens.accessToken() : null;
    return HttpClient.HeaderBuilder.builder().authBearerToken(accessToken);
  }

  //TODO: remove once done with testing
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
    MinistryByTextClient mbtClient = new MinistryByTextClient(env);

    CrmContact crmContact = new CrmContact();
    crmContact.id = "12345";
    crmContact.firstName = "Brett";
    crmContact.lastName = "Meyer";
    crmContact.mobilePhone = "260-349-5732";
    Subscriber subscriber = mbtClient.upsertSubscriber(crmContact, "c64ecadf-bbfa-4cd4-8f19-a64e5d661b2b");
    System.out.println(subscriber);

    // To check same access token is used
    subscriber = mbtClient.upsertSubscriber(crmContact, "c64ecadf-bbfa-4cd4-8f19-a64e5d661b2b");
  }
}
