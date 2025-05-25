/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.ecwid.maleorang.MailchimpException;
import com.ecwid.maleorang.MailchimpObject;
import com.ecwid.maleorang.method.v3_0.batches.BatchStatus;
import com.ecwid.maleorang.method.v3_0.batches.GetBatchStatusMethod;
import com.ecwid.maleorang.method.v3_0.batches.StartBatchMethod;
import com.ecwid.maleorang.method.v3_0.campaigns.content.ContentInfo;
import com.ecwid.maleorang.method.v3_0.campaigns.content.GetCampaignContentMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.DeleteMemberMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.EditMemberMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.GetMembersMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.MemberInfo;
import com.ecwid.maleorang.method.v3_0.lists.merge_fields.EditMergeFieldMethod;
import com.ecwid.maleorang.method.v3_0.lists.merge_fields.GetMergeFieldsMethod;
import com.ecwid.maleorang.method.v3_0.lists.merge_fields.MergeFieldInfo;
import com.ecwid.maleorang.method.v3_0.reports.sent_to.GetCampaignSentToMethod;
import com.ecwid.maleorang.method.v3_0.reports.sent_to.SentToInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


public class MailchimpClient {

  public static final String SUBSCRIBED = "subscribed";
  public static final String FIRST_NAME = "FNAME";
  public static final String LAST_NAME = "LNAME";
  public static final String PHONE_NUMBER = "PHONE";
  public static final String SMS_PHONE_NUMBER = "SMSPHONE";
  public static final String TAGS = "tags";
  public static final String TAG_NAME = "name";
  public static final String TAG_STATUS = "status";
  public static final String TAG_ACTIVE = "active";
  public static final String TAG_INACTIVE = "inactive";

  // 2 hours
  protected static Integer BATCH_STATUS_RETRY_WAIT_IN_SECONDS = 60;
  protected static Integer BATCH_STATUS_MAX_RETRIES = 24;

  protected final com.ecwid.maleorang.MailchimpClient client;
  protected final Environment env;

  public MailchimpClient(EnvironmentConfig.CommunicationPlatform mailchimpConfig, Environment env) {
    client = new com.ecwid.maleorang.MailchimpClient(mailchimpConfig.secretKey);
    this.env = env;
  }

  public void upsertContact(String listId, MemberInfo contact) throws IOException, MailchimpException {
    EditMemberMethod.CreateOrUpdate upsertMemberMethod = new EditMemberMethod.CreateOrUpdate(listId, contact.email_address);
    upsertMemberMethod.status_if_new = contact.status;
    upsertMemberMethod.mapping.putAll(contact.mapping);
    upsertMemberMethod.merge_fields.mapping.putAll(contact.merge_fields.mapping);
    upsertMemberMethod.interests.mapping.putAll(contact.interests.mapping);

    client.execute(upsertMemberMethod);
  }

  public String upsertContactsBatch(String listId, List<MemberInfo> contacts) throws IOException, MailchimpException {
    List<EditMemberMethod.CreateOrUpdate> upsertMemberMethods = contacts.stream()
        .map(contact -> {
          EditMemberMethod.CreateOrUpdate upsertMemberMethod = new EditMemberMethod.CreateOrUpdate(listId, contact.email_address);
          upsertMemberMethod.status_if_new = contact.status;
          upsertMemberMethod.mapping.putAll(contact.mapping);
          upsertMemberMethod.merge_fields.mapping.putAll(contact.merge_fields.mapping);
          upsertMemberMethod.interests.mapping.putAll(contact.interests.mapping);
          return upsertMemberMethod;
        })
        .collect(Collectors.toList());

    if (upsertMemberMethods.isEmpty()) {
      return null;
    }

    StartBatchMethod startBatchMethod = new StartBatchMethod(upsertMemberMethods);
    BatchStatus batchStatus = client.execute(startBatchMethod);
    return batchStatus.id;
  }

  public List<MemberInfo> getListMembers(String listId) throws IOException, MailchimpException {
    return getListMembers(listId, null, null);
  }

  public List<MemberInfo> getListMembers(String listId, String status, Calendar sinceLastChanged) throws IOException, MailchimpException {
    return getListMembers(listId, status,
            "members.email_address,members.tags,total_items", // HUGE performance improvement -- limit to only what we need
            sinceLastChanged);
  }

  public List<MemberInfo> getListMembers(String listId, String status, String fields, Calendar sinceLastChanged) throws IOException, MailchimpException {
    GetMembersMethod getMembersMethod = new GetMembersMethod(listId);
    getMembersMethod.status = status;
    getMembersMethod.fields = fields;
    getMembersMethod.count = 1000; // subjective, but this is timing out periodically -- may need to dial it back further
    env.logJobInfo("retrieving list {} contacts", listId);
    if (sinceLastChanged != null) {
      getMembersMethod.since_last_changed = sinceLastChanged.getTime();
      String formattedDate = new SimpleDateFormat("yyyy-MM-dd").format(sinceLastChanged.getTime());
      env.logJobInfo("retrieving contacts whose information changed after {}", formattedDate);
    }
    GetMembersMethod.Response getMemberResponse = client.execute(getMembersMethod);
    List<MemberInfo> members = new ArrayList<>(getMemberResponse.members);
    while (getMemberResponse.total_items > members.size()) {
      getMembersMethod.offset = members.size();
      env.logJobInfo("retrieving list {} contacts (offset {} of total {})", listId, getMembersMethod.offset, getMemberResponse.total_items);
      getMemberResponse = client.execute(getMembersMethod);
      members.addAll(getMemberResponse.members);
    }

    return members;
  }

  public String archiveContactsBatch(String listId, Collection<String> emails) throws IOException, MailchimpException {
    List<DeleteMemberMethod> deleteMemberMethods = emails.stream()
        .map(email -> new DeleteMemberMethod(listId, email))
        .collect(Collectors.toList());

    if (deleteMemberMethods.isEmpty()) {
      return null;
    }

    StartBatchMethod startBatchMethod = new StartBatchMethod(deleteMemberMethods);
    BatchStatus batchStatus = client.execute(startBatchMethod);
    return batchStatus.id;
  }

  public Map<String, Set<String>> getContactsTags(List<MemberInfo> memberInfos) throws IOException, MailchimpException {
    return memberInfos.stream()
        .collect(Collectors.toMap(
            memberInfo -> memberInfo.email_address.toLowerCase(Locale.ROOT),
            memberInfo -> {
              List<MailchimpObject> tags = (List<MailchimpObject>) memberInfo.mapping.get(TAGS);
              return tags.stream().map(t -> t.mapping.get(TAG_NAME).toString()).collect(Collectors.toSet());
            }
        ));
  }

  public void updateContactTags(String listId, EmailContact emailContact) throws IOException, MailchimpException {
    EditMemberMethod.AddorRemoveTag editMemberMethod = addOrRemoveTag(listId, emailContact);
    client.execute(editMemberMethod);
  }

  public String updateContactTagsBatch(String listId, List<EmailContact> emailContacts) throws IOException, MailchimpException {
    List<EditMemberMethod.AddorRemoveTag> editMemberMethods = emailContacts.stream()
        .map(emailContact -> addOrRemoveTag(listId, emailContact)).collect(Collectors.toList());

    if (editMemberMethods.isEmpty()) {
      return null;
    }

    StartBatchMethod startBatchMethod = new StartBatchMethod(editMemberMethods);
    BatchStatus batchStatus = client.execute(startBatchMethod);
    return batchStatus.id;
  }

  protected EditMemberMethod.AddorRemoveTag addOrRemoveTag(String listId, EmailContact emailContact) {
    Set<String> active = emailContact.activeTags;
    Set<String> inactive = emailContact.inactiveTags;
    ArrayList<MailchimpObject> tags = new ArrayList<>();
    if (CollectionUtils.isNotEmpty(active)) {
      for (String activeTag : active) {
        MailchimpObject tag = new MailchimpObject();
        tag.mapping.put(TAG_STATUS, TAG_ACTIVE);
        tag.mapping.put(TAG_NAME, activeTag);
        tags.add(tag);
      }
    }
    if (CollectionUtils.isNotEmpty(inactive)) {
      for (String inactiveTag : inactive) {
        MailchimpObject tag = new MailchimpObject();
        tag.mapping.put(TAG_STATUS, TAG_INACTIVE);
        tag.mapping.put(TAG_NAME, inactiveTag);
        tags.add(tag);
      }
    }

    EditMemberMethod.AddorRemoveTag editMemberMethod = new EditMemberMethod.AddorRemoveTag(listId, emailContact.email);
    editMemberMethod.tags = tags;

    return editMemberMethod;
  }

  public List<SentToInfo> getCampaignRecipients(String campaignId) throws IOException, MailchimpException {
    GetCampaignSentToMethod getCampaignSentToMethod = new GetCampaignSentToMethod(campaignId);
    getCampaignSentToMethod.fields = "sent_to.email_address,sent_to.status,total_items"; // HUGE performance improvement -- limit to only what we need
    getCampaignSentToMethod.count = 1000; // subjective, but this is timing out periodically -- may need to dial it back further
    env.logJobInfo("retrieving campaign {} contacts", campaignId);
    GetCampaignSentToMethod.Response getCampaignSentToResponse = client.execute(getCampaignSentToMethod);
    List<SentToInfo> sentTos = new ArrayList<>(getCampaignSentToResponse.sent_to);
    while (getCampaignSentToResponse.total_items > sentTos.size()) {
      getCampaignSentToMethod.offset = sentTos.size();
      env.logJobInfo("retrieving campaign {} contacts (offset {} of total {})", campaignId, getCampaignSentToMethod.offset, getCampaignSentToResponse.total_items);
      getCampaignSentToResponse = client.execute(getCampaignSentToMethod);
      sentTos.addAll(getCampaignSentToResponse.sent_to);
    }

    return sentTos;
  }

  public ContentInfo getCampaignContent(String campaignId) throws IOException, MailchimpException {
    GetCampaignContentMethod getCampaignContentMethod = new GetCampaignContentMethod(campaignId);
    return client.execute(getCampaignContentMethod);
  }

  public void runBatchOperations(EnvironmentConfig.CommunicationPlatform mailchimpConfig, String batchStatusId, int attemptCount) throws Exception {
    if (Strings.isNullOrEmpty(batchStatusId)) {
      return;
    }

    if (attemptCount == BATCH_STATUS_MAX_RETRIES) {
      env.logJobError("exhausted retries; returning...");
    } else {
      GetBatchStatusMethod getBatchStatusMethod = new GetBatchStatusMethod(batchStatusId);
      BatchStatus batchStatus = client.execute(getBatchStatusMethod);
      if (!"finished".equalsIgnoreCase(batchStatus.status)) {
        env.logJobInfo("Batch '{}' is not finished: {}/{} Retrying in {} seconds...", batchStatusId, batchStatus.finished_operations, batchStatus.total_operations, BATCH_STATUS_RETRY_WAIT_IN_SECONDS);
        Thread.sleep(BATCH_STATUS_RETRY_WAIT_IN_SECONDS * 1000);
        int newAttemptCount = attemptCount + 1;
        runBatchOperations(mailchimpConfig, batchStatusId, newAttemptCount);
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
                  env.logJobWarn(
                      "Failed Batch Operation for {}: {} {} :: errors: {}",
                      batchOperation.response.contactId,
                      batchOperation.response.status,
                      batchOperation.response.detail,
                      String.join(", ", batchOperation.response.errors.stream().map(e -> "(" + e.field + ") " + e.message).toList())
                  )
              );
        } catch (Exception e) {
          env.logJobWarn("failed to fetch batch operation results", e);
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

  public List<MergeFieldInfo> getMergeFields(String listId) throws IOException, MailchimpException {
    GetMergeFieldsMethod getMergeFields = new GetMergeFieldsMethod(listId);
    getMergeFields.count = 1000; // the max
    GetMergeFieldsMethod.Response getMergeFieldsResponse = client.execute(getMergeFields);
    return getMergeFieldsResponse.merge_fields;
  }

  public MergeFieldInfo createMergeField(String listId, String name, MergeFieldInfo.Type type)
      throws IOException, MailchimpException {
    EditMergeFieldMethod.Create createMergeField = new EditMergeFieldMethod.Create(listId, type);
    createMergeField.name = name;
    createMergeField.required = false;
    createMergeField.is_public = false;
    return client.execute(createMergeField);
  }

  public String exceptionToString(MailchimpException e) {
    String description = e.description;
    if (e.errors != null) {
      description += String.join(" ; ", e.errors);
    }
    return description;
  }

  public record EmailContact(String email, Set<String> activeTags, Set<String> inactiveTags) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class BatchOperation {
    @JsonProperty("status_code")
    public Integer status;
    @JsonProperty("operation_id")
    public String operationId;
    public BatchOperationResponse response;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class BatchOperationResponse {
    public String id;
    @JsonProperty("merge_fields")
    public Map<String, Object> mergeFields;
    @JsonProperty("contact_id")
    public String contactId;
    @JsonProperty("email_address")
    public String email;
    @JsonProperty("full_name")
    public String fullName;
    @JsonProperty("tags_count")
    public Integer tagsCount;
    @JsonProperty("last_changed")
    public Date lastChangedAt;
    @JsonProperty("list_id")
    public String listId;

    // error response fields
    public String instance;
    public String detail;
    public String type;
    public String title;
    public String status;
    public List<Error> errors = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Error {
    public String field;
    public String message;
  }

  // IT utilities

  public static void setBatchStatusRetryWaitInSeconds(int batchStatusRetryWaitInSeconds) {
    BATCH_STATUS_RETRY_WAIT_IN_SECONDS = batchStatusRetryWaitInSeconds;
  }
}
