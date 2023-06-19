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
import static com.impactupgrade.nucleus.util.HttpClient.put;
import static javax.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

// TODO: To eventually become a spoke-phone-java-client open source lib?
public class SpokeClient {

  private static final Logger log = LogManager.getLogger(SpokeClient.class);

  protected static String AUTH_ENDPOINT = "https://auth.spokephone.com/oauth/token";
  protected static String API_ENDPOINT_BASE = "https://integration.spokephone.com/";

  protected final Environment env;

  protected String accessToken;
  protected Calendar accessTokenExpiration;

  public SpokeClient(Environment env) {
    this.env = env;
  }

  public List<Phonebook> getPhonebooks() {
    return get(API_ENDPOINT_BASE + "phonebooks", headers(), new GenericType<>() {});
  }

  public Phonebook createPhonebook(String name, String description, String countryIso) {
    Phonebook phonebook = new Phonebook();
    phonebook.name = name;
    phonebook.description = description;
    phonebook.countryIso = countryIso;
    return put(API_ENDPOINT_BASE + "phonebooks", phonebook, APPLICATION_JSON, headers(), Phonebook.class);
  }

  public Contact upsertContact(CrmContact crmContact, String crmName, String phonebookId) {
    ContactRequest contactRequest = new ContactRequest();
    contactRequest.contact = toSpokeContact(crmContact, crmName);
    return put(API_ENDPOINT_BASE + "phonebooks/" + phonebookId + "/contacts", contactRequest, APPLICATION_JSON, headers(), Contact.class);
  }

  protected Contact toSpokeContact(CrmContact crmContact, String crmName) {
    Contact contact = new Contact();
    contact.id = crmContact.id;
    contact.firstName = crmContact.firstName;
    contact.lastName = crmContact.lastName;

    if (!Strings.isNullOrEmpty(crmContact.mobilePhone)) {
      ContactPhone phone = new ContactPhone();
      phone.label = "Mobile";
      phone.value = crmContact.mobilePhone;
      contact.phoneNumbers.add(phone);
    }
    if (!Strings.isNullOrEmpty(crmContact.homePhone)) {
      ContactPhone phone = new ContactPhone();
      phone.label = "Home";
      phone.value = crmContact.homePhone;
      contact.phoneNumbers.add(phone);
    }
    if (!Strings.isNullOrEmpty(crmContact.workPhone)) {
      ContactPhone phone = new ContactPhone();
      phone.label = "Work";
      phone.value = crmContact.workPhone;
      contact.phoneNumbers.add(phone);
    }

    // TODO: all the extra CRM context

    return contact;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Phonebook {
    public String id;
    public String name;
    public String description;
    public String countryIso;
    public List<Contact> contacts = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Contact {
    public String id;
    public String firstName;
    public String lastName;
    public List<ContactPhone> phoneNumbers = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactPhone {
    public String label;
    public String value;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactRequest {
    public Contact contact;
    public String countryIso;
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
    // TODO: Map.of should be ablet o be used instead of Form (see VirtuousClient), but getting errors about no writer
    return post(
        AUTH_ENDPOINT,
        new Form()
            .param("client_id", env.getConfig().spoke.clientId)
            .param("client_secret", env.getConfig().spoke.clientSecret)
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
        envConfig.spoke.clientId = "1fkdv7al7lr0smo2pq2jq06fbc";
        envConfig.spoke.clientSecret = "7m9ci3r300lurue114m5rc0f2adm2fhhh0t8subv6grfpc2nncu";
        return envConfig;
      }
    };
    SpokeClient spokeClient = new SpokeClient(env);

    CrmContact crmContact = new CrmContact();
    crmContact.id = "12345";
    crmContact.firstName = "Brett";
    crmContact.lastName = "Meyer";
    crmContact.mobilePhone = "260-349-5732";
//    Phonebook phonebook = spokeClient.createPhonebook("Salesforce US", "Salesforce contacts in the US", "US");
    List<Phonebook> phonebooks = spokeClient.getPhonebooks();
//    Contact contact = spokeClient.upsertContact(crmContact, "Salesforce", phonebooks.get(0).id);
    System.out.println(phonebooks);
  }
}