package com.impactupgrade.nucleus.service.segment;

import java.util.Calendar;

public interface EmailPlatformService extends SegmentService {

  // TODO: May not need these...
//  List<CrmContact> getListMembers(String listName) throws Exception;
//  Optional<CrmContact> getContactByEmail(String listName, String email) throws Exception;
//
//  Collection<String> getContactGroupIds(String listName, CrmContact crmContact) throws Exception;
//  List<String> getContactTags(String listName, CrmContact crmContact) throws Exception;
//
//  void unsubscribeContact(String listName, String email) throws Exception;
//  void addTagToContact(String listName, CrmContact crmContact, String tag) throws Exception;

  void syncContacts(Calendar lastSync) throws Exception;
  void syncContacts() throws Exception;
}
