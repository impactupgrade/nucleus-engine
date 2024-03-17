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
import com.impactupgrade.nucleus.model.CrmNote;
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.model.UpdateRecurringDonationEvent;
import org.apache.commons.lang3.tuple.Pair;

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
  Optional<CrmAccount> getAccountByUniqueField(String customField, String customFieldValue, String... extraFields) throws Exception;
  default List<CrmAccount> getAccountsByUniqueField(String customField, List<String> customFieldValues, String... extraFields) throws Exception {
    List<CrmAccount> accounts = new ArrayList<>();
    for (String customFieldValue : customFieldValues) {
      Optional<CrmAccount> account = getAccountByUniqueField(customField, customFieldValue, extraFields);
      account.ifPresent(accounts::add);
    }
    return accounts;
  }
  default List<CrmAccount> getAccountsByNames(List<String> names, String... extraFields) throws Exception {
    List<CrmAccount> accounts = new ArrayList<>();
    for (String name : names) {
      Optional<CrmAccount> account = searchAccounts(AccountSearch.byKeywords(name), extraFields).getSingleResult();
      account.ifPresent(accounts::add);
    }
    return accounts;
  }
  PagedResults<CrmAccount> searchAccounts(AccountSearch accountSearch, String... extraFields) throws Exception;
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
  default List<CrmContact> getContactsByNames(List<Pair<String, String>> names, String... extraFields) throws Exception {
    List<CrmContact> contacts = new ArrayList<>();
    for (Pair<String, String> name : names) {
      Optional<CrmContact> contact = searchContacts(ContactSearch.byName(name.getLeft(), name.getRight()), extraFields).getSingleResult();
      contact.ifPresent(contacts::add);
    }
    return contacts;
  }
  Optional<CrmContact> getContactByUniqueField(String customField, String customFieldValue, String... extraFields) throws Exception;
  default List<CrmContact> getContactsByUniqueField(String customField, List<String> customFieldValues, String... extraFields) throws Exception {
    List<CrmContact> contacts = new ArrayList<>();
    for (String customFieldValue : customFieldValues) {
      Optional<CrmContact> contact = getContactByUniqueField(customField, customFieldValue, extraFields);
      contact.ifPresent(contacts::add);
    }
    return contacts;
  }
  PagedResults<CrmContact> searchContacts(ContactSearch contactSearch, String... extraFields) throws Exception;
  Map<String, String> getContactLists(CrmContactListType listType) throws Exception;
  String insertContact(CrmContact crmContact) throws Exception;
  void updateContact(CrmContact crmContact) throws Exception;
  void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception;
  List<CrmContact> getContactsFromList(String listId, String... extraFields) throws Exception;
  void addContactToList(CrmContact crmContact, String listId) throws Exception;
  void removeContactFromList(CrmContact crmContact, String listId) throws Exception;

  void updateOpportunity(CrmOpportunity crmOpportunity) throws Exception;
  String insertOpportunity(CrmOpportunity crmOpportunity) throws Exception;

  Optional<CrmDonation> getDonationById(String id, String... extraFields) throws Exception;
  default List<CrmDonation> getDonationsByIds(List<String> ids, String... extraFields) throws Exception {
    List<CrmDonation> donations = new ArrayList<>();
    for (String id : ids) {
      Optional<CrmDonation> donation = getDonationById(id, extraFields);
      donation.ifPresent(donations::add);
    }
    return donations;
  }
  // transaction id, secondary id, refund id, etc.
  // we also need account/contact since some CRMs will not allow transaction retrieval without providing the constituent
  List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds, String accountId, String contactId, String... extraFields) throws Exception;
  Optional<CrmDonation> getDonationByUniqueField(String customField, String customFieldValue, String... extraFields) throws Exception;
  default List<CrmDonation> getDonationsByUniqueField(String customField, List<String> customFieldValues, String... extraFields) throws Exception {
    List<CrmDonation> donations = new ArrayList<>();
    for (String customFieldValue : customFieldValues) {
      Optional<CrmDonation> donation = getDonationByCustomField(customField, customFieldValue, extraFields);
      donation.ifPresent(donations::add);
    }
    return donations;
  }
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
  default List<CrmRecurringDonation> getRecurringDonationsByIds(List<String> ids, String... extraFields) throws Exception {
    List<CrmRecurringDonation> rds = new ArrayList<>();
    for (String id : ids) {
      Optional<CrmRecurringDonation> rd = getRecurringDonationById(id, extraFields);
      rd.ifPresent(rds::add);
    }
    return rds;
  }
  Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId, String accountId, String contactId, String... extraFields) throws Exception;
  List<CrmRecurringDonation> searchAllRecurringDonations(ContactSearch contactSearch, String... extraFields) throws Exception;
  String insertRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception;
  void updateRecurringDonation(UpdateRecurringDonationEvent updateRecurringDonationEvent) throws Exception;
  // Provide the full CRM model in case additional context is needed (close reasons, etc.)
  void closeRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception;

  Optional<CrmCampaign> getCampaignByName(String name, String... extraFields) throws Exception;
  default List<CrmCampaign> getCampaignsByNames(List<String> names, String... extraFields) throws Exception {
    List<CrmCampaign> campaigns = new ArrayList<>();
    for (String name : names) {
      Optional<CrmCampaign> campaign = getCampaignByName(name, extraFields);
      campaign.ifPresent(campaigns::add);
    }
    return campaigns;
  }
  Optional<CrmCampaign> getCampaignByExternalReference(String externalReference, String... extraFields) throws Exception;
  String insertCampaign(CrmCampaign crmCampaign) throws Exception;
  void updateCampaign(CrmCampaign crmCampaign) throws Exception;
  void deleteCampaign(String campaignId) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // BATCH OPERATIONS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // default to simply updating one-by-one for CRMs that don't support batching

  default void batchInsertAccount(CrmAccount crmAccount) throws Exception {
    insertAccount(crmAccount);
  }
  default void batchUpdateAccount(CrmAccount crmAccount) throws Exception {
    updateAccount(crmAccount);
  }
  default void batchInsertContact(CrmContact crmContact) throws Exception {
    updateContact(crmContact);
  }
  default void batchUpdateContact(CrmContact crmContact) throws Exception {
    updateContact(crmContact);
  }
  default void batchInsertOpportunity(CrmOpportunity crmOpportunity) throws Exception {
    insertOpportunity(crmOpportunity);
  }
  default void batchUpdateOpportunity(CrmOpportunity crmOpportunity) throws Exception {
    updateOpportunity(crmOpportunity);
  }
  default void batchInsertRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
    insertRecurringDonation(crmRecurringDonation);
  }
  // TODO: How to handle when update() requires UpdateRecurringDonationEvent?
//  default void batchUpdateRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
//    updateRecurringDonation(crmRecurringDonation);
//  }
  default void batchInsertCampaign(CrmCampaign crmCampaign) throws Exception {
    insertCampaign(crmCampaign);
  }
  default void batchUpdateCampaign(CrmCampaign crmCampaign) throws Exception {
    updateCampaign(crmCampaign);
  }
  default void batchFlush() throws Exception {
    // default to no-op
  }

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
  Optional<CrmUser> getUserByEmail(String email) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // STATS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // Retrieves the total value of donations using a specified, CRM-specific filter. Useful for Donation Spring's
  // campaign bar. IMPORTANT: Each CRM service is expected to implement this in the most performant way possible. As an
  // example, SFDC has a sum() function, but HubSpot does not.
  double getDonationsTotal(String filter) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // MISC
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  String insertActivity(CrmActivity crmActivity) throws Exception;
  String updateActivity(CrmActivity crmActivity) throws Exception;
  Optional<CrmActivity> getActivityByExternalRef(String externalRef) throws Exception;
  String insertNote(CrmNote crmNote) throws Exception;

  Map<String, String> getFieldOptions(String object) throws Exception;
  EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions();
  List<CrmCustomField> insertCustomFields(List<CrmCustomField> crmCustomFields);
}
