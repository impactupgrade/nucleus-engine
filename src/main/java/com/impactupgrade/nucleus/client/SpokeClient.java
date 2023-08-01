package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.entity.Organization;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.HttpClient;
import com.impactupgrade.nucleus.util.OAuth2;
import org.json.JSONObject;

import javax.ws.rs.core.GenericType;
import java.util.ArrayList;
import java.util.List;

import static com.impactupgrade.nucleus.util.HttpClient.get;
import static com.impactupgrade.nucleus.util.HttpClient.put;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

// TODO: To eventually become a spoke-phone-java-client open source lib?
public class SpokeClient extends OrgConfiguredClient {

  protected static String AUTH_ENDPOINT = "https://auth.spokephone.com/oauth/token";
  protected static String API_ENDPOINT_BASE = "https://integration.spokephone.com/";

  private final OAuth2.Context oAuth2Context;

  public SpokeClient(Environment env) {
    super(env);

    JSONObject spokeJson = getEnvJson().getJSONObject("spoke");

    this.oAuth2Context = new OAuth2.ClientCredentialsContext(
      env.getConfig().spoke.clientId, env.getConfig().spoke.clientSecret, 
      spokeJson.getString("accessToken"), spokeJson.getLong("expiresAt"), spokeJson.getString("refreshToken"), AUTH_ENDPOINT);
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

  protected HttpClient.HeaderBuilder headers() {
    String accessToken = oAuth2Context.accessToken();
    if (oAuth2Context.refresh().accessToken() != accessToken)  {
      // tokens updated - need to update config in db
      updateEnvJson("spoke", oAuth2Context);
    }
    return HttpClient.HeaderBuilder.builder().authBearerToken(oAuth2Context.accessToken());
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

  //TODO: remove once done with testing
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
    System.out.println(phonebooks);
//    Contact contact = spokeClient.upsertContact(crmContact, "Salesforce", phonebooks.get(0).id);

    // To check same access token is used
    phonebooks = spokeClient.getPhonebooks();
    System.out.println(phonebooks);
  }
}
