/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
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
import com.impactupgrade.nucleus.model.UpdateRecurringDonationEvent;
import com.impactupgrade.nucleus.model.PagedResults;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface CrmService extends SegmentService {

  // NOTE: Methods often receive a whole list of lookups that we're about to process to this all at once. We then let
  // the implementations decide how to implement them in the most performant way. Some APIs may solely allow retrieval
  // one at a time. Others, like SFDC's SOQL, may allow clauses like "WHERE IN (<list>)" in queries, allowing us to
  // retrieve large batches all at once. This is SUPER important, especially for SFDC, where monthly API limits are in
  // play...

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // GENERAL PURPOSE
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  Optional<CrmAccount> getAccountById(String id) throws Exception;
  default List<CrmAccount> getAccountsByIds(List<String> ids, String... extraFields) throws Exception {
    List<CrmAccount> accounts = new ArrayList<>();
    for (String id : ids) {
      Optional<CrmAccount> account = getAccountById(id);
      account.ifPresent(accounts::add);
    }
    return accounts;
  }
  default List<CrmAccount> getAccountsByEmails(List<String> emails) throws Exception {
    List<CrmAccount> accounts = new ArrayList<>();
    for (String email : emails) {
      accounts.addAll(searchAccounts(AccountSearch.byEmail(email)).getResults());
    }
    return accounts;
  }
  PagedResults<CrmAccount> searchAccounts(AccountSearch accountSearch) throws Exception;
  String insertAccount(CrmAccount crmAccount) throws Exception;
  void updateAccount(CrmAccount crmAccount) throws Exception;
  void addAccountToCampaign(CrmAccount crmAccount, String campaignId, String status) throws Exception;
  // TODO: For now, need this to clean up orphaned accounts (see ContactService). But could eventually expand it
  //  to be a full-blown cascade-delete, much like what we do in IT cleanup.
  void deleteAccount(String accountId) throws Exception;

  Optional<CrmContact> getContactById(String id) throws Exception;
  Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception;
  default List<CrmContact> getContactsByIds(List<String> ids, String... extraFields) throws Exception {
    List<CrmContact> contacts = new ArrayList<>();
    for (String id : ids) {
      Optional<CrmContact> contact = getContactById(id);
      contact.ifPresent(contacts::add);
    }
    return contacts;
  }
  default List<CrmContact> getContactsByEmails(List<String> emails, String... extraFields) throws Exception {
    List<CrmContact> contacts = new ArrayList<>();
    for (String email : emails) {
      contacts.addAll(searchContacts(ContactSearch.byEmail(email)).getResults());
    }
    return contacts;
  }
  default List<CrmContact> getContactsByPhones(List<String> phones) throws Exception {
    List<CrmContact> contacts = new ArrayList<>();
    for (String phone : phones) {
      contacts.addAll(searchContacts(ContactSearch.byPhone(phone)).getResults());
    }
    return contacts;
  }
  PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) throws Exception;
  Map<String, String> getContactLists(CrmContactListType listType) throws Exception;
  String insertContact(CrmContact crmContact) throws Exception;
  void updateContact(CrmContact crmContact) throws Exception;
  void addContactToCampaign(CrmContact crmContact, String campaignId, String status) throws Exception;
  List<CrmContact> getContactsFromList(String listId) throws Exception;
  void addContactToList(CrmContact crmContact, String listId) throws Exception;
  void removeContactFromList(CrmContact crmContact, String listId) throws Exception;

  String insertOpportunity(CrmOpportunity crmOpportunity) throws Exception;

  // transaction id, secondary id, refund id, etc.
  // we also need account/contact since some CRMs will not allow transaction retrieval without providing the constituent
  List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds, String accountId, String contactId) throws Exception;
  List<CrmDonation> getDonationsByCustomerId(String customerId) throws Exception;
  String insertDonation(CrmDonation crmDonation) throws Exception;
  void updateDonation(CrmDonation crmDonation) throws Exception;
  void refundDonation(CrmDonation crmDonation) throws Exception;
  void insertDonationDeposit(List<CrmDonation> crmDonations) throws Exception;
  List<CrmDonation> getDonations(Calendar updatedAfter) throws Exception;

  // Some CRMs do not have full-blown notions of RDs, so no RD ID. Searching by the payment gateway's
  // subscription is at times the only option.
  default Optional<CrmRecurringDonation> getRecurringDonation(String id, String subscriptionId, String accountId, String contactId) throws Exception {
    Optional<CrmRecurringDonation> crmRecurringDonation = Optional.empty();

    if (!Strings.isNullOrEmpty(id)) {
      crmRecurringDonation = getRecurringDonationById(id);
    }

    if (crmRecurringDonation.isEmpty() && !Strings.isNullOrEmpty(subscriptionId)) {
      crmRecurringDonation = getRecurringDonationBySubscriptionId(subscriptionId, accountId, contactId);
    }

    return crmRecurringDonation;
  }
  Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception;
  // Most CRMs do not need the accountId/contactId to retrieve RDs, with notable exceptions like Virtuous.
  // We don't even always have the accountId/contactId available, like when we're attempting to figure out who a donor
  // is using past donations as a last resort in ContactService.
  Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId, String accountId,
      String contactId) throws Exception;
  List<CrmRecurringDonation> searchAllRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception;
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
  void updateRecurringDonation(UpdateRecurringDonationEvent updateRecurringDonationEvent) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // COMMUNICATION SYNC
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // Must use PagedResults due to syncs sometimes being massive and requiring a large amount of memory.
  PagedResults<CrmContact> getEmailContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception;
  PagedResults<CrmAccount> getEmailAccounts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception;
  PagedResults<CrmContact> getSmsContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception;
  // Special methods to retrieve all emails in the system, used for mass archival logic. For CRMs that allow control
  // over fields returned (SFDC, HS), override these and limit to email address only -- preserve memory.
  default Set<String> getAllContactEmails(EnvironmentConfig.CommunicationList communicationList) throws Exception {
    Set<String> emails = new HashSet<>();
    PagedResults<CrmContact> contactPagedResults = getEmailContacts(null, communicationList);
    for (PagedResults.ResultSet<CrmContact> resultSet : contactPagedResults.getResultSets()) {
      do {
        for (CrmContact crmContact : resultSet.getRecords()) {
          if (crmContact.canReceiveEmail()) {
            emails.add(crmContact.email.toLowerCase(Locale.ROOT));
          }
        }
        if (!Strings.isNullOrEmpty(resultSet.getNextPageToken())) {
          resultSet = queryMoreContacts(resultSet.getNextPageToken());
        } else {
          resultSet = null;
        }
      } while (resultSet != null);
    }
    return emails;
  }
  default Set<String> getAllAccountEmails(EnvironmentConfig.CommunicationList communicationList) throws Exception {
    Set<String> emails = new HashSet<>();
    PagedResults<CrmAccount> contactPagedResults = getEmailAccounts(null, communicationList);
    for (PagedResults.ResultSet<CrmAccount> resultSet : contactPagedResults.getResultSets()) {
      do {
        for (CrmAccount crmAccount : resultSet.getRecords()) {
          if (crmAccount.canReceiveEmail()) {
            emails.add(crmAccount.email.toLowerCase(Locale.ROOT));
          }
        }
        if (!Strings.isNullOrEmpty(resultSet.getNextPageToken())) {
          // next page
          resultSet = queryMoreAccounts(resultSet.getNextPageToken());
        } else {
          resultSet = null;
        }
      } while (resultSet != null);
    }
    return emails;
  }
  PagedResults<CrmContact> getDonorIndividualContacts(Calendar updatedSince) throws Exception;
  PagedResults<CrmAccount> getDonorOrganizationAccounts(Calendar updatedSince) throws Exception;

  // Map<Contact Id, List<Campaign Name>>
  // We pass the whole list of contacts that we're about to sync to this all at once, then let the implementations
  // decide how to implement it in the most performant way. Some APIs may solely allow retrieval one at a time.
  // Others, like SFDC's SOQL, may allow clauses like "WHERE IN (<list>)" in queries, allowing us to retrieve large
  // batches all at once. This is SUPER important, especially for SFDC, where monthly API limits are in play...
  Map<String, List<String>> getContactsCampaigns(List<CrmContact> crmContacts,
      EnvironmentConfig.CommunicationList communicationList) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // USERS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  Optional<CrmUser> getUserById(String id) throws Exception;
  Optional<CrmUser> getUserByEmail(String email) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // PAGINATION
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  PagedResults.ResultSet<CrmContact> queryMoreContacts(String queryLocator) throws Exception;
  PagedResults.ResultSet<CrmAccount> queryMoreAccounts(String queryLocator) throws Exception;

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
  // MISC
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  void batchInsertActivity(CrmActivity crmActivity) throws Exception;
  void batchUpdateActivity(CrmActivity crmActivity) throws Exception;
  List<CrmActivity> getActivitiesByExternalRefs(List<String> externalRefs) throws Exception;

  String insertNote(CrmNote crmNote) throws Exception;

  Map<String, String> getFieldOptions(String object) throws Exception;
  EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions();
  List<CrmCustomField> insertCustomFields(List<CrmCustomField> crmCustomFields);

}
