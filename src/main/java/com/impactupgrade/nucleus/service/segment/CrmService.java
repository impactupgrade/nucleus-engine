/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.model.CrmUser;

import java.util.List;
import java.util.Optional;

public interface CrmService {

  // TODO: As we gain more granular search methods, we should instead think through general purpose options
  //  that take filter as arguments (or a Search object).

  Optional<CrmAccount> getAccountById(String id) throws Exception;

  Optional<CrmContact> getContactById(String id) throws Exception;
  Optional<CrmContact> getContactByEmail(String email) throws Exception;
  Optional<CrmContact> getContactByPhone(String phone) throws Exception;

  List<CrmDonation> getLastMonthDonationsByAccountId(String accountId) throws Exception;
  // TODO: potentially a performance issue for long-term donors
  List<CrmDonation> getDonationsByAccountId(String accountId) throws Exception;

  Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception;
  List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception;

  Optional<CrmUser> getUserById(String id) throws Exception;

  String insertContact(CrmContact crmContact) throws Exception;
  void updateContact(CrmContact crmContact) throws Exception;

  void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception;
  void addContactToList(CrmContact crmContact, String listId) throws Exception;
  List<CrmContact> getContactsFromList(String listId) throws Exception;
  void removeContactFromList(CrmContact crmContact, String listId) throws Exception;

  void processBulkImport(List<CrmImportEvent> importEvents) throws Exception;
  void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception;
}
