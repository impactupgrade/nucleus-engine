/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class SendGridCommunicationService extends AbstractCommunicationService {

  private static final ObjectMapper mapper = new ObjectMapper()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  @Override
  public String name() {
    return "sendgrid";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().sendgrid != null && !env.getConfig().sendgrid.isEmpty();
  }

  @Override
  protected List<EnvironmentConfig.CommunicationPlatform> getPlatformConfigs() {
    return env.getConfig().sendgrid;
  }

  @Override
  protected Set<String> getExistingContactEmails(EnvironmentConfig.CommunicationPlatform config, String listId) {
    // SendGrid doesn't have a simple way to get all contacts for a list
    // Return empty set for now - batch operations will handle duplicates
    return new HashSet<>();
  }

  @Override
  protected void executeBatchUpsert(List<CrmContact> contacts,
      Map<String, Map<String, Object>> customFields, Map<String, Set<String>> tags,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    SendGrid sendgridClient = new SendGrid(config.secretKey);

    // SendGrid requires that custom field definitions first be explicitly created
    Request request = new Request();
    request.setMethod(Method.GET);
    request.setEndpoint("/marketing/field_definitions");
    Response response = sendgridClient.api(request);
    CustomFieldsResponse customFieldsResponse = mapper.readValue(response.getBody(), CustomFieldsResponse.class);
    Map<String, String> customFieldsByName = customFieldsResponse.custom_fields.stream().collect(Collectors.toMap(f -> f.name, f -> f.id));

    Map<String, List<String>> contactCampaignNames = env.primaryCrmService().getContactsCampaigns(contacts, list);

    env.logJobInfo("upserting {} contacts to list {}", contacts.size(), list.id);

    request = new Request();
    request.setMethod(Method.PUT);
    request.setEndpoint("/marketing/contacts");
    UpsertContacts upsertContacts = new UpsertContacts();
    upsertContacts.list_ids = List.of(list.id);
    upsertContacts.contacts = contacts.stream()
        .map(crmContact -> toSendGridContact(crmContact, contactCampaignNames.get(crmContact.id),
            customFieldsByName, sendgridClient, config, list))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
    request.setBody(mapper.writeValueAsString(upsertContacts));
    response = sendgridClient.api(request);
    if (response.getStatusCode() < 300) {
      env.logJobInfo("sync was successful");
    } else {
      env.logJobError("sync failed: {}", response.getBody());
    }
  }

  @Override
  protected void executeBatchArchive(Set<String> emails, String listId,
      EnvironmentConfig.CommunicationPlatform config) throws Exception {
    // TODO: SendGrid archive implementation
  }

  @Override
  protected List<String> getUnsubscribedEmails(String listId, Calendar lastSync,
      EnvironmentConfig.CommunicationPlatform config) throws Exception {
    // TODO: SendGrid unsubscribe implementation
    return new ArrayList<>();
  }

  @Override
  protected List<String> getBouncedEmails(String listId, Calendar lastSync,
      EnvironmentConfig.CommunicationPlatform config) throws Exception {
    // TODO: SendGrid bounced implementation
    return new ArrayList<>();
  }

  @Override
  protected Map<String, Object> buildPlatformCustomFields(CrmContact crmContact,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    // SendGrid custom fields are handled in the contact conversion - return empty map
    return new HashMap<>();
  }

  protected Contact toSendGridContact(CrmContact crmContact, List<String> campaignNames,
      Map<String, String> customFieldsByName, SendGrid sendgridClient,
      EnvironmentConfig.CommunicationPlatform communicationPlatform,
      EnvironmentConfig.CommunicationList communicationList) {
    if (crmContact == null) {
      return null;
    }
    
    Contact contact = new Contact();

    try {
      contact.email = crmContact.email;
      contact.first_name = crmContact.firstName;
      contact.last_name = crmContact.lastName;
      // TODO: CRM street does not handle multiple address lines eg. addr1 & addr2
      contact.address_line_1 = crmContact.mailingAddress.street;
      contact.city = crmContact.mailingAddress.city;
      contact.state_province_region = crmContact.mailingAddress.state;
      contact.postal_code = crmContact.mailingAddress.postalCode;
      contact.country = crmContact.mailingAddress.country;
      // TODO: contact.canReceiveEmail()? Is there a "status" field, or do we instead need to simply remove from the list?

      Set<String> activeTags = getContactTagsCleaned(crmContact, campaignNames, communicationPlatform, communicationList);
      // TODO: may need to use https://docs.sendgrid.com/api-reference/contacts/get-contacts-by-emails to get the
      //  total list
//      List<String> inactiveTags =
//      inactiveTags.removeAll(activeTags);
      activeTags.forEach(t -> {
        String customFieldId = getCustomFieldId(t, customFieldsByName, sendgridClient);
        contact.custom_fields.put(customFieldId, "true");
      });

      // TODO: groups?
    } catch (Exception e) {
      env.logJobError("failed to map the sendgrid contact {}", crmContact.email, e);
    }

    return contact;
  }

  protected String getCustomFieldId(String fieldName, Map<String, String> customFieldsByName, SendGrid sendgridClient) {
    if (!customFieldsByName.containsKey(fieldName)) {
      try {
        Request request = new Request();
        request.setMethod(Method.POST);
        request.setEndpoint("/marketing/field_definitions");
        CustomField customField = new CustomField();
        customField.name = fieldName;
        customField.field_type = "Text";
        request.setBody(mapper.writeValueAsString(customField));
        Response response = sendgridClient.api(request);
        if (response.getStatusCode() < 300) {
          env.logJobInfo("created custom field: {}", fieldName);

          customField = mapper.readValue(response.getBody(), CustomField.class);
          customFieldsByName.put(fieldName, customField.id);
        } else {
          env.logJobError("failed to create custom field: {}", fieldName);
        }
      } catch (Exception e) {
        env.logJobError("failed to create custom field: {}", fieldName, e);
      }
    }

    return customFieldsByName.get(fieldName);
  }

  protected static class CustomFieldsResponse {
    public List<CustomField> custom_fields = new ArrayList<>();
  }

  protected static class CustomField {
    public String id;
    public String name;
    public String field_type; // Text, Number, Date
  }

  protected static class UpsertContacts {
    public List<String> list_ids;
    public List<Contact> contacts;
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
}
