package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmContact;

import java.util.Calendar;
import java.util.List;
import java.util.Optional;

public interface EmailPlatformService {

  List<String> getEmailsInList(String listName) throws Exception;
  List<CrmContact> getListOfContacts(String listName) throws Exception;

  Optional<CrmContact> getContactByEmail(String listName, String email) throws Exception;

  List<String> getContactGroups(CrmContact crmContact, String listName) throws Exception;

  List<String> getContactTags(String listName, CrmContact crmContact) throws Exception;

  void addContactToList(CrmContact crmContact, String listName) throws Exception;

  void updateContact(CrmContact contact, String listName) throws Exception;

  void unsubscribeContact(String email, String listName) throws Exception;

  void addContactToGroup(CrmContact crmContact, String listId, String groupName) throws Exception;

  void addTagToContact(String listName, CrmContact crmContact, String tag) throws Exception;

  void syncNewContacts(Calendar calendar) throws Exception;

  void syncNewDonors(Calendar calendar) throws Exception;

}
