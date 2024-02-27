package com.impactupgrade.nucleus.service.segment;

import com.ecwid.maleorang.MailchimpException;
import com.ecwid.maleorang.MailchimpObject;
import com.ecwid.maleorang.method.v3_0.lists.members.MemberInfo;
import com.ecwid.maleorang.method.v3_0.lists.merge_fields.MergeFieldInfo;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.MailchimpClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmAccount;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

        List<CrmContact> crmContacts = getEmailContacts(lastSync, communicationList);

        Set<String> seenEmails = crmContacts.stream().map(c -> c.email.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<CrmContact> fauxContacts = env.primaryCrmService().getEmailAccounts(lastSync, communicationList).stream()
                .filter(account -> !seenEmails.contains(account.email.toLowerCase(Locale.ROOT)))
                .map(this::asCrmContact)
                .toList();
        List<CrmContact> contacts = Stream.concat(crmContacts.stream(), fauxContacts.stream()).toList();

        syncContacts(contacts, mailchimpConfig, communicationList);
      }
    }
  }

  protected CrmContact asCrmContact(CrmAccount crmAccount) {
    CrmContact crmContact = new CrmContact();
    crmContact.account = crmAccount;
    crmContact.crmRawObject = crmAccount.crmRawObject;
    crmContact.email = crmAccount.email;
    crmContact.emailBounced = crmAccount.emailBounced;
    //TODO: crmContact.emailOptIn = ; ?
    crmContact.emailOptOut = crmAccount.emailOptOut;
    crmContact.firstName = crmAccount.name;
    crmContact.mailingAddress = crmAccount.mailingAddress;
    if (crmContact.mailingAddress == null || Strings.isNullOrEmpty(crmContact.mailingAddress.street)) {
      crmContact.mailingAddress = crmAccount.billingAddress;
    }
    // TODO
//    crmContact.firstDonationDate = ;
//    crmContact.lastDonationDate = ;
//    crmContact.largestDonationAmount = ;
//    crmContact.totalDonationAmount = ;
//    crmContact.numDonations = ;
//    crmContact.totalDonationAmountYtd = ;
//    crmContact.numDonationsYtd = ;
//    crmContact.ownerName = ;
    return crmContact;
  }

  protected void syncContacts(List<CrmContact> crmContacts, EnvironmentConfig.Mailchimp mailchimpConfig,
      EnvironmentConfig.CommunicationList communicationList) throws Exception {
    MailchimpClient mailchimpClient = new MailchimpClient(mailchimpConfig, env);

    List<CrmContact> contactsToUpsert = new ArrayList<>();
    List<CrmContact> contactsToArchive = new ArrayList<>();

    // transactional is always subscribed
    if (communicationList.type == EnvironmentConfig.CommunicationListType.TRANSACTIONAL) {
      contactsToUpsert.addAll(crmContacts);
    } else {
      crmContacts.forEach(crmContact -> (crmContact.canReceiveEmail() ? contactsToUpsert : contactsToArchive).add(crmContact));
    }

    try {
      List<MemberInfo> listMembers = mailchimpClient.getListMembers(communicationList.id);

      Map<String, Map<String, Object>> contactsCustomFields = new HashMap<>();
      for (CrmContact crmContact : contactsToUpsert) {
        Map<String, Object> customFieldMap = getCustomFields(communicationList.id, crmContact, mailchimpClient, mailchimpConfig);
        contactsCustomFields.put(crmContact.email, customFieldMap);
      }
      Map<String, List<String>> crmContactCampaignNames = getContactCampaignNames(crmContacts);
      Map<String, Set<String>> tags = mailchimpClient.getContactsTags(listMembers);
      Map<String, Set<String>> activeTags = getActiveTags(contactsToUpsert, crmContactCampaignNames, mailchimpConfig);

      // run the actual contact upserts
      List<MemberInfo> upsertMemberInfos = toMemberInfos(communicationList, contactsToUpsert, contactsCustomFields);
      String upsertBatchId = mailchimpClient.upsertContactsBatch(communicationList.id, upsertMemberInfos);
      mailchimpClient.runBatchOperations(mailchimpConfig, upsertBatchId, 0);

      // update all contacts' tags
      List<MailchimpClient.EmailContact> emailContacts = contactsToUpsert.stream()
          .map(crmContact -> new MailchimpClient.EmailContact(crmContact.email, activeTags.get(crmContact.email), tags.get(crmContact.email)))
          .collect(Collectors.toList());
      String tagsBatchId = updateTagsBatch(communicationList.id, emailContacts, mailchimpClient, mailchimpConfig);
      mailchimpClient.runBatchOperations(mailchimpConfig, tagsBatchId, 0);

      // this part's a little funky -- make sure to read the comments carefully
      // get all mc email addresses in the entire audience
      Set<String> mcEmails = listMembers.stream().map(memberInfo -> memberInfo.email_address.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
      // archive mc emails that are 1) marked as unsubscribed in the CRM
      Set<String> emailsToArchive = contactsToArchive.stream().map(crmContact -> crmContact.email).collect(Collectors.toSet());
      emailsToArchive.retainAll(mcEmails); // (but only if they actually exist in mc)
      // or 2) not in the CRM at all
      if (mailchimpConfig.enableCrmBasedArchival) {
        Set<String> crmContactsEmails = new HashSet<>();
        // get all email address in the entire CRM
        crmContacts.forEach(crmContact -> {
          crmContactsEmails.add(crmContact.email.toLowerCase(Locale.ROOT));
          if (crmContact.account != null && !Strings.isNullOrEmpty(crmContact.account.email)) {
            crmContactsEmails.add(crmContact.account.email.toLowerCase(Locale.ROOT));
          }
        });
        // remove all CRM emails from the list of MC emails, which lives us with the list that needs to be archived
        mcEmails.removeAll(crmContactsEmails);
        emailsToArchive.addAll(mcEmails);
      }

      String archiveBatchId = mailchimpClient.archiveContactsBatch(communicationList.id, emailsToArchive);
      mailchimpClient.runBatchOperations(mailchimpConfig, archiveBatchId, 0);
    } catch (MailchimpException e) {
      env.logJobWarn("Mailchimp syncContacts failed: {}", mailchimpClient.exceptionToString(e));
    } catch (Exception e) {
      env.logJobWarn("Mailchimp syncContacts failed", e);
    }
  }

  protected Map<String, Set<String>> getActiveTags(List<CrmContact> crmContacts, Map<String, List<String>> crmContactCampaignNames, EnvironmentConfig.CommunicationPlatform mailchimpConfig) throws Exception {
    Map<String, Set<String>> activeTags = new HashMap<>();
    for (CrmContact crmContact : crmContacts) {
      Set<String> tagsCleaned = getContactTagsCleaned(crmContact, crmContactCampaignNames.get(crmContact.id), mailchimpConfig);
      activeTags.put(crmContact.email, tagsCleaned);
    }
    return activeTags;
  }

  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.CommunicationPlatform mailchimpConfig : env.getConfig().mailchimp) {
      MailchimpClient mailchimpClient = new MailchimpClient(mailchimpConfig, env);
      for (EnvironmentConfig.CommunicationList communicationList : mailchimpConfig.lists) {
        List<MemberInfo> unsubscribedMembers = mailchimpClient.getListMembers(communicationList.id, "unsubscribed", lastSync);
        syncUnsubscribed(getEmails(unsubscribedMembers));

        List<MemberInfo> cleanedMembers = mailchimpClient.getListMembers(communicationList.id, "cleaned", lastSync);
        syncCleaned(getEmails(cleanedMembers));
      }
    }
  }

  protected List<String> getEmails(List<MemberInfo> memberInfos) {
    return memberInfos.stream().map(u -> u.email_address).map(String::toLowerCase).distinct().sorted().toList();
  }

  protected void syncUnsubscribed(List<String> unsubscribedEmails) throws Exception {
    updateContactsByEmails(unsubscribedEmails, c -> c.emailOptOut = true);
    updateAccountsByEmails(unsubscribedEmails, a -> a.emailOptOut = true);
  }

  protected void syncCleaned(List<String> cleanedEmails) throws Exception {
    updateContactsByEmails(cleanedEmails, c -> c.emailBounced = true);
    updateAccountsByEmails(cleanedEmails, a -> a.emailBounced = true);
  }

  protected void updateContactsByEmails(List<String> emails, Consumer<CrmContact> contactConsumer) throws Exception {
    // VITAL: In order for batching to work, must be operating under a single instance of the CrmService!
    CrmService crmService = env.primaryCrmService();
    List<CrmContact> contacts = crmService.getContactsByEmails(emails);
    int count = 0;
    int total = contacts.size();
    for (CrmContact crmContact : contacts) {
      env.logJobInfo("updating unsubscribed contact in CRM: {} ({} of {})", crmContact.email, count++, total);
      CrmContact updateContact = new CrmContact();
      updateContact.id = crmContact.id;
      contactConsumer.accept(updateContact);
      crmService.batchUpdateContact(updateContact);
    }
    crmService.batchFlush();
  }

  protected void updateAccountsByEmails(List<String> emails, Consumer<CrmAccount> accountConsumer) throws Exception {
    // VITAL: In order for batching to work, must be operating under a single instance of the CrmService!
    CrmService crmService = env.primaryCrmService();
    List<CrmAccount> accounts = crmService.getAccountsByEmails(emails);
    int count = 0;
    int total = accounts.size();
    for (CrmAccount account : accounts) {
      env.logJobInfo("updating unsubscribed account in CRM: {} ({} of {})", account.email, count++, total);
      CrmAccount updateAccount = new CrmAccount();
      updateAccount.id = account.id;
      accountConsumer.accept(updateAccount);
      crmService.batchUpdateAccount(updateAccount);
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
          syncContacts(List.of(crmContact.get()), mailchimpConfig, communicationList);
        }
      }
    }
  }

  protected Map<String, Object> getCustomFields(String listId, CrmContact crmContact, MailchimpClient mailchimpClient,
      EnvironmentConfig.CommunicationPlatform mailchimpConfig) throws Exception {
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
      MailchimpClient mailchimpClient, EnvironmentConfig.CommunicationPlatform mailchimpConfig) {

    emailContacts.stream()
            .filter(emailContact -> CollectionUtils.isNotEmpty(emailContact.inactiveTags()))
            .forEach(emailContact -> {
              emailContact.inactiveTags().removeAll(emailContact.activeTags());
              emailContact.inactiveTags().removeAll(mailchimpConfig.tagsToPreserve);
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
