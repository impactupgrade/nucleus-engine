package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class SendGridEmailService extends SmtpEmailService {

  private static final Logger log = LogManager.getLogger(SendGridEmailService.class);
  private static final ObjectMapper mapper = new ObjectMapper();

  @Override
  public String name() {
    return "sendgrid";
  }

  @Override
  protected List<EnvironmentConfig.EmailPlatform> emailPlatforms() {
    return env.getConfig().sendgrid;
  }

  @Override
  public void sendEmailTemplate(String template, String to) {
    log.info("not implemented: sendEmailTemplate");
    // TODO
  }

  @Override
  public void syncContacts(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.EmailPlatform emailPlatform : env.getConfig().sendgrid) {
      SendGrid sendgridClient = new SendGrid(emailPlatform.secretKey);
      for (EnvironmentConfig.EmailList emailList : emailPlatform.lists) {
        // TODO: SG has a max of 30k per call, so we may need to break this down for some customers.
        List<CrmContact> crmContacts = getCrmContacts(emailList, lastSync);
        Map<String, List<String>> contactCampaignNames = getContactCampaignNames(crmContacts);

        log.info("upserting {} contacts to list {}", crmContacts.size(), emailList.id);

        Request request = new Request();
        request.setMethod(Method.PUT);
        request.setEndpoint("/marketing/contacts");
        UpsertContacts upsertContacts = new UpsertContacts();
        upsertContacts.list_ids = List.of(emailList.id);
        upsertContacts.contacts = crmContacts.stream()
            .map(c -> toSendGridContact(c, contactCampaignNames.get(c.id), emailList.groups, emailPlatform))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        request.setBody(mapper.writeValueAsString(upsertContacts));
        Response response = sendgridClient.api(request);
//          System.out.println(response.getStatusCode());
//          System.out.println(response.getBody());
//          System.out.println(response.getHeaders());
      }
    }
  }

  protected Contact toSendGridContact(CrmContact crmContact, List<String> campaignNames, Map<String, String> groups,
      EnvironmentConfig.EmailPlatform emailPlatform) {
    if (crmContact == null) {
      return null;
    }
    
    Contact contact = new Contact();

    try {
      contact.email = crmContact.email;
      contact.first_name = crmContact.firstName;
      contact.last_name = crmContact.lastName;
      // TODO: CRM street does not handle multiple address lines eg. addr1 & addr2
      contact.address_line_1 = crmContact.address.street;
      contact.city = crmContact.address.city;
      contact.state_province_region = crmContact.address.state;
      contact.postal_code = crmContact.address.postalCode;
      contact.country = crmContact.address.country;
      // TODO: contact.canReceiveEmail()? Is there a "status" field, or do we instead need to simply remove from the list?

      // TODO: custom fields must first exist, so create a map from https://docs.sendgrid.com/api-reference/custom-fields/get-all-field-definitions
      //  up front, check it as we go, and add new fields with https://docs.sendgrid.com/api-reference/custom-fields/create-custom-field-definition
      //  as needed?
      List<String> activeTags = buildContactTags(crmContact, campaignNames, emailPlatform);
      // TODO: may need to use https://docs.sendgrid.com/api-reference/contacts/get-contacts-by-emails to get the
      //  total list
//      List<String> inactiveTags =
//      inactiveTags.removeAll(activeTags);
      activeTags.forEach(t -> contact.custom_fields.put(t, "true"));

      // TODO: groups?
    } catch (Exception e) {
      log.error("failed to map the sendgrid contact {}", crmContact.email, e);
    }

    return contact;
  }
  
  protected static class UpsertContacts {
    List<String> list_ids;
    List<Contact> contacts;
  }

  protected static class Contact {
    public String address_line_1;
    public String address_line_2;
    public String city;
    public String country;
    public String email;
    public String first_name;
    public String last_name;
    public String postal_code;
    public String state_province_region;
    public Map<String, String> custom_fields = new HashMap<>();
  }

  protected String host(EnvironmentConfig.EmailPlatform emailPlatform) { return "smtp.sendgrid.net"; }
  protected String port(EnvironmentConfig.EmailPlatform emailPlatform) { return "587"; }
  protected String username(EnvironmentConfig.EmailPlatform emailPlatform) { return "apikey"; }
  protected String password(EnvironmentConfig.EmailPlatform emailPlatform) {
    if (Strings.isNullOrEmpty(emailPlatform.secretKey)) {
      // legacy support
      return System.getenv("SENDGRID_KEY");
    }
    return emailPlatform.secretKey;
  }
}
