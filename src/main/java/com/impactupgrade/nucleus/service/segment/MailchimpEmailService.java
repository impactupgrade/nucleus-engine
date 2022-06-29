package com.impactupgrade.nucleus.service.segment;

import com.ecwid.maleorang.MailchimpObject;
import com.ecwid.maleorang.method.v3_0.lists.members.MemberInfo;
import com.ecwid.maleorang.method.v3_0.lists.merge_fields.MergeFieldInfo;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.MailchimpClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.client.MailchimpClient.ADDRESS;
import static com.impactupgrade.nucleus.client.MailchimpClient.FIRST_NAME;
import static com.impactupgrade.nucleus.client.MailchimpClient.LAST_NAME;
import static com.impactupgrade.nucleus.client.MailchimpClient.PHONE_NUMBER;
import static com.impactupgrade.nucleus.client.MailchimpClient.SUBSCRIBED;

public class MailchimpEmailService extends AbstractEmailService {

  private static final Logger log = LogManager.getLogger(MailchimpEmailService.class);

  private final Map<String, String> mergeFieldsNameToTag = new HashMap<>();

  @Override
  public String name() {
    return "mailchimp";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().mailchimp != null && !env.getConfig().mailchimp.isEmpty();
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
    for (EnvironmentConfig.EmailPlatform mailchimpConfig : env.getConfig().mailchimp) {
      MailchimpClient mailchimpClient = new MailchimpClient(mailchimpConfig);
      for (EnvironmentConfig.EmailList emailList : mailchimpConfig.lists) {
        // clear the cache, since fields differ between audiences
        mergeFieldsNameToTag.clear();

        List<CrmContact> crmContacts = getCrmContacts(emailList, lastSync);
        Map<String, List<String>> crmContactCampaignNames = getContactCampaignNames(crmContacts);

        int count = 0;
        for (CrmContact crmContact : crmContacts) {
          // transactional is always subscribed
          if (emailList.type == EnvironmentConfig.EmailListType.TRANSACTIONAL || crmContact.canReceiveEmail()) {
            log.info("upserting contact {} {} to list {} ({} of {})", crmContact.id, crmContact.email, emailList.id, count++, crmContacts.size());
            Map<String, Object> customFields = getCustomFields(emailList.id, crmContact, mailchimpClient, mailchimpConfig);
            mailchimpClient.upsertContact(emailList.id, toMcMemberInfo(crmContact, customFields, emailList.groups));
            // if they can't, they're archived, and will be failed to be retrieved for update
            updateTags(emailList.id, crmContact, crmContactCampaignNames.get(crmContact.id), mailchimpClient, mailchimpConfig);
          } else if (!crmContact.canReceiveEmail()) {
            log.info("unsubscribing contact {} {} from list {} ({} of {})", crmContact.id, crmContact.email, emailList.id, count++, crmContacts.size());
            mailchimpClient.archiveContact(emailList.id, crmContact.email);
          }
        }
      }
    }
  }

  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.EmailPlatform mailchimpConfig : env.getConfig().mailchimp) {
      MailchimpClient mailchimpClient = new MailchimpClient(mailchimpConfig);
      for (EnvironmentConfig.EmailList emailList : mailchimpConfig.lists) {
        syncUnsubscribes(mailchimpClient.getListMembers(emailList.id, "unsubscribed"));
        syncUnsubscribes(mailchimpClient.getListMembers(emailList.id, "cleaned"));
      }
    }
  }

  // TODO: Purely allowing this to unsubscribe in the CRM, as opposed to archiving immediately in MC. Let organizations
  //  decide if their unsubscribe-from-CRM code does an archive...
  private void syncUnsubscribes(List<MemberInfo> unsubscribes) throws Exception {
    // VITAL: In order for batching to work, must be operating under a single instance of the CrmService!
    CrmService crmService = env.primaryCrmService();

    int count = 0;
    for (MemberInfo unsubscribe : unsubscribes) {
      Optional<CrmContact> crmContact = crmService.searchContacts(ContactSearch.byEmail(unsubscribe.email_address)).getSingleResult();
      if (crmContact.isPresent()) {
        log.info("updating unsubscribed contact in CRM: {} ({} of {})", crmContact.get().email, count++, unsubscribes.size());
        CrmContact updateContact = new CrmContact();
        updateContact.id = crmContact.get().id;
        updateContact.emailOptOut = true;
        crmService.batchUpdateContact(updateContact);
      }
    }
    crmService.batchFlush();
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

  protected Map<String, Object> getCustomFields(String listId, CrmContact crmContact, MailchimpClient mailchimpClient,
      EnvironmentConfig.EmailPlatform mailchimpConfig) throws Exception {
    Map<String, Object> customFieldMap = new HashMap<>();

    List<CustomField> customFields = buildContactCustomFields(crmContact, mailchimpConfig);
    if (mergeFieldsNameToTag.isEmpty()) {
      List<MergeFieldInfo> mergeFields = mailchimpClient.getMergeFields(listId);
      for (MergeFieldInfo mergeField : mergeFields) {
        mergeFieldsNameToTag.put(mergeField.name, mergeField.tag);
      }
    }
    for (CustomField customField : customFields) {
      if (customField.value == null) {
        continue;
      }

      if (!mergeFieldsNameToTag.containsKey(customField.name)) {
        // TEXT, NUMBER, ADDRESS, PHONE, DATE, URL, IMAGEURL, RADIO, DROPDOWN, BIRTHDAY, ZIP
        MergeFieldInfo.Type type = switch(customField.type) {
          case DATE -> MergeFieldInfo.Type.DATE;
          // MC doesn't support a boolean type, so we use NUMBER and map to 0/1
          case BOOLEAN -> MergeFieldInfo.Type.NUMBER;
          case NUMBER -> MergeFieldInfo.Type.NUMBER;
          default -> MergeFieldInfo.Type.TEXT;
        };
        MergeFieldInfo mergeField = mailchimpClient.createMergeField(listId, customField.name, type);
        mergeFieldsNameToTag.put(mergeField.name, mergeField.tag);
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

      String mailchimpTag = mergeFieldsNameToTag.get(customField.name);
      customFieldMap.put(mailchimpTag, value);
    }

    return customFieldMap;
  }

  protected void updateTags(String listId, CrmContact crmContact, List<String> crmContactCampaignNames,
      MailchimpClient mailchimpClient, EnvironmentConfig.EmailPlatform mailchimpConfig) {
    try {
      List<String> activeTags = getContactTagsCleaned(crmContact, crmContactCampaignNames, mailchimpConfig);
      List<String> inactiveTags = mailchimpClient.getContactTags(listId, crmContact.email);
      inactiveTags.removeAll(activeTags);

      mailchimpClient.updateContactTags(listId, crmContact.email, activeTags, inactiveTags);
    } catch (Exception e) {
      log.error("updating tags failed for contact: {} {}", crmContact.id, crmContact.email, e);
    }
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

  protected MemberInfo toMcMemberInfo(CrmContact crmContact, Map<String, Object> customFields, Map<String, String> groups) {
    if (crmContact == null) {
      return null;
    }

    MemberInfo mcContact = new MemberInfo();
    // TODO: This isn't correct, but we'll need a way to pull the existing MC contact ID? Or maybe it's never needed,
    //  since updates use the email hash...
//    mcContact.id = contact.id;
    mcContact.email_address = crmContact.email;
    mcContact.merge_fields = new MailchimpObject();
    mcContact.merge_fields.mapping.put(FIRST_NAME, crmContact.firstName);
    mcContact.merge_fields.mapping.put(LAST_NAME, crmContact.lastName);
    mcContact.merge_fields.mapping.put(PHONE_NUMBER, crmContact.mobilePhone);
    // MC only allows "complete" addresses, so only fill this out if that's the case. As a backup, we're creating
    // separate, custom fields for city/state/zip/country.
    if (!Strings.isNullOrEmpty(crmContact.address.street)) {
      mcContact.merge_fields.mapping.put(ADDRESS, toMcAddress(crmContact.address));
    }
    mcContact.merge_fields.mapping.putAll(customFields);
    mcContact.status = SUBSCRIBED;

    List<String> groupIds = crmContact.emailGroups.stream().map(groupName -> getGroupIdFromName(groupName, groups)).collect(Collectors.toList());
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
