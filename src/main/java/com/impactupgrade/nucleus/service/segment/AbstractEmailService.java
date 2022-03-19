package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.Utils;

import java.util.ArrayList;
import java.util.Calendar;
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

  // Separate method, allowing orgs to add in (or completely override) the defaults.
  protected List<String> buildContactTags(CrmContact contact, List<String> contactCampaignNames, EnvironmentConfig.EmailPlatform emailPlatform) throws Exception {
    List<String> tags = new ArrayList<>();

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // DONATION METRICS
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    if (contact.totalDonationAmount != null
        && Double.parseDouble(contact.totalDonationAmount) >= emailPlatform.tagFilters.majorDonorAmount) {
      tags.add("Major Donor");
    }

    if (contact.lastDonationDate != null) {
      tags.add("Donor");

      Calendar lastDonation = Utils.getCalendarFromDateString(contact.lastDonationDate);
      Calendar limit = Calendar.getInstance();
      limit.add(Calendar.DAY_OF_MONTH, -emailPlatform.tagFilters.recentDonorDays);
      if (lastDonation.after(limit)) {
        tags.add("Recent Donor");
      }
    }

    if (contact.numDonations != null
        && Double.parseDouble(contact.numDonations) >= emailPlatform.tagFilters.frequentDonorCount) {
      tags.add("Frequent Donor");
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

    if (contact.ownerName != null) {
      tags.add("Owner: " + contact.ownerName);
    }

    if (contactCampaignNames != null) {
      for (String c : contactCampaignNames) {
        tags.add("Campaign Member: " + c);
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
