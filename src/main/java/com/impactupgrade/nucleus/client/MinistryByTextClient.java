package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.core.Form;
import javax.ws.rs.core.GenericType;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import static com.impactupgrade.nucleus.util.HttpClient.TokenResponse;
import static com.impactupgrade.nucleus.util.HttpClient.get;
import static com.impactupgrade.nucleus.util.HttpClient.post;
import static javax.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

// TODO: To eventually become a mbt-java-client open source lib?
public class MinistryByTextClient {

  private static final Logger log = LogManager.getLogger(MinistryByTextClient.class);

  protected static String AUTH_ENDPOINT = "https://login-qa.ministrybytext.com/connect/token";
  protected static String API_ENDPOINT_BASE = "https://api-qa.ministrybytext.com/";

  protected final Environment env;

  protected String accessToken;
  protected Calendar accessTokenExpiration;

  public MinistryByTextClient(Environment env) {
    this.env = env;
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
    if (isAccessTokenInvalid()) {
      log.info("Getting new access token...");
      TokenResponse tokenResponse = getAccessToken();
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

  protected TokenResponse getAccessToken() {
    // TODO: Map.of should be able to be used instead of Form (see VirtuousClient), but getting errors about no writer
    return post(
        AUTH_ENDPOINT,
        new Form()
            .param("client_id", env.getConfig().mbt.clientId)
            .param("client_secret", env.getConfig().mbt.clientSecret)
            .param("grant_type", "client_credentials"),
        APPLICATION_FORM_URLENCODED,
        HttpClient.HeaderBuilder.builder(),
        TokenResponse.class
    );
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
    MinistryByTextClient mbtClient = new MinistryByTextClient(env);

    CrmContact crmContact = new CrmContact();
    crmContact.id = "12345";
    crmContact.firstName = "Brett";
    crmContact.lastName = "Meyer";
    crmContact.mobilePhone = "260-349-5732";
    Subscriber subscriber = mbtClient.upsertSubscriber(crmContact, "c64ecadf-bbfa-4cd4-8f19-a64e5d661b2b");
    System.out.println(subscriber);
  }
}
