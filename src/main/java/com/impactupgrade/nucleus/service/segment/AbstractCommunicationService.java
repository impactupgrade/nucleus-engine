package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.Utils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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

  protected Map<String, List<String>> getContactCampaignNames(List<CrmContact> crmContacts) throws Exception {
    List<String> crmContactIds = crmContacts.stream().map(c -> c.id).filter(Objects::nonNull).collect(Collectors.toList());
    if (crmContactIds.isEmpty()) {
      return Collections.emptyMap();
    }
    return env.primaryCrmService().getContactCampaignsByContactIds(crmContactIds);
  }

  protected List<CrmContact> getEmailContacts(Calendar lastSync, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    List<CrmContact> crmContacts = env.primaryCrmService().getEmailContacts(lastSync, communicationList);

    Map<String, CrmContact> uniqueContacts = crmContacts.stream().collect(Collectors.toMap(
        so -> so.email.toLowerCase(Locale.ROOT),
        Function.identity(),
        // IMPORTANT: FIFO. If contacts share an email address, the oldest record is typically the truth.
        (so1, so2) -> so1
    ));
    return new ArrayList<>(uniqueContacts.values());
  }

  protected List<CustomField> buildContactCustomFields(CrmContact crmContact,
      EnvironmentConfig.CommunicationPlatform communicationPlatform) throws Exception {
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

    return customFields;
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

  protected final Set<String> getContactTagsCleaned(CrmContact crmContact, List<String> contactCampaignNames,
      EnvironmentConfig.CommunicationPlatform communicationPlatform) throws Exception {
    Set<String> tags = buildContactTags(crmContact, contactCampaignNames, communicationPlatform);

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
  protected Set<String> buildContactTags(CrmContact crmContact, List<String> contactCampaignNames,
      EnvironmentConfig.CommunicationPlatform communicationPlatform) throws Exception {
    Set<String> tags = new HashSet<>();

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // DONATION METRICS
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    if (crmContact.totalDonationAmount != null && communicationPlatform.tagFilters.majorDonorAmount != null
        && crmContact.totalDonationAmount >= communicationPlatform.tagFilters.majorDonorAmount) {
      tags.add("major_donor");
    }

    if (crmContact.lastDonationDate != null) {
      tags.add("donor");

      if (communicationPlatform.tagFilters.recentDonorDays != null) {
        Calendar limit = Calendar.getInstance();
        limit.add(Calendar.DAY_OF_MONTH, -communicationPlatform.tagFilters.recentDonorDays);
        if (crmContact.lastDonationDate.after(limit)) {
          tags.add("recent_donor");
        }
      }
    }

    if (crmContact.numDonations != null && communicationPlatform.tagFilters.frequentDonorCount != null
        && crmContact.numDonations >= communicationPlatform.tagFilters.frequentDonorCount) {
      tags.add("frequent_donor");
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // DEMOGRAPHIC INFO
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // TODO: Would be great to have age, but the field (or "birthdate") tends to be custom within SFDC.
//      char contactAgeGroup = Integer.toString(primaryCrmService.getAge(contact)).charAt(0);
//      addTagToContact(listId, contact, "Age: " + contactAgeGroup + "0 - " + contactAgeGroup + "9");

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

    return tags;
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
