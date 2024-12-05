/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.Utils;

import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class AbstractCommunicationService implements CommunicationService {

  protected Environment env;

  @Override
  public void init(Environment env) {
    this.env = env;
    // TODO: DO NOT try to pull CrmService here! We should consider a refactor (possibly splitting EmailService
    //  into an email sync service vs. transactional email service. Currently, nucleus-core and others use
    //  SendGridEmailService, even when no CrmService is identified.
  }

  protected List<CustomField> buildContactCustomFields(CrmContact crmContact,
      EnvironmentConfig.CommunicationPlatform communicationPlatform,
      EnvironmentConfig.CommunicationList communicationList) throws Exception {
    List<CustomField> customFields = new ArrayList<>();

    if (!Strings.isNullOrEmpty(crmContact.title)) {
      customFields.add(new CustomField("title", CustomFieldType.STRING, crmContact.title));
    }

    if (!Strings.isNullOrEmpty(crmContact.mailingAddress.city)) {
      customFields.add(new CustomField("city", CustomFieldType.STRING, crmContact.mailingAddress.city));
    }
    if (!Strings.isNullOrEmpty(crmContact.mailingAddress.state)) {
      customFields.add(new CustomField("state", CustomFieldType.STRING, crmContact.mailingAddress.state));
    }
    if (!Strings.isNullOrEmpty(crmContact.mailingAddress.postalCode)) {
      customFields.add(new CustomField("postal_code", CustomFieldType.STRING, crmContact.mailingAddress.postalCode));
    }
    if (!Strings.isNullOrEmpty(crmContact.mailingAddress.country)) {
      customFields.add(new CustomField("country", CustomFieldType.STRING, crmContact.mailingAddress.country));
    }

    customFields.add(new CustomField("crm_contact_id", CustomFieldType.STRING, crmContact.id));
    if (crmContact.account.id != null) {
      customFields.add(new CustomField("crm_account_id", CustomFieldType.STRING, crmContact.account.id));
    }
    if (crmContact.firstDonationDate != null) {
      customFields.add(new CustomField("date_of_first_donation", CustomFieldType.DATE, crmContact.firstDonationDate));
    }
    if (crmContact.lastDonationDate != null) {
      customFields.add(new CustomField("date_of_last_donation", CustomFieldType.DATE, crmContact.lastDonationDate));
    }
    if (crmContact.largestDonationAmount != null) {
      customFields.add(new CustomField("largest_donation", CustomFieldType.NUMBER, crmContact.largestDonationAmount));
    }
    if (crmContact.totalDonationAmount != null) {
      customFields.add(new CustomField("total_of_donations", CustomFieldType.NUMBER, crmContact.totalDonationAmount));
    }
    if (crmContact.numDonations != null) {
      customFields.add(new CustomField("number_of_donations", CustomFieldType.NUMBER, crmContact.numDonations));
    }
    if (crmContact.totalDonationAmountYtd != null) {
      customFields.add(new CustomField("total_of_donations_ytd", CustomFieldType.NUMBER, crmContact.totalDonationAmountYtd));
    }
    if (crmContact.numDonationsYtd != null) {
      customFields.add(new CustomField("number_of_donations_ytd", CustomFieldType.NUMBER, crmContact.numDonationsYtd));
    }

    customFields.addAll(communicationPlatform.crmFieldToCommunicationFields.stream()
            .map(mapping -> getCustomField(crmContact, mapping))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet()));

    return customFields;
  }

  protected CustomField getCustomField(CrmContact crmContact, EnvironmentConfig.CrmFieldToCommunicationField mapping) {
    Object value = crmContact.fieldFetcher != null ? crmContact.fieldFetcher.apply(mapping.crmFieldName) : null;
    if (value != null) {
      return new CustomField(mapping.communicationFieldName, getCustomFieldType(value), value);
    } else {
      return null;
    }
  }

  protected enum CustomFieldType {
    STRING, NUMBER, BOOLEAN, DATE
  }
  protected static class CustomField {
    public String name;
    public CustomFieldType type;
    public Object value;
    public CustomField(String name, CustomFieldType type, Object value) {
      this.name = name;
      this.type = type;
      this.value = value;
    }
  }

  //TODO: explicitly define in env config?
  protected CustomFieldType getCustomFieldType(Object value) {
    if (value == null) {
      return null;
    }
    CustomFieldType customFieldType;
    if (value instanceof Number) {
      customFieldType = CustomFieldType.NUMBER;
    } else if (value instanceof Boolean) {
      customFieldType = CustomFieldType.BOOLEAN;
    } else if (value instanceof Date || value instanceof Calendar || value instanceof Temporal) {
      customFieldType = CustomFieldType.DATE;
    } else {
      customFieldType = CustomFieldType.STRING;
    }
    return customFieldType;
  }

  protected Set<String> getContactTagsCleaned(CrmContact crmContact, List<String> contactCampaignNames,
      EnvironmentConfig.CommunicationPlatform communicationPlatform,
      EnvironmentConfig.CommunicationList communicationList) throws Exception {
    Set<String> tags = buildContactTags(crmContact, contactCampaignNames, communicationPlatform, communicationList);

    // Mailchimp's Salesforce plugin chokes on tags > 80 chars, which seems like a sane limit anyway.
    Set<String> cleanedTags = new HashSet<>();
    for (String tag : tags) {
      if (tag.length() > 80) {
        tag = tag.substring(0, 80);
      }
      cleanedTags.add(tag);
    }

    return cleanedTags;
  }

  // Separate method, allowing orgs to add in (or completely override) the defaults.
  // NOTE: Only use alphanumeric and _ chars! Some providers, like SendGrid, are using custom fields for
  //  tags and have limitations on field names.
  // IMPORTANT: At the moment, any new tag added here must be added to EnvironmentConfig.CommunicationPlatform.controlledTags!
  protected Set<String> buildContactTags(CrmContact crmContact, List<String> contactCampaignNames,
      EnvironmentConfig.CommunicationPlatform communicationPlatform,
      EnvironmentConfig.CommunicationList communicationList) throws Exception {
    Set<String> tags = new HashSet<>();

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // DONATION METRICS
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    if (crmContact.lastDonationDate != null) {
      tags.add("donor");
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // INTERNAL INFO
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    if (crmContact.ownerName != null) {
      tags.add("owner_" + Utils.toSlug(crmContact.ownerName));
    }

    if (contactCampaignNames != null) {
      for (String c : contactCampaignNames) {
        tags.add("campaign_" + Utils.toSlug(c));
      }
    }

    if (!Strings.isNullOrEmpty(crmContact.account.recordTypeName)) {
      tags.add("account_type_" + Utils.toSlug(crmContact.account.recordTypeName));
    }

    tags.addAll(communicationPlatform.crmFieldToCommunicationTags.stream()
            .map(mapping -> getTagName(crmContact, mapping))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet()));

    return tags;
  }

  protected String getTagName(CrmContact crmContact, EnvironmentConfig.CrmFieldToCommunicationTag mapping) {
    Object crmFieldValue = crmContact.fieldFetcher != null ? crmContact.fieldFetcher.apply(mapping.crmFieldName) : null;
    String crmFieldValueString = crmFieldValue == null ? "" : crmFieldValue.toString();
    if (evaluate(crmFieldValueString, mapping.operator, mapping.value)) {
      return mapping.communicationTagName;
    } else {
      return null;
    }
  }

  protected boolean evaluate(String crmFieldValueString, EnvironmentConfig.Operator operator, String value) {
    if (Strings.isNullOrEmpty(crmFieldValueString) || operator == null) {
      return false;
    }
    return switch(operator) {
      case NOT_EMPTY -> Strings.isNullOrEmpty(crmFieldValueString);
      case EQUAL_TO ->  crmFieldValueString.equalsIgnoreCase(value);
      case NOT_EQUAL_TO -> !crmFieldValueString.equalsIgnoreCase(value);
    };
  }

  /**
   * Returns the ID of the group from the config map
   */
  protected String getGroupIdFromName(String groupName, Map<String, String> groups) {
    return groups.entrySet().stream()
        .filter(e -> e.getKey().equalsIgnoreCase(groupName))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElseThrow(() -> new RuntimeException("group " + groupName + " not configured in environment.json"));
  }
}
