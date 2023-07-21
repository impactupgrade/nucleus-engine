package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.HttpClient;
import com.impactupgrade.nucleus.util.OAuth2Util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

public class ConstantContactCommunicationService extends AbstractCommunicationService {

  private static final String API_BASE_URL = "https://api.cc.email/v3";
  private static final String AUTH_URL = "https://authz.constantcontact.com/oauth2/default/v1/token";

  //TODO: replace with oauth context when oauth pr is merged
  private OAuth2Util.Tokens tokens;

  @Override
  public String name() {
    return "constantContact";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().constantContact != null && !env.getConfig().constantContact.isEmpty();
  }

  public void getUserPrivileges() {
    EnvironmentConfig.CommunicationPlatform constantContact = env.getConfig().constantContact.get(0);
    String s = HttpClient.get(API_BASE_URL + "/account/user/privileges", headers(constantContact), String.class);
    System.out.println(s);
  }

  @Override
  public void syncContacts(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.CommunicationPlatform communicationPlatform : env.getConfig().constantContact) {
      for (EnvironmentConfig.CommunicationList communicationList : communicationPlatform.lists) {

        //List<CrmContact> crmContacts = env.primaryCrmService().getSmsContacts(lastSync, communicationList);

        CrmContact contact1 = new CrmContact();
        contact1.firstName = "Tom";
        contact1.lastName = "Ford";
        contact1.email = "tf@gmail.com";
        contact1.mobilePhone = "1234567890";

        List<CrmContact> crmContacts = List.of(contact1);

        for (CrmContact crmContact : crmContacts) {
          if (!Strings.isNullOrEmpty(crmContact.phoneNumberForSMS())) {
            env.logJobInfo("upserting contact {} {} on list {}", crmContact.id, crmContact.phoneNumberForSMS(), communicationList.id);
            upsertContact(crmContact, communicationPlatform, communicationList.id);
          }
        }
      }
    }
  }

  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {

  }

  @Override
  public void upsertContact(String contactId) throws Exception {
    CrmService crmService = env.primaryCrmService();

    for (EnvironmentConfig.CommunicationPlatform communicationPlatform : env.getConfig().constantContact) {
      for (EnvironmentConfig.CommunicationList communicationList : communicationPlatform.lists) {
        Optional<CrmContact> crmContact = crmService.getFilteredContactById(contactId, communicationList.crmFilter);

        if (crmContact.isPresent() && !Strings.isNullOrEmpty(crmContact.get().phoneNumberForSMS())) {
          env.logJobInfo("upserting contact {} {} on list {}", crmContact.get().id, crmContact.get().phoneNumberForSMS(), communicationList.id);
          upsertContact(crmContact.get(), communicationPlatform, communicationList.id);
        }
      }
    }
  }

  protected Contact upsertContact(CrmContact crmContact, EnvironmentConfig.CommunicationPlatform communicationPlatform, String listId) {
    Contact contact = toContact(crmContact, listId);
    String response = HttpClient.post(API_BASE_URL + "/contacts/sign_up_form", contact, APPLICATION_JSON, headers(communicationPlatform), String.class);
    System.out.println(response);
    return contact;
  }

  protected HttpClient.HeaderBuilder headers(EnvironmentConfig.CommunicationPlatform communicationPlatform) {
    String auth = communicationPlatform.clientId + ":" + communicationPlatform.clientSecret;
    String encodedAuth = Base64.getEncoder().encodeToString((auth).getBytes(StandardCharsets.UTF_8));

    if (tokens == null) {
      tokens = new OAuth2Util.Tokens(communicationPlatform.accessToken, null, communicationPlatform.refreshToken);
    }

    tokens = OAuth2Util.refreshTokens(tokens,
        null,
        Map.of(
            "Authorization", "Basic " + encodedAuth),
        AUTH_URL);

    String accessToken = tokens != null ? tokens.accessToken() : null;
    return HttpClient.HeaderBuilder.builder().authBearerToken(accessToken);
  }

  //TODO: remove once done with testing
  public static void main(String[] args) throws Exception {
    Environment env = new Environment() {
      @Override
      public EnvironmentConfig getConfig() {
        EnvironmentConfig.CommunicationPlatform communicationPlatform = new EnvironmentConfig.CommunicationPlatform();
        communicationPlatform.clientId = "f7a59166-c879-4763-82b2-d5aff202a4a3";
        communicationPlatform.clientSecret = "WSpU_LGm036P6EVnPw3bgQ";
        communicationPlatform.refreshToken = "Z5OlO3ey0ISkW8P0VkxU1yn02eLr96S4p7W3VBeWxxE";

        EnvironmentConfig.CommunicationList communicationList = new EnvironmentConfig.CommunicationList();
        communicationList.id = "88aef5b5-8d67-409d-8139-c299a695c078";
        communicationPlatform.lists = List.of(communicationList);

        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.constantContact = new ArrayList<>();
        envConfig.constantContact.add(communicationPlatform);
        return envConfig;
      }
    };

    ConstantContactCommunicationService ccService = new ConstantContactCommunicationService();
    ccService.init(env);

    ccService.getUserPrivileges();
    ccService.getUserPrivileges();

    ccService.syncContacts(null);
  }

  private Contact toContact(CrmContact crmContact, String listId) {
    if (crmContact == null) {
      return null;
    }
    Contact contact = new Contact();
    contact.firstname = crmContact.firstName;
    contact.lastname = crmContact.lastName;
    contact.emailAddress = crmContact.email;
    contact.listMemberships = List.of(listId);
    return contact;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static final class Contact {
    @JsonProperty("contact_id")
    public String id;
    @JsonProperty("first_name")
    public String firstname;
    @JsonProperty("last_name")
    public String lastname;
    @JsonProperty("email_address")
    public String emailAddress;
    @JsonProperty("list_memberships")
    public List<String> listMemberships;
  }
}