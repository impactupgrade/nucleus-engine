/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.ecwid.maleorang.MailchimpException;
import com.ecwid.maleorang.MailchimpObject;
import com.ecwid.maleorang.method.v3_0.lists.members.MemberInfo;
import com.ecwid.maleorang.method.v3_0.lists.merge_fields.MergeFieldInfo;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.MailchimpClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import org.apache.commons.collections.CollectionUtils;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.impactupgrade.nucleus.client.MailchimpClient.FIRST_NAME;
import static com.impactupgrade.nucleus.client.MailchimpClient.LAST_NAME;
import static com.impactupgrade.nucleus.client.MailchimpClient.PHONE_NUMBER;
import static com.impactupgrade.nucleus.client.MailchimpClient.SMS_PHONE_NUMBER;
import static com.impactupgrade.nucleus.client.MailchimpClient.SUBSCRIBED;

public class MailchimpCommunicationService extends AbstractCommunicationService {

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
  protected List<EnvironmentConfig.CommunicationPlatform> getPlatformConfigs() {
    return env.getConfig().mailchimp;
  }

  @Override
  protected ExistingContacts getExistingContacts(EnvironmentConfig.CommunicationPlatform config,
      EnvironmentConfig.CommunicationList list) {
    MailchimpClient mailchimpClient = env.mailchimpClient(config);
    ExistingContacts existingContacts = new ExistingContacts();

    try {
      List<MemberInfo> listMembers = mailchimpClient.getListMembers(list.id);
      Map<String, Set<String>> contactsTags = mailchimpClient.getContactsTags(listMembers);

      for (MemberInfo memberInfo : listMembers) {
        String email = memberInfo.email_address.toLowerCase(Locale.ROOT);
        existingContacts.emailsToIds.put(email, email);
        existingContacts.emailsToTags.put(email, contactsTags.getOrDefault(email, new HashSet<>()));
      }
    } catch (Exception e) {
      env.logJobError("Failed to get existing contacts from Mailchimp: {}", e.getMessage());
    }

    return existingContacts;
  }

  @Override
  protected void executeBatchUpsert(List<CrmContact> contacts,
      Map<String, Map<String, Object>> customFields, Map<String, Set<String>> tags,
      ExistingContacts existingContacts, EnvironmentConfig.CommunicationPlatform config,
      EnvironmentConfig.CommunicationList list) throws Exception {
    MailchimpClient mailchimpClient = env.mailchimpClient(config);

    try {
      // Run the actual contact upserts
      List<MemberInfo> upsertMemberInfos = toMcMemberInfos(list, config, contacts, customFields);
      String upsertBatchId = mailchimpClient.upsertContactsBatch(list.id, upsertMemberInfos);
      mailchimpClient.runBatchOperations(config, upsertBatchId, 0);

      // Update all contacts' tags
      List<MailchimpClient.TaggedContact> taggedContacts = contacts.stream()
          .map(crmContact -> {
            String email = crmContact.email.toLowerCase(Locale.ROOT);
            Set<String> activeTags = tags.get(email);
            Set<String> existingTags = existingContacts.emailsToTags.getOrDefault(email, new HashSet<>());
            return new MailchimpClient.TaggedContact(crmContact.email, activeTags, existingTags);
          })
          .collect(Collectors.toList());
      String tagsBatchId = updateTagsBatch(list.id, taggedContacts, mailchimpClient, config);
      mailchimpClient.runBatchOperations(config, tagsBatchId, 0);
    } catch (MailchimpException e) {
      env.logJobWarn("Mailchimp executeBatchUpsert failed: {}", mailchimpClient.exceptionToString(e));
      throw e;
    }
  }

  // Originally, we simply called syncContacts/executeBatchUpsert and made use of existing functions. But that unfortunately
  //  does things like download ALL contacts from the audiences. Instead, we copy and paste the process here,
  //  stripping out the full sync and only pushing in the contact's new tags. We skip removing old tags --
  //  instead, let the nightly job do that for everybody. This process is typically only needed when something
  //  needs added, like a campaign tag to kick off a Journey in MC itself.
  @Override
  protected void executeUpsert(CrmContact contact, Map<String, Object> customFields, Set<String> tags,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    MailchimpClient mailchimpClient = env.mailchimpClient(config);

    try {
      // run the actual contact upserts
      MemberInfo upsertMemberInfo = toMcMemberInfo(config, contact, customFields, list.groups);
      mailchimpClient.upsertContact(list.id, upsertMemberInfo);

      // update all contacts' tags
      MailchimpClient.TaggedContact taggedContact = new MailchimpClient.TaggedContact(contact.email, tags, Set.of());
      mailchimpClient.updateContactTags(list.id, taggedContact);
    } catch (MailchimpException e) {
      env.logJobWarn("Mailchimp upsertContact failed: {}", mailchimpClient.exceptionToString(e));
    } catch (Exception e) {
      env.logJobWarn("Mailchimp upsertContact failed", e);
    }
  }

  @Override
  protected void executeBatchArchive(Set<String> emails,
      ExistingContacts existingContacts, EnvironmentConfig.CommunicationPlatform config,
      EnvironmentConfig.CommunicationList list) throws Exception {
    MailchimpClient mailchimpClient = env.mailchimpClient(config);
    String archiveBatchId = mailchimpClient.archiveContactsBatch(list.id, emails);
    mailchimpClient.runBatchOperations(config, archiveBatchId, 0);
  }

  @Override
  protected Set<String> getUnsubscribedEmails(Calendar lastSync,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    MailchimpClient mailchimpClient = env.mailchimpClient(config);
    List<MemberInfo> unsubscribedMembers = mailchimpClient.getListMembers(list.id, "unsubscribed", lastSync);
    return getEmails(unsubscribedMembers);
  }

  @Override
  protected Set<String> getBouncedEmails(Calendar lastSync,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    MailchimpClient mailchimpClient = env.mailchimpClient(config);
    List<MemberInfo> cleanedMembers = mailchimpClient.getListMembers(list.id, "cleaned", lastSync);
    return getEmails(cleanedMembers);
  }

  @Override
  protected Map<String, Object> buildPlatformCustomFields(CrmContact crmContact,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    MailchimpClient mailchimpClient = env.mailchimpClient(config);

    Map<String, Object> customFieldMap = new HashMap<>();

    List<CustomField> customFields = buildContactCustomFields(crmContact, config, list);
    if (mergeFieldsNameToTag.isEmpty()) {
      List<MergeFieldInfo> mergeFields = mailchimpClient.getMergeFields(list.id);
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
        MergeFieldInfo.Type type = switch (customField.type) {
          case DATE -> MergeFieldInfo.Type.DATE;
          // MC doesn't support a boolean type, so we use NUMBER and map to 0/1
          case BOOLEAN -> MergeFieldInfo.Type.NUMBER;
          case NUMBER -> MergeFieldInfo.Type.NUMBER;
          case CURRENCY -> MergeFieldInfo.Type.NUMBER;
          default -> MergeFieldInfo.Type.TEXT;
        };
        MergeFieldInfo mergeField = mailchimpClient.createMergeField(list.id, customField.name, type);
        mergeFieldsNameToTag.put(mergeField.name, mergeField.tag);
      }

      Object value = customField.value;
      if (customField.type == CustomFieldType.BOOLEAN) {
        value = ((Boolean) value) ? 1 : 0;
      } else if (customField.type == CustomFieldType.DATE) {
        Calendar c = (Calendar) value;
        value = new SimpleDateFormat("MM/dd/yyyy").format(c.getTime());
      }

      String mailchimpTag = mergeFieldsNameToTag.get(customField.name);
      customFieldMap.put(mailchimpTag, value);
    }

    return customFieldMap;
  }

  @Override
  protected void prepareBatchProcessing(EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) {
    // Clear the cache since fields differ between audiences - done once per communication list
    mergeFieldsNameToTag.clear();
  }

  protected Set<String> getEmails(List<MemberInfo> memberInfos) {
    return memberInfos.stream().map(u -> u.email_address.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
  }

  protected String updateTagsBatch(String listId, List<MailchimpClient.TaggedContact> taggedContacts,
      MailchimpClient mailchimpClient, EnvironmentConfig.CommunicationPlatform mailchimpConfig) {

    // filter down the tags-to-remove to only the ones we actually have control over
    taggedContacts.stream()
            .filter(taggedContact -> CollectionUtils.isNotEmpty(taggedContact.inactiveTags()))
            .forEach(taggedContact -> {
              taggedContact.inactiveTags().removeAll(taggedContact.activeTags());
              taggedContact.inactiveTags().removeIf(t -> {
                List<String> controlledTags = Stream.concat(
                    mailchimpConfig.defaultControlledTags.stream(), mailchimpConfig.customControlledTags.stream()
                ).toList();
                boolean controlled = false;
                for (String controlledTag : controlledTags) {
                  if (t.contains(controlledTag)) {
                    controlled = true;
                    break;
                  }
                }
                // backwards -- we're taking the whole list of tags we could potentially remove, seeing if any of them
                // match our controlledList, and removing any that are NOT controlled by Nucleus
                return !controlled;
              });
            });

    try {
      return mailchimpClient.updateContactTagsBatch(listId, taggedContacts);
    } catch (Exception e) {
      env.logJobError("updating tags failed for contacts! {}", e.getMessage());
      return null;
    }
  }

  protected List<MemberInfo> toMcMemberInfos(EnvironmentConfig.CommunicationList communicationList, EnvironmentConfig.CommunicationPlatform mailchimpConfig, List<CrmContact> crmContacts,
      Map<String, Map<String, Object>> customFieldsMap) {
    return crmContacts.stream()
        .map(crmContact -> toMcMemberInfo(mailchimpConfig, crmContact, customFieldsMap.get(crmContact.email), communicationList.groups))
        .collect(Collectors.toList());
  }

  protected MemberInfo toMcMemberInfo(EnvironmentConfig.CommunicationPlatform mailchimpConfig, CrmContact crmContact, Map<String, Object> customFields, Map<String, String> groups) {
    if (crmContact == null) {
      return null;
    }

    MemberInfo mcContact = new MemberInfo();
    mcContact.email_address = crmContact.email;
    mcContact.merge_fields = new MailchimpObject();
    mcContact.merge_fields.mapping.put(FIRST_NAME, crmContact.firstName);
    mcContact.merge_fields.mapping.put(LAST_NAME, crmContact.lastName);
    mcContact.merge_fields.mapping.put(PHONE_NUMBER, crmContact.mobilePhone);

    if (smsAllowed(mailchimpConfig, crmContact)) {
      mcContact.consents_to_one_to_one_messaging = true;
      mcContact.sms_subscription_status = SUBSCRIBED;
      String phoneNumber = crmContact.phoneNumberForSMS();
      mcContact.sms_phone_number = phoneNumber;
      mcContact.merge_fields.mapping.put(SMS_PHONE_NUMBER, phoneNumber);
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

  private boolean smsAllowed(EnvironmentConfig.CommunicationPlatform mailchimpConfig, CrmContact crmContact) {
    if (!mailchimpConfig.enableSms) {
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
    if (!Strings.isNullOrEmpty(mailchimpConfig.countryCode) && phoneNumber.startsWith(mailchimpConfig.countryCode)) {
      smsAllowed = true;
    } else if (!Strings.isNullOrEmpty(mailchimpConfig.country) && !phoneNumber.startsWith("+")) {
      smsAllowed = Stream.of(crmContact.account.billingAddress, crmContact.account.mailingAddress, crmContact.mailingAddress)
          .filter(Objects::nonNull)
          .anyMatch(crmAddress -> mailchimpConfig.country.equalsIgnoreCase(crmAddress.country));
    }
    return smsAllowed;
  }
}
