/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.ecwid.maleorang.MailchimpException;
import com.ecwid.maleorang.MailchimpObject;
import com.ecwid.maleorang.method.v3_0.lists.members.EditMemberMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.GetMemberMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.GetMembersMethod;
import com.ecwid.maleorang.method.v3_0.lists.members.MemberInfo;
import com.impactupgrade.nucleus.environment.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


public class MailchimpClient {

  private static final Logger log = LogManager.getLogger(MailchimpClient.class);

  public static final String SUBSCRIBED = "subscribed";
  public static final String UNSUBSCRIBED = "unsubscribed";
  public static final String FIRST_NAME = "FNAME";
  public static final String LAST_NAME = "LNAME";
  public static final String PHONE_NUMBER = "PHONE";
  public static final String ADDRESS = "ADDRESS";
  public static final String TAGS = "tags";
  public static final String TAG_COUNT = "tags_count";
  public static final String TAG_NAME = "name";
  public static final String TAG_STATUS = "status";
  public static final String TAG_ACTIVE = "active";
  public static final String TAG_INACTIVE = "inactive";

  protected final com.ecwid.maleorang.MailchimpClient client;

  public MailchimpClient(Environment env) {
    client = new com.ecwid.maleorang.MailchimpClient(env.getConfig().mailchimp.secretKey);
  }

  public MemberInfo getContactInfo(String listId, String contactEmail) throws IOException, MailchimpException {
    GetMemberMethod getMemberMethod = new GetMemberMethod(listId, contactEmail);
    return client.execute(getMemberMethod);
  }

  public void upsertContact(String listId, MemberInfo contact) throws IOException {
    try {
      EditMemberMethod.CreateOrUpdate upsertMemberMethod = new EditMemberMethod.CreateOrUpdate(listId, contact.email_address);
      upsertMemberMethod.status_if_new = contact.status;
      upsertMemberMethod.mapping.putAll(contact.mapping);
      upsertMemberMethod.interests.mapping.putAll(contact.interests.mapping);
      client.execute(upsertMemberMethod);
    } catch (MailchimpException e) {
      log.warn("Mailchimp upsertContact failed: {}", e.description);
    }
  }

  public List<MemberInfo> getListMembers(String listId) throws IOException, MailchimpException {
    GetMembersMethod getMembersMethod = new GetMembersMethod(listId);
    GetMembersMethod.Response getMemberResponse = client.execute(getMembersMethod);
    return getMemberResponse.members;
  }

  public void unsubscribeContact(String listId, String email) throws IOException, MailchimpException {
    EditMemberMethod editMemberMethod = new EditMemberMethod.Update(listId, email);
    editMemberMethod.status = UNSUBSCRIBED;
    client.execute(editMemberMethod);
  }

  // TODO: TEST THIS
  public Set<String> getContactGroupIds(String listId, String contactEmail) throws IOException, MailchimpException {
    MemberInfo contact = getContactInfo(listId, contactEmail);
    return contact.interests.mapping.keySet();
  }

  // TODO: TEST THIS
  public List<String> getContactTags(String listId, String contactEmail) throws IOException, MailchimpException {
    MemberInfo member = getContactInfo(listId, contactEmail);
    List<MailchimpObject> tags = (List<MailchimpObject>) member.mapping.get(TAGS);
    return tags.stream().map(t -> t.mapping.get(TAG_NAME).toString()).collect(Collectors.toList());
  }

  // TODO: TEST THIS
  public void addTag(String listId, String contactEmail, String tagName) throws IOException, MailchimpException {
    MailchimpObject newTag = new MailchimpObject();
    newTag.mapping.put(TAG_STATUS, TAG_ACTIVE);
    newTag.mapping.put(TAG_NAME, tagName);
    EditMemberMethod.AddorRemoveTag editMemberMethod = new EditMemberMethod.AddorRemoveTag(listId, contactEmail);
    // TODO: Necessary? Is there more of an add method that would preserve the existing list?
    editMemberMethod.tags = getContactInfo(listId, contactEmail).tags;
    editMemberMethod.tags.add(newTag);
    client.execute(editMemberMethod);
  }

  // TODO: TEST THIS
  public void removeTag(String listId, String contactEmail, String tagName) throws IOException, MailchimpException {
    MailchimpObject tag = new MailchimpObject();
    tag.mapping.put(TAG_STATUS, TAG_INACTIVE);
    tag.mapping.put(TAG_NAME, tagName);
    EditMemberMethod.AddorRemoveTag editMemberMethod = new EditMemberMethod.AddorRemoveTag(listId, contactEmail);
    editMemberMethod.tags = getContactInfo(listId, contactEmail).tags;
    editMemberMethod.tags.remove(tag);
    client.execute(editMemberMethod);
  }
}
