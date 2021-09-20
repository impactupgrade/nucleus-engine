package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmContact;

import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmailPlatformService extends SegmentService {

  List<CrmContact> getListMembers(String listName) throws Exception;
  Optional<CrmContact> getContactByEmail(String listName, String email) throws Exception;

  // TODO: May not need these, but keep for now.
  Collection<String> getContactGroupIds(String listName, CrmContact crmContact) throws Exception;
  List<String> getContactTags(String listName, CrmContact crmContact) throws Exception;

  void upsertContact(String listName, CrmContact crmContact) throws Exception;
  void unsubscribeContact(String listName, String email) throws Exception;
  void addTagToContact(String listName, CrmContact crmContact, String tag) throws Exception;

  void syncContacts(Calendar since) throws Exception;
  void syncDonors(Calendar since) throws Exception;
}
