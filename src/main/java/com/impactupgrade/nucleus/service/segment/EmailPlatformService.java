package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmContact;

import java.util.List;

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

  void syncContacts(List<CrmContact> contacts) throws Exception;
  void syncDonors(List<CrmContact> contacts) throws Exception;
}
