/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.AccountSearch;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmActivity;
import com.impactupgrade.nucleus.model.CrmCampaign;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmContactListType;
import com.impactupgrade.nucleus.model.CrmCustomField;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmNote;
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PagedResults;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CrmService extends SegmentService {

  // NOTE: Methods often receive a whole list of lookups that we're about to process to this all at once. We then let
  // the implementations decide how to implement them in the most performant way. Some APIs may solely allow retrieval
  // one at a time. Others, like SFDC's SOQL, may allow clauses like "WHERE IN (<list>)" in queries, allowing us to
  // retrieve large batches all at once. This is SUPER important, especially for SFDC, where monthly API limits are in
  // play...

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // GENERAL PURPOSE
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  Optional<CrmAccount> getAccountById(String id, String... extraFields) throws Exception;
  default List<CrmAccount> getAccountsByIds(List<String> ids, String... extraFields) throws Exception {
    List<CrmAccount> accounts = new ArrayList<>();
    for (String id : ids) {
      Optional<CrmAccount> account = getAccountById(id, extraFields);
      account.ifPresent(accounts::add);
    }
    return accounts;
  }
  List<CrmAccount> getAccountsByEmails(List<String> emails, String... extraFields) throws Exception;
  List<CrmAccount> searchAccounts(AccountSearch accountSearch, String... extraFields) throws Exception;
  String insertAccount(CrmAccount crmAccount) throws Exception;
  void updateAccount(CrmAccount crmAccount) throws Exception;
  void addAccountToCampaign(CrmAccount crmAccount, String campaignId) throws Exception;
  // TODO: For now, need this to clean up orphaned accounts (see ContactService). But could eventually expand it
  //  to be a full-blown cascade-delete, much like what we do in IT cleanup.
  void deleteAccount(String accountId) throws Exception;

  Optional<CrmContact> getContactById(String id, String... extraFields) throws Exception;
  default List<CrmContact> getContactsByIds(List<String> ids, String... extraFields) throws Exception {
    List<CrmContact> contacts = new ArrayList<>();
    for (String id : ids) {
      Optional<CrmContact> contact = getContactById(id, extraFields);
      contact.ifPresent(contacts::add);
    }
    return contacts;
  }
  default List<CrmContact> getContactsByEmails(List<String> emails, String... extraFields) throws Exception {
    List<CrmContact> contacts = new ArrayList<>();
    for (String email : emails) {
      Optional<CrmContact> contact = searchContacts(ContactSearch.byEmail(email), extraFields).getSingleResult();
      contact.ifPresent(contacts::add);
    }
    return contacts;
  }
  PagedResults<CrmContact> searchContacts(ContactSearch contactSearch, String... extraFields) throws Exception;
  String insertContact(CrmContact crmContact) throws Exception;
  void updateContact(CrmContact crmContact) throws Exception;
  void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception;
  List<CrmContact> getContactsFromList(String listId, String... extraFields) throws Exception;
  void addContactToList(CrmContact crmContact, String listId) throws Exception;
  void removeContactFromList(CrmContact crmContact, String listId) throws Exception;

  String insertOpportunity(CrmOpportunity crmOpportunity) throws Exception;

  // transaction id, secondary id, refund id, etc.
  // we also need account/contact since some CRMs will not allow transaction retrieval without providing the constituent
  List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds, String accountId, String contactId, String... extraFields) throws Exception;
  String insertDonation(CrmDonation crmDonation) throws Exception;
  void updateDonation(CrmDonation crmDonation) throws Exception;
  void refundDonation(CrmDonation crmDonation) throws Exception;
  void insertDonationDeposit(List<CrmDonation> crmDonations) throws Exception;

  // Some CRMs do not have full-blown notions of RDs, so no RD ID. Searching by the payment gateway's
  // subscription is at times the only option.
  default Optional<CrmRecurringDonation> getRecurringDonation(String id, String subscriptionId, String accountId, String contactId, String... extraFields) throws Exception {
    Optional<CrmRecurringDonation> crmRecurringDonation = Optional.empty();

    if (!Strings.isNullOrEmpty(id)) {
      crmRecurringDonation = getRecurringDonationById(id, extraFields);
    }

    if (crmRecurringDonation.isEmpty() && !Strings.isNullOrEmpty(subscriptionId)) {
      crmRecurringDonation = getRecurringDonationBySubscriptionId(subscriptionId, accountId, contactId, extraFields);
    }

    return crmRecurringDonation;
  }
  Optional<CrmRecurringDonation> getRecurringDonationById(String id, String... extraFields) throws Exception;
  Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId, String accountId, String contactId, String... extraFields) throws Exception;
  List<CrmRecurringDonation> searchAllRecurringDonations(ContactSearch contactSearch, String... extraFields) throws Exception;
  String insertRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception;
//  void updateRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception;
  // Provide the full CRM model in case additional context is needed (close reasons, etc.)
  void closeRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception;

  String insertCampaign(CrmCampaign crmCampaign) throws Exception;
  void updateCampaign(CrmCampaign crmCampaign) throws Exception;
  Optional<CrmCampaign> getCampaignByExternalReference(String externalReference) throws Exception;
  void deleteCampaign(String campaignId) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // BATCH OPERATIONS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  default void batchUpdateAccount(CrmAccount crmAccount) throws Exception {
    // default to simply updating one-by-one for CRMs that don't support batching
    updateAccount(crmAccount);
  }
  default void batchUpdateContact(CrmContact crmContact) throws Exception {
    // default to simply updating one-by-one for CRMs that don't support batching
    updateContact(crmContact);
  }
  default void batchFlush() throws Exception {
    // default to no-op
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // RECURRING DONATION MANAGEMENT EVENTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // TODO
  void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // COMMUNICATION SYNC
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  List<CrmContact> getEmailContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception;
  List<CrmAccount> getEmailAccounts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception;
  List<CrmContact> getSmsContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception;
  Optional<CrmContact> getFilteredContactById(String id, String filter, String... extraFields) throws Exception;
  // Map<Contact Id, List<Campaign Name>>
  // We pass the whole list of contacts that we're about to sync to this all at once, then let the implementations
  // decide how to implement it in the most performant way. Some APIs may solely allow retrieval one at a time.
  // Others, like SFDC's SOQL, may allow clauses like "WHERE IN (<list>)" in queries, allowing us to retrieve large
  // batches all at once. This is SUPER important, especially for SFDC, where monthly API limits are in play...
  Map<String, List<String>> getContactCampaignsByContactIds(List<String> contactIds,
      EnvironmentConfig.CommunicationList communicationList) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // USERS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  List<CrmUser> getUsers() throws Exception;
  Optional<CrmUser> getUserById(String id) throws Exception;
  Optional<CrmUser> getUserByEmail(String email) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // STATS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // Retrieves the total value of donations using a specified, CRM-specific filter. Useful for Donation Spring's
  // campaign bar. IMPORTANT: Each CRM service is expected to implement this in the most performant way possible. As an
  // example, SFDC has a sum() function, but HubSpot does not.
  double getDonationsTotal(String filter) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // BULK UTILS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // TODO
  void processBulkImport(List<CrmImportEvent> importEvents) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // PORTAL FIELD UTILS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  Map<String, String> getContactLists(CrmContactListType listType) throws Exception;
  Map<String, String> getFieldOptions(String object) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // MISC
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  String insertActivity(CrmActivity crmActivity) throws Exception;
  String updateActivity(CrmActivity crmActivity) throws Exception;
  Optional<CrmActivity> getActivityByExternalRef(String externalRef) throws Exception;
  String insertNote(CrmNote crmNote) throws Exception;

  List<CrmCustomField> insertCustomFields(List<CrmCustomField> crmCustomFields);

  EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions();

}
