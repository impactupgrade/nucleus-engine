/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.ecwid.maleorang.MailchimpException;
import com.ecwid.maleorang.MailchimpObject;
import com.ecwid.maleorang.method.v3_0.batches.BatchStatus;
import com.ecwid.maleorang.method.v3_0.batches.GetBatchStatusMethod;
import com.ecwid.maleorang.method.v3_0.batches.StartBatchMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.DeleteMemberMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.EditMemberMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.GetMemberMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.GetMembersMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.MemberInfo;
import com.ecwid.maleorang.method.v3_0.lists.merge_fields.EditMergeFieldMethod;
import com.ecwid.maleorang.method.v3_0.lists.merge_fields.GetMergeFieldsMethod;
import com.ecwid.maleorang.method.v3_0.lists.merge_fields.MergeFieldInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import org.apache.commons.collections.CollectionUtils;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


public class MailchimpClient {

  public static final String SUBSCRIBED = "subscribed";
  public static final String FIRST_NAME = "FNAME";
  public static final String LAST_NAME = "LNAME";
  public static final String PHONE_NUMBER = "PHONE";
  public static final String TAGS = "tags";
  public static final String TAG_NAME = "name";
  public static final String TAG_STATUS = "status";
  public static final String TAG_ACTIVE = "active";
  public static final String TAG_INACTIVE = "inactive";

  protected final com.ecwid.maleorang.MailchimpClient client;
  protected final Environment env;

  public MailchimpClient(EnvironmentConfig.CommunicationPlatform mailchimpConfig, Environment env) {
    client = new com.ecwid.maleorang.MailchimpClient(mailchimpConfig.secretKey);
    this.env = env;
  }

  public MemberInfo getContactInfo(String listId, String contactEmail) throws IOException, MailchimpException {
    GetMemberMethod getMemberMethod = new GetMemberMethod(listId, contactEmail);
    return client.execute(getMemberMethod);
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
          upsertMemberMethod.tags = contact.tags;
          return upsertMemberMethod;
        })
        .collect(Collectors.toList());

    StartBatchMethod startBatchMethod = new StartBatchMethod(upsertMemberMethods);
    BatchStatus batchStatus = client.execute(startBatchMethod);
    return batchStatus.id;
  }

  public List<MemberInfo> getListMembers(String listId) throws IOException, MailchimpException {
    return getListMembers(listId, null, null);
  }

  public List<MemberInfo> getListMembers(String listId, String status, Calendar sinceLastChanged) throws IOException, MailchimpException {
    GetMembersMethod getMembersMethod = new GetMembersMethod(listId);
    getMembersMethod.status = status;
    getMembersMethod.fields = "members.email_address,members.tags,total_items"; // HUGE performance improvement -- limit to only what we need
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

  public String archiveContactsBatch(String listId, List<String> emails) throws IOException, MailchimpException {
    List<DeleteMemberMethod> deleteMemberMethods = emails.stream()
        .map(email -> new DeleteMemberMethod(listId, email))
        .collect(Collectors.toList());

    StartBatchMethod startBatchMethod = new StartBatchMethod(deleteMemberMethods);
    BatchStatus batchStatus = client.execute(startBatchMethod);
    return batchStatus.id;
  }

  public Map<String, Set<String>> getContactsTags(List<MemberInfo> memberInfos) throws IOException, MailchimpException {
    Map<String, Set<String>> tagsMap = memberInfos.stream()
        .collect(Collectors.toMap(
            memberInfo -> memberInfo.email_address, memberInfo -> {
              List<MailchimpObject> tags = (List<MailchimpObject>) memberInfo.mapping.get(TAGS);
              return tags.stream().map(t -> t.mapping.get(TAG_NAME).toString()).collect(Collectors.toSet());
            }
        ));
    return tagsMap;
  }

  public String updateContactTagsBatch(String listId, List<EmailContact> emailContacts) throws IOException, MailchimpException {
    List<EditMemberMethod.AddorRemoveTag> editMemberMethods =
        emailContacts.stream()
            .map(emailContact -> {
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
            }).collect(Collectors.toList());

    StartBatchMethod startBatchMethod = new StartBatchMethod(editMemberMethods);
    BatchStatus batchStatus = client.execute(startBatchMethod);
    return batchStatus.id;
  }

  public BatchStatus getBatchStatus(String batchStatusId) throws IOException, MailchimpException {
    GetBatchStatusMethod getBatchStatusMethod = new GetBatchStatusMethod(batchStatusId);
    return client.execute(getBatchStatusMethod);
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

  public record EmailContact(String email, Set<String> activeTags, Set<String> inactiveTags) {};

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
}
