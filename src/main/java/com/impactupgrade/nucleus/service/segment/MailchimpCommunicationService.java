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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.client.MailchimpClient.FIRST_NAME;
import static com.impactupgrade.nucleus.client.MailchimpClient.LAST_NAME;
import static com.impactupgrade.nucleus.client.MailchimpClient.PHONE_NUMBER;
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
  public void syncContacts(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.Mailchimp mailchimpConfig : env.getConfig().mailchimp) {
      for (EnvironmentConfig.CommunicationList communicationList : mailchimpConfig.lists) {
        // clear the cache, since fields differ between audiences
        mergeFieldsNameToTag.clear();

        Map<String, CrmContact> crmContacts = getEmailContacts(lastSync, communicationList);
        syncContacts(crmContacts, mailchimpConfig, communicationList);
      }
    }
  }

  protected void syncContacts(Map<String, CrmContact> crmContacts, EnvironmentConfig.Mailchimp mailchimpConfig,
      EnvironmentConfig.CommunicationList communicationList) throws Exception {
    MailchimpClient mailchimpClient = new MailchimpClient(mailchimpConfig, env);

    // VITAL: In order for batching to work, must be operating under a single instance of the CrmService!
    CrmService crmService = env.primaryCrmService();

    List<CrmContact> contactsToUpsert = new ArrayList<>();
    List<CrmContact> contactsToArchive = new ArrayList<>();

    // transactional is always subscribed
    if (communicationList.type == EnvironmentConfig.CommunicationListType.TRANSACTIONAL) {
      contactsToUpsert.addAll(crmContacts.values());
    } else {
      crmContacts.values().forEach(crmContact -> (crmContact.canReceiveEmail() ? contactsToUpsert : contactsToArchive).add(crmContact));
    }

    try {
      List<MemberInfo> listMembers = mailchimpClient.getListMembers(mailchimpConfig, communicationList);

      Map<String, Map<String, Object>> contactsCustomFields = new HashMap<>();
      for (CrmContact crmContact : contactsToUpsert) {
        Map<String, Object> customFieldMap = getCustomFields(communicationList.id, crmContact, mailchimpClient, mailchimpConfig);
        contactsCustomFields.put(crmContact.email, customFieldMap);
      }

      Map<String, List<String>> crmContactCampaignNames = getContactCampaignNames(crmContacts.values());
      Map<String, Set<String>> tags = mailchimpClient.getContactsTags(listMembers);
      Map<String, Set<String>> activeTags = getActiveTags(contactsToUpsert, crmContactCampaignNames, mailchimpConfig);

      List<MemberInfo> upsertMemberInfos = toMemberInfos(communicationList, contactsToUpsert, contactsCustomFields);

      // run the actual contact upserts
      String upsertBatchId = mailchimpClient.upsertContactsBatch(communicationList.id, upsertMemberInfos);
      mailchimpClient.runBatchOperations(mailchimpConfig, upsertBatchId, 0);

      // update all contacts' tags
      List<MailchimpClient.EmailContact> emailContacts = contactsToUpsert.stream()
          .map(crmContact -> new MailchimpClient.EmailContact(crmContact.email, activeTags.get(crmContact.email), tags.get(crmContact.email)))
          .collect(Collectors.toList());
      String tagsBatchId = updateTagsBatch(communicationList.id, emailContacts, mailchimpClient, mailchimpConfig);
      mailchimpClient.runBatchOperations(mailchimpConfig, tagsBatchId, 0);

      // archive mc emails that are 1) marked as unsubscribed in the CRM or 2) not in the CRM at all
      Set<String> crmContactsEmails = new HashSet<>();
      crmContacts.values().forEach(crmContact -> {
        crmContactsEmails.add(crmContact.email.toLowerCase(Locale.ROOT));
        if (crmContact.account != null && !Strings.isNullOrEmpty(crmContact.account.email)) {
          crmContactsEmails.add(crmContact.account.email.toLowerCase(Locale.ROOT));
        }
      });
      Set<String> mcEmailsToArchive = listMembers.stream().map(memberInfo -> memberInfo.email_address.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
      mcEmailsToArchive.removeAll(crmContactsEmails);
      List<String> emailsToArchive = new ArrayList<>();
      emailsToArchive.addAll(contactsToArchive.stream().map(crmContact -> crmContact.email).toList());
      emailsToArchive.addAll(mcEmailsToArchive);
      String archiveBatchId = mailchimpClient.archiveContactsBatch(communicationList.id, emailsToArchive);
      mailchimpClient.runBatchOperations(mailchimpConfig, archiveBatchId, 0);

      // sync MC's predictive demographic fields back into the CRM
      if (!mailchimpConfig.fieldsToSyncToCrm.isEmpty()) {
        for (MemberInfo listMember : listMembers) {
          CrmContact crmContact = crmContacts.get(listMember.email_address.toLowerCase(Locale.ROOT));
          CrmContact crmContactUpsert = new CrmContact();
          crmContactUpsert.id = crmContact.id;
          for (String key : mailchimpConfig.fieldsToSyncToCrm.keySet()) {
            // TODO: predictive demographics are not in the merge fields -- any other way to get them from the API,
            //  or do we have to create segments and pull from there?
            crmContactUpsert.crmRawFieldsToSet.put(
                mailchimpConfig.fieldsToSyncToCrm.get(key),
                (String) listMember.merge_fields.mapping.get(key)
            );
          }
          crmService.batchUpdateContact(crmContactUpsert);
        }
        crmService.batchFlush();
      }
    } catch (
  MailchimpException e) {
      env.logJobWarn("Mailchimp syncContacts failed: {}", mailchimpClient.exceptionToString(e));
    } catch (Exception e) {
      env.logJobWarn("Mailchimp syncContacts failed", e);
    }
  }

  protected Map<String, Set<String>> getActiveTags(List<CrmContact> crmContacts, Map<String, List<String>> crmContactCampaignNames, EnvironmentConfig.Mailchimp mailchimpConfig) throws Exception {
    Map<String, Set<String>> activeTags = new HashMap<>();
    for (CrmContact crmContact : crmContacts) {
      Set<String> tagsCleaned = getContactTagsCleaned(crmContact, crmContactCampaignNames.get(crmContact.id), mailchimpConfig);
      activeTags.put(crmContact.email, tagsCleaned);
    }
    return activeTags;
  }

  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.Mailchimp mailchimpConfig : env.getConfig().mailchimp) {
      MailchimpClient mailchimpClient = new MailchimpClient(mailchimpConfig, env);
      for (EnvironmentConfig.CommunicationList communicationList : mailchimpConfig.lists) {
        syncUnsubscribes(mailchimpClient.getListMembers(mailchimpConfig, communicationList, "unsubscribed", lastSync), c -> c.emailOptOut = true);
        syncUnsubscribes(mailchimpClient.getListMembers(mailchimpConfig, communicationList, "cleaned", lastSync), c -> c.emailBounced = true);
      }
    }
  }

  protected void syncUnsubscribes(List<MemberInfo> unsubscribes, Consumer<CrmContact> consumer) throws Exception {
    // VITAL: In order for batching to work, must be operating under a single instance of the CrmService!
    CrmService crmService = env.primaryCrmService();

    List<String> unsubscribeEmails = unsubscribes.stream().map(u -> u.email_address).filter(Objects::nonNull).distinct().sorted().toList();
    if (unsubscribeEmails.isEmpty()) {
      return;
    }
    List<CrmContact> unsubscribeContacts = crmService.getContactsByEmails(unsubscribeEmails);

    int count = 0;
    int total = unsubscribeContacts.size();
    for (CrmContact crmContact : unsubscribeContacts) {
      env.logJobInfo("updating unsubscribed contact in CRM: {} ({} of {})", crmContact.email, count++, total);
      CrmContact updateContact = new CrmContact();
      updateContact.id = crmContact.id;
      consumer.accept(updateContact);
      crmService.batchUpdateContact(updateContact);
    }
    crmService.batchFlush();
  }

  @Override
  public void upsertContact(String contactId) throws Exception {
    CrmService crmService = env.primaryCrmService();

    for (EnvironmentConfig.Mailchimp mailchimpConfig : env.getConfig().mailchimp) {
      for (EnvironmentConfig.CommunicationList communicationList : mailchimpConfig.lists) {
        // clear the cache, since fields differ between audiences
        mergeFieldsNameToTag.clear();

        Optional<CrmContact> crmContact = crmService.getFilteredContactById(contactId, communicationList.crmFilter);
        if (crmContact.isPresent() && !Strings.isNullOrEmpty(crmContact.get().email)) {
          syncContacts(Map.of(crmContact.get().email, crmContact.get()), mailchimpConfig, communicationList);
        }
      }
    }
  }

  protected Map<String, Object> getCustomFields(String listId, CrmContact crmContact, MailchimpClient mailchimpClient,
      EnvironmentConfig.Mailchimp mailchimpConfig) throws Exception {
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
        MergeFieldInfo.Type type = switch (customField.type) {
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

  protected String updateTagsBatch(String listId, List<MailchimpClient.EmailContact> emailContacts,
      MailchimpClient mailchimpClient, EnvironmentConfig.Mailchimp mailchimpConfig) {

    emailContacts.stream()
            .filter(emailContact -> CollectionUtils.isNotEmpty(emailContact.inactiveTags()))
            .forEach(emailContact -> {
              emailContact.inactiveTags().removeAll(emailContact.activeTags());
              emailContact.inactiveTags().removeAll(mailchimpConfig.contactTagFilters);
            });
    try {
      return mailchimpClient.updateContactTagsBatch(listId, emailContacts);
    } catch (Exception e) {
      env.logJobError("updating tags failed for contacts! {}", e.getMessage());
      return null;
    }
  }

  protected List<MemberInfo> toMemberInfos(EnvironmentConfig.CommunicationList communicationList, List<CrmContact> crmContacts,
      Map<String, Map<String, Object>> customFieldsMap) {
    return crmContacts.stream()
        .map(crmContact -> toMcMemberInfo(crmContact, customFieldsMap.get(crmContact.email), communicationList.groups))
        .collect(Collectors.toList());
  }

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
    mcContact.merge_fields.mapping.putAll(customFields);
    mcContact.status = SUBSCRIBED;

    List<String> groupIds = crmContact.emailGroups.stream().map(groupName -> getGroupIdFromName(groupName, groups)).collect(Collectors.toList());
    // TODO: Does this deselect what's no longer subscribed to in MC?
    MailchimpObject groupMap = new MailchimpObject();
    groupIds.forEach(id -> groupMap.mapping.put(id, true));
    mcContact.interests = groupMap;

    return mcContact;
  }
}
