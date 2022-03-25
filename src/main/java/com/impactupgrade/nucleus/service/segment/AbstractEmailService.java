package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.Utils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public abstract class AbstractEmailService implements EmailService {

  protected Environment env;
  protected CrmService primaryCrmService;
  protected CrmService donationsCrmService;

  @Override
  public void init(Environment env) {
    this.env = env;
    primaryCrmService = env.primaryCrmService();
    donationsCrmService = env.donationsCrmService();
  }

  protected List<CrmContact> getCrmContacts(EnvironmentConfig.EmailList emailList, Calendar lastSync) throws Exception {
    return switch (emailList.type) {
      case CONTACTS -> primaryCrmService.getEmailContacts(lastSync, emailList.crmFilter);
      case DONORS -> donationsCrmService.getEmailDonorContacts(lastSync, emailList.crmFilter);
    };
  }

  protected Map<String, List<String>> getContactCampaignNames(List<CrmContact> crmContacts) throws Exception {
    List<String> crmContactIds = crmContacts.stream().map(c -> c.id).collect(Collectors.toList());
    return primaryCrmService.getActiveCampaignsByContactIds(crmContactIds);
  }

  protected Map<String, Object> buildContactCustomFields(CrmContact crmContact,
      EnvironmentConfig.EmailPlatform emailPlatform) throws Exception {
    Map<String, Object> fields = new HashMap<>();

    if (crmContact.firstDonationDate != null) {
      // TODO: will probably need to convert this
      fields.put("date_of_first_donation", crmContact.firstDonationDate);
    }
    if (crmContact.lastDonationDate != null) {
      // TODO: will probably need to convert this
      fields.put("date_of_last_donation", crmContact.lastDonationDate);
    }
    if (crmContact.totalDonationAmount != null) {
      fields.put("total_of_donations", crmContact.totalDonationAmount);
    }
    if (crmContact.numDonations != null) {
      fields.put("number_of_donations", crmContact.numDonations);
    }

    return fields;
  }

  // Separate method, allowing orgs to add in (or completely override) the defaults.
  // NOTE: Only use alphanumeric and _ chars! Some providers, like SendGrid, are using custom fields for
  //  tags and have limitations on field names.
  protected List<String> buildContactTags(CrmContact crmContact, List<String> contactCampaignNames,
      EnvironmentConfig.EmailPlatform emailPlatform) throws Exception {
    List<String> tags = new ArrayList<>();

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // DONATION METRICS
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    if (crmContact.totalDonationAmount != null && emailPlatform.tagFilters.majorDonorAmount != null
        && Double.parseDouble(crmContact.totalDonationAmount) >= emailPlatform.tagFilters.majorDonorAmount) {
      tags.add("major_donor");
    }

    if (crmContact.lastDonationDate != null) {
      tags.add("donor");

      if (emailPlatform.tagFilters.recentDonorDays != null) {
        Calendar limit = Calendar.getInstance();
        limit.add(Calendar.DAY_OF_MONTH, -emailPlatform.tagFilters.recentDonorDays);
        if (crmContact.lastDonationDate.after(limit)) {
          tags.add("recent_donor");
        }
      }
    }

    if (crmContact.numDonations != null && emailPlatform.tagFilters.frequentDonorCount != null
        && Double.parseDouble(crmContact.numDonations) >= emailPlatform.tagFilters.frequentDonorCount) {
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
