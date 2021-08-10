package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmContact;

import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmailPlatformService extends SegmentService {

  List<CrmContact> getListOfContacts(String listName) throws Exception;

  Optional<CrmContact> getContactByEmail(String listName, String email) throws Exception;

  Collection<String> getContactGroupIds(String listName, CrmContact crmContact) throws Exception;

  List<String> getContactTags(String listName, CrmContact crmContact) throws Exception;

  void addContactToList(String listName, CrmContact crmContact) throws Exception;

  void updateContact(String listName, CrmContact crmContact) throws Exception;

  void removeContactFromList(String listName, String email) throws Exception;

  void addContactToGroup(String listName, CrmContact crmContact, String groupName) throws Exception;

  void addTagToContact(String listName, CrmContact crmContact, String tag) throws Exception;

  void syncNewContacts(Calendar calendar) throws Exception;

  void syncNewDonors(Calendar calendar) throws Exception;

}
