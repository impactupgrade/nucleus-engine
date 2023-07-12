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
import com.google.common.collect.Lists;
import com.impactupgrade.nucleus.client.MailchimpClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.client.MailchimpClient.ADDRESS;
import static com.impactupgrade.nucleus.client.MailchimpClient.FIRST_NAME;
import static com.impactupgrade.nucleus.client.MailchimpClient.LAST_NAME;
import static com.impactupgrade.nucleus.client.MailchimpClient.PHONE_NUMBER;
import static com.impactupgrade.nucleus.client.MailchimpClient.SUBSCRIBED;

public class MailchimpEmailService extends AbstractEmailService {

  private static final Logger log = LogManager.getLogger(MailchimpEmailService.class);

  private static final Integer BATCH_REQUEST_OPERATIONS_SIZE = 500;
  private static final Integer BATCH_STATUS_RETRY_TIMEOUT_IN_SECONDS = 10;
  private static final Integer BATCH_STATUS_MAX_RETRIES = 5;

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
  public void sendEmailTemplate(String subject, String template, Map<String, Object> data, List<String> tos, String from) {
    // TODO
  }

  @Override
  public void syncContacts(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.EmailPlatform mailchimpConfig : env.getConfig().mailchimp) {
      for (EnvironmentConfig.EmailList emailList : mailchimpConfig.lists) {
        // clear the cache, since fields differ between audiences
        mergeFieldsNameToTag.clear();

        List<CrmContact> crmContacts = getCrmContacts(emailList, lastSync);
        Map<String, List<String>> crmContactCampaignNames = getContactCampaignNames(crmContacts);
        Map<String, List<String>> tags = new MailchimpClient(mailchimpConfig).getContactsTags(emailList.id);

        List<List<CrmContact>> partitions = Lists.partition(crmContacts, BATCH_REQUEST_OPERATIONS_SIZE);
        int i = 1;
        for (List<CrmContact> contactsBatch : partitions) {
          log.info("Processing contacts batch {} of total {}...", i, partitions.size());
          syncContacts(contactsBatch, crmContactCampaignNames, tags, mailchimpConfig, emailList);
          i++;
        }
      }
    }
  }

  protected void syncContacts(List<CrmContact> crmContacts, Map<String, List<String>> crmContactCampaignNames,
    Map<String, List<String>> tags, EnvironmentConfig.EmailPlatform mailchimpConfig, EnvironmentConfig.EmailList emailList) throws Exception {
    MailchimpClient mailchimpClient = new MailchimpClient(mailchimpConfig);

    List<CrmContact> contactsToUpsert = new ArrayList<>();
    List<CrmContact> contactsToArchive = new ArrayList<>();

    // transactional is always subscribed
    if (emailList.type == EnvironmentConfig.EmailListType.TRANSACTIONAL) {
      contactsToUpsert.addAll(crmContacts);
    } else {
      crmContacts.forEach(crmContact -> (crmContact.canReceiveEmail() ? contactsToUpsert : contactsToArchive).add(crmContact));
    }

    try {
      Map<String, Map<String, Object>> contactsCustomFields = new HashMap<>();
      for (CrmContact crmContact : contactsToUpsert) {
        Map<String, Object> customFieldMap = getCustomFields(emailList.id, crmContact, mailchimpClient, mailchimpConfig);
        contactsCustomFields.put(crmContact.email, customFieldMap);
      }

      List<MemberInfo> memberInfos = toMemberInfos(emailList, contactsToUpsert, contactsCustomFields);

      String upsertBatchId = mailchimpClient.upsertContactsBatch(emailList.id, memberInfos);
      // Getting batch processing results synchronously to make sure
      // all contacts were processed before updating tags
      List<MailchimpClient.BatchOperation> batchOperations = getBatchOperations(mailchimpClient, mailchimpConfig, upsertBatchId, 0);

      // Logging error operations
      batchOperations.stream()
          .filter(batchOperation -> batchOperation.status >= 300)
          .forEach(batchOperation ->
              log.warn("Failed Batch Operation (status: detail): {}: {}", batchOperation.response.status, batchOperation.response.detail));

      Map<String, List<String>> activeTags = getActiveTags(contactsToUpsert, crmContactCampaignNames, mailchimpConfig);
      List<MailchimpClient.EmailContact> emailContacts = contactsToUpsert.stream()
          .map(crmContact -> new MailchimpClient.EmailContact(crmContact.email, activeTags.get(crmContact.email), tags.get(crmContact.email)))
          .collect(Collectors.toList());

      updateTagsBatch(emailList.id, emailContacts, mailchimpClient);

      // if they can't, they're archived, and will be failed to be retrieved for update
      List<String> emailsToArchive = contactsToArchive.stream().map(crmContact -> crmContact.email).collect(Collectors.toList());
      mailchimpClient.archiveContactsBatch(emailList.id, emailsToArchive);

    } catch (MailchimpException e) {
      log.warn("Mailchimp syncContacts failed: {}", mailchimpClient.exceptionToString(e));
    } catch (Exception e) {
      log.warn("Mailchimp syncContacts failed", e);
    }
  }

  protected List<MailchimpClient.BatchOperation> getBatchOperations(MailchimpClient mailchimpClient, EnvironmentConfig.EmailPlatform mailchimpConfig, String batchStatusId, Integer attemptCount) throws Exception {
    List<MailchimpClient.BatchOperation> batchOperations = null;
    if (attemptCount == BATCH_STATUS_MAX_RETRIES) {
      log.error("exhausted retries; returning...");
    } else {
      BatchStatus batchStatus = mailchimpClient.getBatchStatus(batchStatusId);
      if (!"finished".equalsIgnoreCase(batchStatus.status)) {
        log.info("Batch '{}' is not finished. Retrying in {} seconds...", batchStatusId, BATCH_STATUS_RETRY_TIMEOUT_IN_SECONDS);
        Thread.sleep(BATCH_STATUS_RETRY_TIMEOUT_IN_SECONDS * 1000);
        Integer newAttemptCount = attemptCount + 1;
        batchOperations = getBatchOperations(mailchimpClient, mailchimpConfig, batchStatusId, newAttemptCount);
      } else {
        log.info("Batch '{}' finished! (finished/total) {}/{}", batchStatusId, batchStatus.finished_operations, batchStatus.total_operations);
        if (batchStatus.errored_operations > 0) {
          log.warn("Errored operations count: {}", batchStatus.errored_operations);
        } else {
          log.info("All operations processed OK!");
        }
        String batchResponse = getBatchResponseAsString(batchStatus, mailchimpConfig);
        batchOperations = deserializeBatchOperations(batchResponse);
      }
    }
    return batchOperations;
  }

  protected String getBatchResponseAsString(BatchStatus batchStatus, EnvironmentConfig.EmailPlatform mailchimpConfig) throws Exception {
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
      log.error("Failed to get batch response body! {}", e);
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
      log.warn("Failed to deserialize batch operations! {}", e.getMessage());
    }
    return batchOperations;
  }

  protected Map<String, List<String>> getActiveTags(List<CrmContact> crmContacts, Map<String, List<String>> crmContactCampaignNames, EnvironmentConfig.EmailPlatform mailchimpConfig) throws Exception {
    Map<String, List<String>> activeTags = new HashMap<>();
    for (CrmContact crmContact : crmContacts) {
      List<String> tagsCleaned = getContactTagsCleaned(crmContact, crmContactCampaignNames.get(crmContact.id), mailchimpConfig);
      activeTags.put(crmContact.email, tagsCleaned);
    }
    return activeTags;
  }

  protected List<MemberInfo> toMemberInfos(EnvironmentConfig.EmailList emailList, List<CrmContact> crmContacts,
      Map<String, Map<String, Object>> customFieldsMap) {
    return crmContacts.stream()
        .map(crmContact -> toMcMemberInfo(crmContact, customFieldsMap.get(crmContact.email), emailList.groups))
        .collect(Collectors.toList());
  }

  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.EmailPlatform mailchimpConfig : env.getConfig().mailchimp) {
      MailchimpClient mailchimpClient = new MailchimpClient(mailchimpConfig);
      for (EnvironmentConfig.EmailList emailList : mailchimpConfig.lists) {
        syncUnsubscribes(mailchimpClient.getListMembers(emailList.id, "unsubscribed", lastSync), c -> c.emailOptOut = true);
        syncUnsubscribes(mailchimpClient.getListMembers(emailList.id, "cleaned", lastSync), c -> c.emailBounced = true);
      }
    }
  }

  // TODO: Purely allowing this to unsubscribe in the CRM, as opposed to archiving immediately in MC. Let organizations
  //  decide if their unsubscribe-from-CRM code does an archive...
  private void syncUnsubscribes(List<MemberInfo> unsubscribes, Consumer<CrmContact> consumer) throws Exception {
    // VITAL: In order for batching to work, must be operating under a single instance of the CrmService!
    CrmService crmService = env.primaryCrmService();

    List<String> unsubscribeEmails = unsubscribes.stream().map(u -> u.email_address).filter(Objects::nonNull).distinct().sorted().toList();
    if (unsubscribeEmails.isEmpty()) {
      return;
    }
    List<CrmContact> unsubscribeContacts = crmService.getContactsByEmails(unsubscribeEmails);

    int count = 0;
    for (CrmContact crmContact : unsubscribeContacts) {
      log.info("updating unsubscribed contact in CRM: {} ({} of {})", crmContact.email, count++, unsubscribeContacts.size());
      CrmContact updateContact = new CrmContact();
      updateContact.id = crmContact.id;
      consumer.accept(updateContact);
      crmService.batchUpdateContact(updateContact);
    }
    crmService.batchFlush();
  }

  // TODO: TER and STS still using contactId, update to use email only.
  @Override
  public void upsertContact(String email, @Deprecated String contactId) throws Exception {
    CrmService crmService = env.primaryCrmService();

    for (EnvironmentConfig.EmailPlatform mailchimpConfig : env.getConfig().mailchimp) {
      for (EnvironmentConfig.EmailList emailList : mailchimpConfig.lists) {
        Optional<CrmContact> crmContact = Optional.empty();
        if (!Strings.isNullOrEmpty(email)) {
          crmContact = crmService.getFilteredContactByEmail(email, emailList.crmFilter);
        } else if (!Strings.isNullOrEmpty(contactId)) {
          crmContact = crmService.getFilteredContactById(contactId, emailList.crmFilter);
        }

        if (crmContact.isPresent() && !Strings.isNullOrEmpty(crmContact.get().email)) {
          log.info("updating contact {} {} on list {}", crmContact.get().id, crmContact.get().email, emailList.id);
          Map<String, List<String>> crmContactCampaignNames = getContactCampaignNames(List.of(crmContact.get()));
          syncContact(crmContact.get(), crmContactCampaignNames, mailchimpConfig, emailList);
        } else if (!Strings.isNullOrEmpty(email)) {
          // If the contact didn't exist, but we have the email, try archiving it. Could be a contact that was
          // just deleted in the CRM.
          log.info("attempting to archive contact {} on list {}", email, emailList.id);
          try {
            new MailchimpClient(mailchimpConfig).archiveContact(emailList.id, email);
          } catch (MailchimpException e) {
            if (e.code == 405) {
              // swallow -- attempting to archive a contact that is already archived
            } else {
              throw e;
            }
          }
        }
      }
    }
  }

  protected void syncContact(CrmContact crmContact, Map<String, List<String>> crmContactCampaignNames,
                             EnvironmentConfig.EmailPlatform mailchimpConfig, EnvironmentConfig.EmailList emailList) throws Exception {
    MailchimpClient mailchimpClient = new MailchimpClient(mailchimpConfig);

    try {
      // transactional is always subscribed
      if (emailList.type == EnvironmentConfig.EmailListType.TRANSACTIONAL || crmContact.canReceiveEmail()) {
        Map<String, Object> customFields = getCustomFields(emailList.id, crmContact, mailchimpClient, mailchimpConfig);
        mailchimpClient.upsertContact(emailList.id, toMcMemberInfo(crmContact, customFields, emailList.groups));
        // if they can't, they're archived, and will be failed to be retrieved for update
        updateTags(emailList.id, crmContact, crmContactCampaignNames.get(crmContact.id), mailchimpClient, mailchimpConfig);
      } else if (!crmContact.canReceiveEmail()) {
        mailchimpClient.archiveContact(emailList.id, crmContact.email);
      }
    } catch (MailchimpException e) {
      log.warn("Mailchimp syncContact failed: {}", mailchimpClient.exceptionToString(e));
    } catch (Exception e) {
      log.warn("Mailchimp syncContact failed", e);
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
      MailchimpClient mailchimpClient, EnvironmentConfig.EmailPlatform mailchimpConfig) {
    try {
      List<String> activeTags = getContactTagsCleaned(crmContact, crmContactCampaignNames, mailchimpConfig);
      List<String> contactTags = mailchimpClient.getContactTags(listId, crmContact.email);
      
      String[] tagPrefixesArray = mailchimpConfig.contactTagFilters.tagPrefixes.toArray(new String[]{});
      List<String> inactiveTags = contactTags.stream()
          .filter(tag -> !activeTags.contains(tag))
          .filter(tag -> 
              mailchimpConfig.contactTagFilters.tags.contains(tag) || StringUtils.startsWithAny(tag, tagPrefixesArray))
          .collect(Collectors.toList());

      mailchimpClient.updateContactTags(listId, crmContact.email, activeTags, inactiveTags);
    } catch (Exception e) {
      log.error("updating tags failed for contact: {} {}", crmContact.id, crmContact.email, e);
    }
  }

  protected String updateTagsBatch(String listId,
      List<MailchimpClient.EmailContact> emailContacts,
      MailchimpClient mailchimpClient) {
    emailContacts.stream()
            .filter(emailContact -> CollectionUtils.isNotEmpty(emailContact.inactiveTags()))
            .forEach(emailContact -> emailContact.inactiveTags().removeAll(emailContact.activeTags()));
    try {
      return mailchimpClient.updateContactTagsBatch(listId, emailContacts);
    } catch (Exception e) {
      log.error("updating tags failed for contacts! {}", e.getMessage());
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
    // MC only allows "complete" addresses, so only fill this out if that's the case. As a backup, we're creating
    // separate, custom fields for city/state/zip/country.
    if (!Strings.isNullOrEmpty(crmContact.mailingAddress.street)) {
      mcContact.merge_fields.mapping.put(ADDRESS, toMcAddress(crmContact.mailingAddress));
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
