package com.impactupgrade.nucleus.service.segment;

import com.ecwid.maleorang.MailchimpObject;
import com.ecwid.maleorang.method.v3_0.lists.members.MemberInfo;
import com.impactupgrade.nucleus.client.MailchimpClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.client.MailchimpClient.ADDRESS;
import static com.impactupgrade.nucleus.client.MailchimpClient.FIRST_NAME;
import static com.impactupgrade.nucleus.client.MailchimpClient.LAST_NAME;
import static com.impactupgrade.nucleus.client.MailchimpClient.PHONE_NUMBER;
import static com.impactupgrade.nucleus.client.MailchimpClient.SUBSCRIBED;
import static com.impactupgrade.nucleus.client.MailchimpClient.UNSUBSCRIBED;

public class MailchimpEmailService implements EmailService {

  private static final Logger log = LogManager.getLogger(MailchimpEmailService.class);

  protected Environment env;
  protected CrmService primaryCrmService;
  protected CrmService donationsCrmService;

  @Override
  public String name() {
    return "mailchimp";
  }

  @Override
  public void init(Environment env) {
    this.env = env;
    primaryCrmService = env.primaryCrmService();
    donationsCrmService = env.donationsCrmService();
  }

  @Override
  public void sendEmailText(String subject, String body, boolean isHtml, String to, String from) {
    // TODO
  }

  @Override
  public void sendEmailTemplate(String template, String to) {
    // TODO
  }

  @Override
  public void syncContacts(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.Mailchimp mailchimpConfig: env.getConfig().mailchimp) {
      MailchimpClient mailchimpClient = new MailchimpClient(mailchimpConfig);
      for (EnvironmentConfig.MailchimpList mcList : mailchimpConfig.lists) {
        // TODO: All this will likely end up duplicated in each impl of this service interface. Refactor?
        List<CrmContact> crmContacts = Collections.emptyList();
        switch (mcList.type) {
          case CONTACTS -> crmContacts = primaryCrmService.getEmailContacts(lastSync, mcList.crmFilter);
          case DONORS -> crmContacts = donationsCrmService.getEmailDonorContacts(lastSync, mcList.crmFilter);
        }

        List<String> crmContactIds = crmContacts.stream().map(c -> c.id).collect(Collectors.toList());
        Map<String, List<String>> contactCampaignNames = primaryCrmService.getActiveCampaignsByContactIds(crmContactIds);

        int count = 0;
        for (CrmContact crmContact : crmContacts) {
          log.info("upserting contact {} {} to list {} ({} of {})", crmContact.id, crmContact.email, mcList.id, count++, crmContacts.size());
          mailchimpClient.upsertContact(mcList.id, toMcMemberInfo(crmContact, mcList.groups));
          updateTags(mcList.id, crmContact, contactCampaignNames.get(crmContact.id), mailchimpClient, mailchimpConfig);
        }
      }
    }
  }

//  @Override
//  public Optional<CrmContact> getContactByEmail(String listName, String email) throws Exception {
//    String listId = getListIdFromName(listName);
//    return Optional.ofNullable(toCrmContact(mailchimpClient.getContactInfo(listId, email)));
//  }
//
//  @Override
//  public List<CrmContact> getListMembers(String listName) throws Exception {
//    String listId = getListIdFromName(listName);
//    return mailchimpClient.getListMembers(listId).stream().map(this::toCrmContact).collect(Collectors.toList());
//  }
//
//  @Override
//  public void unsubscribeContact(String email, String listName) throws Exception {
//    String listId = getListIdFromName(listName);
//    mailchimpClient.unsubscribeContact(listId, email);
//  }
//
//  @Override
//  public Collection<String> getContactGroupIds(String listName, CrmContact crmContact) throws Exception {
//    String listId = getListIdFromName(listName);
//    return mailchimpClient.getContactGroupIds(listId, crmContact.email);
//  }
//
//  @Override
//  public List<String> getContactTags(String listName, CrmContact crmContact) throws Exception {
//    String listId = getListIdFromName(listName);
//    return mailchimpClient.getContactTags(listId, crmContact.email);
//  }
//

  protected void updateTags(String listId, CrmContact crmContact, List<String> contactCampaignNames,
      MailchimpClient mailchimpClient, EnvironmentConfig.Mailchimp mailchimpConfig) {
    try {
      List<String> activeTags = buildContactTags(crmContact, contactCampaignNames, mailchimpConfig);
      List<String> inactiveTags = mailchimpClient.getContactTags(listId, crmContact.email);
      inactiveTags.removeAll(activeTags);

      mailchimpClient.updateContactTags(listId, crmContact.email, activeTags, inactiveTags);
    } catch (Exception e) {
      log.error("updating tags failed for contact: {} {}", crmContact.id, crmContact.email, e);
    }
  }

  // Separate method, allowing orgs to add in (or completely override) the defaults.
  // TODO: All this will likely end up duplicated in each impl of this service interface. Refactor?
  protected List<String> buildContactTags(CrmContact contact, List<String> contactCampaignNames, EnvironmentConfig.Mailchimp mailchimpConfig) throws Exception {
    List<String> tags = new ArrayList<>();

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // DONATION METRICS
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    if (contact.totalDonationAmount != null
        && Double.parseDouble(contact.totalDonationAmount) >= mailchimpConfig.tagFilters.majorDonorAmount) {
      tags.add("Major Donor");
    }

    if (contact.lastDonationDate != null) {
      tags.add("Donor");

      Calendar lastDonation = Utils.getCalendarFromDateString(contact.lastDonationDate);
      Calendar limit = Calendar.getInstance();
      limit.add(Calendar.DAY_OF_MONTH, -mailchimpConfig.tagFilters.recentDonorDays);
      if (lastDonation.after(limit)) {
        tags.add("Recent Donor");
      }
    }

    if (contact.numDonations != null
        && Double.parseDouble(contact.numDonations) >= mailchimpConfig.tagFilters.frequentDonorCount) {
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

//  protected CrmContact toCrmContact(MemberInfo member) {
//    if (member == null) {
//      return null;
//    }
//
//    CrmContact contact = new CrmContact();
//    contact.email = member.email_address;
//    contact.firstName = (String) member.merge_fields.mapping.get(FIRST_NAME);
//    contact.lastName = (String) member.merge_fields.mapping.get(LAST_NAME);
//    contact.mobilePhone = (String) member.merge_fields.mapping.get(PHONE_NUMBER);
//    contact.emailOptIn = SUBSCRIBED.equalsIgnoreCase(member.status);
//    contact.address = toCrmAddress((Map<String, Object>) member.merge_fields.mapping.get(ADDRESS));
//    // TODO
////    contact.emailGroups = getContactGroupIDs(contact.listName, contact.email);
//    return contact;
//  }
//
//  protected CrmAddress toCrmAddress(Map<String, Object> address) {
//    CrmAddress crmAddress = new CrmAddress();
//    crmAddress.country = (String) address.get("country");
//    crmAddress.street = (String) address.get("state");
//    crmAddress.city = (String) address.get("city");
//    crmAddress.street = address.get("addr1") + "\n" + address.get("addr2");
//    crmAddress.postalCode = (String) address.get("zip");
//    return crmAddress;
//  }

  protected MemberInfo toMcMemberInfo(CrmContact contact, Map<String, String> groups) {
    if (contact == null) {
      return null;
    }

    MemberInfo mcContact = new MemberInfo();
    // TODO: This isn't correct, but we'll need a way to pull the existing MC contact ID? Or maybe it's never needed,
    //  since updates use the email hash...
//    mcContact.id = contact.id;
    mcContact.email_address = contact.email;
    mcContact.merge_fields = new MailchimpObject();
    mcContact.merge_fields.mapping.put(FIRST_NAME, contact.firstName);
    mcContact.merge_fields.mapping.put(LAST_NAME, contact.lastName);
    mcContact.merge_fields.mapping.put(PHONE_NUMBER, contact.mobilePhone);
    mcContact.merge_fields.mapping.put(ADDRESS, toMcAddress(contact.address));
    mcContact.status = contact.canReceiveEmail() ? SUBSCRIBED : UNSUBSCRIBED;

    List<String> groupIds = contact.emailGroups.stream().map(groupName -> getGroupIdFromName(groupName, groups)).collect(Collectors.toList());
    // TODO: Does this deselect what's no longer subscribed to in MC?
    MailchimpObject groupMap = new MailchimpObject();
    groupIds.forEach(id -> groupMap.mapping.put(id, true));
    mcContact.interests = groupMap;

    return mcContact;
  }

  protected MailchimpObject toMcAddress(CrmAddress address) {
    MailchimpObject mcAddress = new MailchimpObject();

    mcAddress.mapping.put("country", address.country);
    mcAddress.mapping.put("state", address.state);
    mcAddress.mapping.put("city", address.city);
    // TODO: CRM street does not handle multiple address lines eg. addr1 & addr2
    mcAddress.mapping.put("addr1", address.street);
    mcAddress.mapping.put("zip", address.postalCode);

    return mcAddress;
  }
}
