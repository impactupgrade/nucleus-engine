/*
 * Copyright (c) 2025 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import brevo.ApiException;
import brevoModel.CreateAttribute;
import brevoModel.CreateContact;
import brevoModel.GetAttributesAttributes;
import brevoModel.GetContactDetails;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.BrevoClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.Utils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.impactupgrade.nucleus.client.BrevoClient.FIRSTNAME;
import static com.impactupgrade.nucleus.client.BrevoClient.LASTNAME;
import static com.impactupgrade.nucleus.client.BrevoClient.SMS;
import static com.impactupgrade.nucleus.client.BrevoClient.TAGS;

public class BrevoCommunicationService extends AbstractCommunicationService {

  private final Set<String> attributeNames = new HashSet<>();

  @Override
  public String name() {
    return "brevo";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().brevo != null && !env.getConfig().brevo.isEmpty();
  }

  @Override
  protected List<EnvironmentConfig.CommunicationPlatform> getPlatformConfigs() {
    return env.getConfig().brevo;
  }

  @Override
  protected Set<String> getExistingContactEmails(EnvironmentConfig.CommunicationPlatform config, String listId) {
    BrevoClient brevoClient = env.brevoClient(config);
    try {
      List<GetContactDetails> listContacts = brevoClient.getContactsFromList(listId);
      return listContacts.stream().map(contact -> contact.getEmail().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    } catch (ApiException e) {
      env.logJobError("Failed to get existing contact emails from Brevo: {}", e.getMessage());
      return new HashSet<>();
    }
  }

  @Override
  protected void executeBatchUpsert(List<CrmContact> contacts,
      Map<String, Map<String, Object>> customFields, Map<String, Set<String>> tags,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    BrevoClient brevoClient = env.brevoClient(config);
    try {
      List<CreateContact> upsertMemberInfos = toCreateContacts(config, contacts, customFields, tags);
      brevoClient.importContacts(upsertMemberInfos, list.id);
    } catch (ApiException e) {
      env.logJobError("Brevo executeBatchUpsert failed (code/response body): {}/{}", e.getCode(), e.getResponseBody(), e);
      throw e;
    }
  }

  @Override
  protected void executeBatchArchive(Set<String> emails, String listId,
      EnvironmentConfig.CommunicationPlatform config) throws Exception {
    BrevoClient brevoClient = env.brevoClient(config);
    brevoClient.setEmailBlacklisted(emails);
    brevoClient.removeContactsFromList(emails, listId);
  }

  @Override
  protected List<String> getUnsubscribedEmails(String listId, Calendar lastSync,
      EnvironmentConfig.CommunicationPlatform config) throws Exception {
    BrevoClient brevoClient = env.brevoClient(config);
    List<GetContactDetails> listContacts = brevoClient.getContactsFromList(lastSync, listId);
    List<GetContactDetails> unsubscribed = listContacts.stream()
        .filter(c -> unsubscribedFromList(c, listId))
        .toList();
    return getEmails(unsubscribed);
  }

  @Override
  protected List<String> getBouncedEmails(String listId, Calendar lastSync,
      EnvironmentConfig.CommunicationPlatform config) throws Exception {
    // Brevo doesn't have separate bounced emails endpoint like Mailchimp
    return new ArrayList<>();
  }

  @Override
  protected Map<String, Object> buildPlatformCustomFields(CrmContact crmContact,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    BrevoClient brevoClient = env.brevoClient(config);

    Map<String, Object> customFieldMap = new HashMap<>();

    List<CustomField> customFields = buildContactCustomFields(crmContact, config, list);
    if (attributeNames.isEmpty()) {
      List<GetAttributesAttributes> attributes = brevoClient.getAttributes();
      for (GetAttributesAttributes attribute : attributes) {
        attributeNames.add(attribute.getName());
      }
    }
    // create tags attribute, if it doesn't already exist
    if (!attributeNames.contains(TAGS)) {
      brevoClient.createAttribute(TAGS, CreateAttribute.TypeEnum.TEXT);
      attributeNames.add(TAGS);
    }

    // create custom fields' attributes
    for (CustomField customField : customFields) {
      if (customField.value == null) {
        continue;
      }

      if (!attributeNames.contains(customField.name.toUpperCase(Locale.ROOT))) {
        //  TEXT("text"), DATE("date"), FLOAT("float"), BOOLEAN("boolean"), ID("id"), CATEGORY("category");
        CreateAttribute.TypeEnum type = switch (customField.type) {
          case DATE -> CreateAttribute.TypeEnum.DATE;
          case BOOLEAN -> CreateAttribute.TypeEnum.BOOLEAN;
          case NUMBER -> CreateAttribute.TypeEnum.FLOAT;
          default -> CreateAttribute.TypeEnum.TEXT;
        };
        brevoClient.createAttribute(customField.name, type);
        attributeNames.add(customField.name.toUpperCase(Locale.ROOT));
      }

      Object value = customField.value;
      if (customField.type == CustomFieldType.BOOLEAN) {
        if (((Boolean) value)) {
          value = 1;
        } else {
          value = 0;
        }
      } else if (customField.type == CustomFieldType.DATE) {
        Calendar c = (Calendar) value;
        value = new SimpleDateFormat("MM/dd/yyyy").format(c.getTime());
      }

      customFieldMap.put(customField.name, value);
    }

    return customFieldMap;
  }

  protected boolean unsubscribedFromList(GetContactDetails getContactDetails, String listId) {
    Long id = Utils.parseLong(listId);
    return id != null && getContactDetails.getListUnsubscribed() != null && getContactDetails.getListUnsubscribed().contains(id);
  }

  protected List<String> getEmails(List<GetContactDetails> contacts) {
    return contacts.stream().map(c -> c.getEmail().toLowerCase(Locale.ROOT)).distinct().sorted().toList();
  }

  protected List<CreateContact> toCreateContacts(EnvironmentConfig.CommunicationPlatform brevoConfig,
      List<CrmContact> crmContacts, Map<String, Map<String, Object>> customFieldsMap,
      Map<String, Set<String>> activeTags) {
    return crmContacts.stream()
        .map(crmContact -> toCreateContact(brevoConfig, crmContact, customFieldsMap.get(crmContact.email), activeTags.get(crmContact.email)))
        .collect(Collectors.toList());
  }

  protected CreateContact toCreateContact(EnvironmentConfig.CommunicationPlatform brevoConfig,
      CrmContact crmContact, Map<String, Object> customFields, Set<String> tags) {
    if (crmContact == null) {
      return null;
    }

    CreateContact createContact = new CreateContact();
    createContact.setEmail(crmContact.email);
    createContact.setEmailBlacklisted(!crmContact.canReceiveEmail());
    createContact.setUpdateEnabled(true); // to allow upsert

    Properties attributes = new Properties();
    if (!Strings.isNullOrEmpty(crmContact.firstName)) {
      attributes.setProperty(FIRSTNAME, crmContact.firstName);
    }
    if (!Strings.isNullOrEmpty(crmContact.lastName)) {
      attributes.setProperty(LASTNAME, crmContact.lastName);
    }
    attributes.putAll(customFields);

    String tagsString = "";
    if (tags != null && !tags.isEmpty()) {
      tagsString = tags.stream().collect(Collectors.joining(", "));
    }
    attributes.setProperty(TAGS, tagsString);

    // don't blacklist anyone if sms is not actually enabled
    if (brevoConfig.enableSms) {
      if (smsAllowed(brevoConfig, crmContact)) {
        createContact.setSmsBlacklisted(false);
        attributes.setProperty(SMS, crmContact.phoneNumberForSMS());
      } else {
        createContact.setSmsBlacklisted(true);
      }
    }

    createContact.setAttributes(attributes);

    return createContact;
  }

  private boolean smsAllowed(EnvironmentConfig.CommunicationPlatform brevoConfig, CrmContact crmContact) {
    if (!brevoConfig.enableSms) {
      return false;
    }
    boolean smsOptIn = Boolean.TRUE == crmContact.smsOptIn && Boolean.TRUE != crmContact.smsOptOut;
    if (!smsOptIn) {
      return false;
    }
    String phoneNumber = crmContact.phoneNumberForSMS();
    if (Strings.isNullOrEmpty(phoneNumber)) {
      return false;
    }

    boolean smsAllowed = false;
    if (!Strings.isNullOrEmpty(brevoConfig.countryCode) && phoneNumber.startsWith(brevoConfig.countryCode)) {
      smsAllowed = true;
    } else if (!Strings.isNullOrEmpty(brevoConfig.country) && !phoneNumber.startsWith("+")) {
      smsAllowed = Stream.of(crmContact.account.billingAddress, crmContact.account.mailingAddress, crmContact.mailingAddress)
          .filter(Objects::nonNull)
          .anyMatch(crmAddress -> brevoConfig.country.equalsIgnoreCase(crmAddress.country));
    }
    return smsAllowed;
  }
}
