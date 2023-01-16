package com.impactupgrade.nucleus.service.segment;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * Supports sending transactional emails and syncing contacts.
 */
public interface EmailService extends SegmentService {

  void sendEmailText(String subject, String body, boolean isHtml, String to, String from);
  void sendEmailTemplate(String subject, String template, Map<String, Object> data, List<String> tos, String from);

  // TODO: May not need these...
//  List<CrmContact> getListMembers(String listName) throws Exception;
//  Optional<CrmContact> getContactByEmail(String listName, String email) throws Exception;
//
//  Collection<String> getContactGroupIds(String listName, CrmContact crmContact) throws Exception;
//  List<String> getContactTags(String listName, CrmContact crmContact) throws Exception;
//
//  void unsubscribeContact(String listName, String email) throws Exception;
//  void addTagToContact(String listName, CrmContact crmContact, String tag) throws Exception;
//  void syncTags(Calendar lastSync) throws Exception;

  void syncContacts(Calendar lastSync) throws Exception;
  void syncUnsubscribes(Calendar lastSync) throws Exception;
  void upsertContact(String contactId) throws Exception;
}
