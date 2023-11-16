package com.impactupgrade.nucleus.service.segment;

import com.ecwid.maleorang.MailchimpException;
import com.ecwid.maleorang.MailchimpObject;
import com.ecwid.maleorang.method.v3_0.batches.BatchStatus;
import com.ecwid.maleorang.method.v3_0.lists.members.MemberInfo;
import com.ecwid.maleorang.method.v3_0.lists.merge_fields.MergeFieldInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.MailchimpClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

  // TODO: For now, letting a single, massive batch run.
//  private static final Integer BATCH_REQUEST_OPERATIONS_SIZE = 500;

  // 2 hours
  private static final Integer BATCH_STATUS_RETRY_TIMEOUT_IN_SECONDS = 300;
  private static final Integer BATCH_STATUS_MAX_RETRIES = 24;

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
    for (EnvironmentConfig.CommunicationPlatform mailchimpConfig : env.getConfig().mailchimp) {
      for (EnvironmentConfig.CommunicationList communicationList : mailchimpConfig.lists) {
        // clear the cache, since fields differ between audiences
        mergeFieldsNameToTag.clear();

        List<CrmContact> crmContacts = getEmailContacts(lastSync, communicationList);
        Map<String, List<String>> crmContactCampaignNames = getContactCampaignNames(crmContacts);

//        List<List<CrmContact>> partitions = Lists.partition(crmContacts, BATCH_REQUEST_OPERATIONS_SIZE);
//        int i = 1;
//        for (List<CrmContact> contactsBatch : partitions) {
//          env.logJobInfo("Processing contacts batch {} of total {}...", i, partitions.size());
//          syncContacts(contactsBatch, crmContactCampaignNames, mailchimpConfig, communicationList);
//          i++;
//        }
        syncContacts(crmContacts, crmContactCampaignNames, mailchimpConfig, communicationList);
      }
    }
  }

  protected void syncContacts(List<CrmContact> crmContacts, Map<String, List<String>> crmContactCampaignNames,
      EnvironmentConfig.CommunicationPlatform mailchimpConfig, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    MailchimpClient mailchimpClient = new MailchimpClient(mailchimpConfig, env);

    List<CrmContact> contactsToUpsert = new ArrayList<>();
    List<CrmContact> contactsToArchive = new ArrayList<>();

    // transactional is always subscribed
    if (communicationList.type == EnvironmentConfig.CommunicationListType.TRANSACTIONAL) {
      contactsToUpsert.addAll(crmContacts);
    } else {
      crmContacts.forEach(crmContact -> (crmContact.canReceiveEmail() ? contactsToUpsert : contactsToArchive).add(crmContact));
    }

    List<MemberInfo> listMembers = mailchimpClient.getListMembers(communicationList.id);
    List<String> membersEmails = listMembers.stream().map(memberInfo -> memberInfo.email_address).collect(Collectors.toList());
    List<String> crmContactsEmails = new ArrayList<>();
    crmContacts.stream().forEach(crmContact -> {
          crmContactsEmails.add(crmContact.email);
          if (crmContact.account != null) {
            crmContactsEmails.add(crmContact.account.email);
          }
        });

    // archive mc emails that are not in CRM
    List<String> mcEmailsToArchive = membersEmails.stream()
        .filter(email -> !crmContactsEmails.contains(email))
        .collect(Collectors.toList());

    try {
      Map<String, Map<String, Object>> contactsCustomFields = new HashMap<>();
      for (CrmContact crmContact : contactsToUpsert) {
        Map<String, Object> customFieldMap = getCustomFields(communicationList.id, crmContact, mailchimpClient, mailchimpConfig);
        contactsCustomFields.put(crmContact.email, customFieldMap);
      }

      List<MemberInfo> memberInfos = toMemberInfos(communicationList, contactsToUpsert, contactsCustomFields);

      String upsertBatchId = mailchimpClient.upsertContactsBatch(communicationList.id, memberInfos);
      // batch processing results synchronously to make sure
      // all contacts were processed before updating tags
      runBatchOperations(mailchimpClient, mailchimpConfig, upsertBatchId, 0);

      Map<String, Set<String>> tags = mailchimpClient.getContactsTags(communicationList.id);
      Map<String, Set<String>> activeTags = getActiveTags(contactsToUpsert, crmContactCampaignNames, mailchimpConfig);
      List<MailchimpClient.EmailContact> emailContacts = contactsToUpsert.stream()
          .map(crmContact -> new MailchimpClient.EmailContact(crmContact.email, activeTags.get(crmContact.email), tags.get(crmContact.email)))
          .collect(Collectors.toList());

      updateTagsBatch(communicationList.id, emailContacts, mailchimpClient, mailchimpConfig);

      // if they can't, they're archived, and will be failed to be retrieved for update
      List<String> emailsToArchive = new ArrayList<>();
      emailsToArchive.addAll(contactsToArchive.stream().map(crmContact -> crmContact.email).collect(Collectors.toList()));
      emailsToArchive.addAll(mcEmailsToArchive);

      mailchimpClient.archiveContactsBatch(communicationList.id, emailsToArchive);

    } catch (MailchimpException e) {
      env.logJobWarn("Mailchimp syncContacts failed: {}", mailchimpClient.exceptionToString(e));
    } catch (Exception e) {
      env.logJobWarn("Mailchimp syncContacts failed", e);
    }
  }

  protected void runBatchOperations(MailchimpClient mailchimpClient, EnvironmentConfig.CommunicationPlatform mailchimpConfig, String batchStatusId, Integer attemptCount) throws Exception {
    if (attemptCount == BATCH_STATUS_MAX_RETRIES) {
      env.logJobError("exhausted retries; returning...");
    } else {
      BatchStatus batchStatus = mailchimpClient.getBatchStatus(batchStatusId);
      if (!"finished".equalsIgnoreCase(batchStatus.status)) {
        env.logJobInfo("Batch '{}' is not finished: {}/{} Retrying in {} seconds...", batchStatusId, batchStatus.finished_operations, batchStatus.total_operations, BATCH_STATUS_RETRY_TIMEOUT_IN_SECONDS);
        Thread.sleep(BATCH_STATUS_RETRY_TIMEOUT_IN_SECONDS * 1000);
        Integer newAttemptCount = attemptCount + 1;
        runBatchOperations(mailchimpClient, mailchimpConfig, batchStatusId, newAttemptCount);
      } else {
        env.logJobInfo("Batch '{}' finished! (finished/total) {}/{}", batchStatusId, batchStatus.finished_operations, batchStatus.total_operations);
        if (batchStatus.errored_operations > 0) {
          env.logJobWarn("Errored operations count: {}", batchStatus.errored_operations);
        } else {
          env.logJobInfo("All operations processed OK!");
        }

        // TODO: Periodically failing, but don't hold up everything else if it does. Or compression in the response
        //  body may be different for large operations -- getting this: java.util.zip.ZipException: ZipFile invalid LOC header (bad signature)
        try {
          String batchResponse = getBatchResponseAsString(batchStatus, mailchimpConfig);
          List<MailchimpClient.BatchOperation> batchOperations = deserializeBatchOperations(batchResponse);

          // Logging error operations
          batchOperations.stream()
              .filter(batchOperation -> batchOperation.status >= 300)
              .forEach(batchOperation ->
                  env.logJobWarn("Failed Batch Operation (status: detail): {}: {}", batchOperation.response.status, batchOperation.response.detail));
        } catch (Exception e) {
          env.logJobError("failed to fetch batch operation results", e);
        }
      }
    }
  }

  protected String getBatchResponseAsString(BatchStatus batchStatus, EnvironmentConfig.CommunicationPlatform mailchimpConfig) throws Exception {
    if (Strings.isNullOrEmpty(batchStatus.response_body_url)) {
      return null;
    }

    String responseString = null;
    InputStream inputStream = HttpClient.get(batchStatus.response_body_url, HttpClient.HeaderBuilder.builder()
        .authBearerToken(mailchimpConfig.secretKey)
        .header("Accept-Encoding", "application/gzip"), InputStream.class);

    try (TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(
        new GzipCompressorInputStream(new BufferedInputStream(inputStream)))) {
      TarArchiveEntry tarArchiveEntry;
      while ((tarArchiveEntry = (TarArchiveEntry) tarArchiveInputStream.getNextEntry()) != null) {
        if (!tarArchiveEntry.isDirectory()) {
          responseString = IOUtils.toString(tarArchiveInputStream, StandardCharsets.UTF_8);
        }
      }
    } catch (Exception e) {
      env.logJobError("Failed to get batch response body! {}", e);
    }
    return responseString;
  }

  protected List<MailchimpClient.BatchOperation> deserializeBatchOperations(String batchOperationsString) {
    if (Strings.isNullOrEmpty(batchOperationsString)) {
      return Collections.emptyList();
    }
    List<MailchimpClient.BatchOperation> batchOperations = new ArrayList<>();
    try {
      JSONArray jsonArray = new JSONArray(batchOperationsString);
      ObjectMapper objectMapper = new ObjectMapper();

      for (int i = 0; i< jsonArray.length(); i ++) {
        JSONObject batchOperation = jsonArray.getJSONObject(i);
        // Response is an escaped string - converting it to json object and back to string to unescape
        String response = batchOperation.getString("response");
        JSONObject responseObject = new JSONObject(response);
        batchOperation.put("response", responseObject);

        batchOperations.add(objectMapper.readValue(batchOperation.toString(), new TypeReference<>() {}));
      }
    } catch (JsonProcessingException e) {
      env.logJobWarn("Failed to deserialize batch operations! {}", e.getMessage());
    }
    return batchOperations;
  }

  protected Map<String, Set<String>> getActiveTags(List<CrmContact> crmContacts, Map<String, List<String>> crmContactCampaignNames, EnvironmentConfig.CommunicationPlatform mailchimpConfig) throws Exception {
    Map<String, Set<String>> activeTags = new HashMap<>();
    for (CrmContact crmContact : crmContacts) {
      Set<String> tagsCleaned = getContactTagsCleaned(crmContact, crmContactCampaignNames.get(crmContact.id), mailchimpConfig);
      activeTags.put(crmContact.email, tagsCleaned);
    }
    return activeTags;
  }

  protected List<MemberInfo> toMemberInfos(EnvironmentConfig.CommunicationList communicationList, List<CrmContact> crmContacts,
      Map<String, Map<String, Object>> customFieldsMap) {
    return crmContacts.stream()
        .map(crmContact -> toMcMemberInfo(crmContact, customFieldsMap.get(crmContact.email), communicationList.groups))
        .collect(Collectors.toList());
  }

  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.CommunicationPlatform mailchimpConfig : env.getConfig().mailchimp) {
      MailchimpClient mailchimpClient = new MailchimpClient(mailchimpConfig, env);
      for (EnvironmentConfig.CommunicationList communicationList : mailchimpConfig.lists) {
        syncUnsubscribes(mailchimpClient.getListMembers(communicationList.id, "unsubscribed", lastSync), c -> c.emailOptOut = true);
        syncUnsubscribes(mailchimpClient.getListMembers(communicationList.id, "cleaned", lastSync), c -> c.emailBounced = true);
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
    for (CrmContact crmContact : unsubscribeContacts) {
      env.logJobInfo("updating unsubscribed contact in CRM: {} ({} of {})", crmContact.email, count++, unsubscribeContacts.size());
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

    for (EnvironmentConfig.CommunicationPlatform mailchimpConfig : env.getConfig().mailchimp) {
      for (EnvironmentConfig.CommunicationList communicationList : mailchimpConfig.lists) {
        Optional<CrmContact> crmContact = crmService.getFilteredContactById(contactId, communicationList.crmFilter);

        if (crmContact.isPresent() && !Strings.isNullOrEmpty(crmContact.get().email)) {
          env.logJobInfo("updating contact {} {} on list {}", crmContact.get().id, crmContact.get().email, communicationList.id);
          Map<String, List<String>> crmContactCampaignNames = getContactCampaignNames(List.of(crmContact.get()));
          syncContact(crmContact.get(), crmContactCampaignNames, mailchimpConfig, communicationList);
        }
      }
    }
  }

  protected void syncContact(CrmContact crmContact, Map<String, List<String>> crmContactCampaignNames,
                             EnvironmentConfig.CommunicationPlatform mailchimpConfig, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    MailchimpClient mailchimpClient = new MailchimpClient(mailchimpConfig, env);

    try {
      // transactional is always subscribed
      if (communicationList.type == EnvironmentConfig.CommunicationListType.TRANSACTIONAL || crmContact.canReceiveEmail()) {
        Map<String, Object> customFields = getCustomFields(communicationList.id, crmContact, mailchimpClient, mailchimpConfig);
        mailchimpClient.upsertContact(communicationList.id, toMcMemberInfo(crmContact, customFields, communicationList.groups));
        // if they can't, they're archived, and will be failed to be retrieved for update
        updateTags(communicationList.id, crmContact, crmContactCampaignNames.get(crmContact.id), mailchimpClient, mailchimpConfig);
      } else if (!crmContact.canReceiveEmail()) {
        mailchimpClient.archiveContact(communicationList.id, crmContact.email);
      }
    } catch (MailchimpException e) {
      env.logJobWarn("Mailchimp syncContact failed: {}", mailchimpClient.exceptionToString(e));
    } catch (Exception e) {
      env.logJobWarn("Mailchimp syncContact failed", e);
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

  protected void updateTags(String listId, CrmContact crmContact, List<String> crmContactCampaignNames,
      MailchimpClient mailchimpClient, EnvironmentConfig.CommunicationPlatform mailchimpConfig) {
    try {
      Set<String> activeTags = getContactTagsCleaned(crmContact, crmContactCampaignNames, mailchimpConfig);
      Set<String> contactTags = mailchimpClient.getContactTags(listId, crmContact.email);
      
      String[] contactTagFilters = mailchimpConfig.contactTagFilters.toArray(new String[]{});
      Set<String> inactiveTags = contactTags.stream()
          .filter(tag -> !activeTags.contains(tag))
          // filter out any tags that need to remain (IE, ones that were manually created in MC)
          .filter(tag -> !StringUtils.containsAny(tag, contactTagFilters))
          .collect(Collectors.toSet());

      mailchimpClient.updateContactTags(listId, crmContact.email, activeTags, inactiveTags);
    } catch (Exception e) {
      env.logJobError("updating tags failed for contact: {} {}", crmContact.id, crmContact.email, e);
    }
  }

  protected String updateTagsBatch(String listId, List<MailchimpClient.EmailContact> emailContacts,
      MailchimpClient mailchimpClient, EnvironmentConfig.CommunicationPlatform mailchimpConfig) {

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
