/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmCampaign;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmCustomField;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PagedResults;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CrmService extends SegmentService {

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
  // TODO: Business Donations coming soon, but not all CRMs support email at the company/account level.
//  Optional<CrmAccount> getAccountByEmail(String email) throws Exception;
  // TODO: replace usages with getAccountByCustomField?
  Optional<CrmAccount> getAccountByCustomerId(String customerId) throws Exception;
  Optional<CrmAccount> getAccountByCustomField(String customField, String customFieldValue, String... extraFields) throws Exception;
  default List<CrmAccount> getAccountsByCustomField(String customField, List<String> customFieldValues, String... extraFields) throws Exception {

  }
  String insertAccount(CrmAccount crmAccount) throws Exception;
  void updateAccount(CrmAccount crmAccount) throws Exception;
  // TODO: For now, need this to clean up orphaned accounts (see ContactService). But could eventually expand it
  //  to be a full-blown cascade-delete, much like what we do in IT cleanup.
  void deleteAccount(String accountId) throws Exception;

  Optional<CrmContact> getContactById(String id, String... extraFields) throws Exception;
  Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception;
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
  default List<CrmContact> getContactsByNames(List<String> names, String... extraFields) throws Exception {
    List<CrmContact> contacts = new ArrayList<>();
    for (String name : names) {
      Optional<CrmContact> contact = searchContacts(ContactSearch.byName(name), extraFields).getSingleResult();
      contact.ifPresent(contacts::add);
    }
    return contacts;
  }
  // TODO: Pagination initially needed for Twilio Frontline, but should we start introducing pagination across the board?
  PagedResults<CrmContact> searchContacts(ContactSearch contactSearch, String... extraFields) throws Exception;
  Optional<CrmContact> getContactByCustomField(String customField, String customFieldValue, String... extraFields) throws Exception;
  default List<CrmContact> getContactsByCustomFields(String customField, List<String> customFieldValues, String... extraFields) throws Exception {

  }
  String insertContact(CrmContact crmContact) throws Exception;
  void updateContact(CrmContact crmContact) throws Exception;
  // TODO: Business Donations coming soon.
//  boolean hasSecondaryAffiliation(String crmAccountId, String crmContactId) throws Exception;
//  void insertSecondaryAffiliation(String crmAccountId, String crmContactId) throws Exception;
  void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception;
  List<CrmContact> getContactsFromList(String listId) throws Exception;
  void addContactToList(CrmContact crmContact, String listId) throws Exception;
  void removeContactFromList(CrmContact crmContact, String listId) throws Exception;

  Optional<CrmOpportunity> getOpportunityById(String id, String... extraFields) throws Exception;
  default List<CrmOpportunity> getOpportunitiesByIds(List<String> ids, String... extraFields) throws Exception {
    List<CrmOpportunity> opportunities = new ArrayList<>();
    for (String id : ids) {
      Optional<CrmOpportunity> opportunity = getOpportunityById(id, extraFields);
      opportunity.ifPresent(opportunities::add);
    }
    return opportunities;
  }
  Optional<CrmOpportunity> getOpportunityByCustomField(String customField, String customFieldValue, String... extraFields) throws Exception;
  default List<CrmOpportunity> getOpportunitiesByCustomFields(String customField, List<String> customFieldValues, String... extraFields) throws Exception {

  }
  List<CrmOpportunity> searchOpportunities(String accountId, String contactId, Calendar date, double amount, String... extraFields) throws Exception;
  String insertOpportunity(CrmOpportunity crmOpportunity) throws Exception;
  String updateOpportunity(CrmOpportunity crmOpportunity) throws Exception;

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
  // default impl doesn't need accountId and contactId, so simply make use of getDonationsByTransactionIds
  default Optional<CrmDonation> getDonationByTransactionIds(List<String> transactionIds, String accountId, String contactId) throws Exception {
    List<CrmDonation> crmDonations = getDonationsByTransactionIds(transactionIds);

    if (crmDonations == null || crmDonations.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(crmDonations.get(0));
  }

  // helper method
  default Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
    return getDonationByTransactionIds(List.of(transactionId), null, null);
  }

  // We pass the whole list of donations that we're about to process to this all at once, then let the implementations
  // decide how to implement it in the most performant way. Some APIs may solely allow retrieval one at a time.
  // Others, like SFDC's SOQL, may allow clauses like "WHERE IN (<list>)" in queries, allowing us to retrieve large
  // batches all at once. This is SUPER important, especially for SFDC, where monthly API limits are in play...
  List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds) throws Exception;

  // We pass the whole list of donations that we're about to process to this all at once, then let the implementations
  // decide how to implement it in the most performant way. Some APIs may solely allow retrieval one at a time.
  // Others, like SFDC's SOQL, may allow clauses like "WHERE IN (<list>)" in queries, allowing us to retrieve large
  // batches all at once. This is SUPER important, especially for SFDC, where monthly API limits are in play...
//  default List<CrmDonation> getDonations(List<PaymentGatewayEvent> paymentGatewayEvents) throws Exception {
//    // use a set to prevent duplicates
//    Set<String> transactionIds = new HashSet<>();
//
//    for (PaymentGatewayEvent paymentGatewayEvent : paymentGatewayEvents) {
//      // SOME orgs create separate Opportunities for refunds, then use the Refund IDs in the standard Charge ID field.
//      if (!Strings.isNullOrEmpty(paymentGatewayEvent.getRefundId())) {
//        transactionIds.add(paymentGatewayEvent.getRefundId());
//      }
//      transactionIds.add(paymentGatewayEvent.getTransactionId());
//      if (!Strings.isNullOrEmpty(paymentGatewayEvent.getTransactionSecondaryId())) {
//        transactionIds.add(paymentGatewayEvent.getTransactionSecondaryId());
//      }
//    }
//
//    return getDonationsByTransactionIds(transactionIds.stream().toList());
//  }
  Optional<CrmDonation> getDonationByCustomField(String customField, String customFieldValue, String... extraFields) throws Exception;
  default List<CrmDonation> getDonationsByCustomFields(String customField, List<String> customFieldValues, String... extraFields) throws Exception {

  }
  String insertDonation(CrmDonation crmDonation) throws Exception;
  void updateDonation(CrmDonation crmDonation) throws Exception;
  void refundDonation(CrmDonation crmDonation) throws Exception;
  void insertDonationDeposit(List<CrmDonation> crmDonations) throws Exception;

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
  Optional<CrmRecurringDonation> getRecurringDonationById(String id, String... extraFields) throws Exception;
  default List<CrmRecurringDonation> getRecurringDonationsByIds(List<String> ids, String... extraFields) throws Exception {
    List<CrmRecurringDonation> rds = new ArrayList<>();
    for (String id : ids) {
      Optional<CrmRecurringDonation> rd = getRecurringDonationById(id, extraFields);
      rd.ifPresent(rds::add);
    }
    return rds;
  }
  Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId, String accountId, String contactId) throws Exception;
  List<CrmRecurringDonation> searchAllRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception;
  String insertRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception;
  String updateRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception;
  // Provide the full CRM model in case additional context is needed (close reasons, etc.)
  void closeRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CAMPAIGNS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  Optional<CrmCampaign> getCampaignByName(String name, String... extraFields) throws Exception;
  default List<CrmCampaign> getCampaignsByNames(List<String> names, String... extraFields) throws Exception {
    List<CrmCampaign> campaigns = new ArrayList<>();
    for (String name : names) {
      Optional<CrmCampaign> campaign = getCampaignByName(name, extraFields);
      campaign.ifPresent(campaigns::add);
    }
    return campaigns;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // BATCH OPERATIONS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  default void batchInsert(CrmAccount crmAccount) throws Exception {
    // default to simply using one-by-one for CRMs that don't support batching
    insertAccount(crmAccount);
  }
  default void batchUpdate(CrmAccount crmAccount) throws Exception {
    // default to simply using one-by-one for CRMs that don't support batching
    updateAccount(crmAccount);
  }
  default void batchInsert(CrmContact crmContact) throws Exception {
    // default to simply using one-by-one for CRMs that don't support batching
    insertContact(crmContact);
  }
  default void batchUpdate(CrmContact crmContact) throws Exception {
    // default to simply using one-by-one for CRMs that don't support batching
    updateContact(crmContact);
  }
  default void batchInsert(CrmRecurringDonation crmRecurringDonation) throws Exception {
    // default to simply using one-by-one for CRMs that don't support batching
    insertRecurringDonation(crmRecurringDonation);
  }
  default void batchUpdate(CrmRecurringDonation crmRecurringDonation) throws Exception {
    // default to simply using one-by-one for CRMs that don't support batching
    updateRecurringDonation(crmRecurringDonation);
  }
  default void batchInsert(CrmOpportunity crmOpportunity) throws Exception {
    // default to simply using one-by-one for CRMs that don't support batching
    insertOpportunity(crmOpportunity);
  }
  default void batchUpdate(CrmOpportunity crmOpportunity) throws Exception {
    // default to simply using one-by-one for CRMs that don't support batching
    updateOpportunity(crmOpportunity);
  }
  default void batchInsert(CrmDonation crmDonation) throws Exception {
    // default to simply using one-by-one for CRMs that don't support batching
    insertDonation(crmDonation);
  }
  default void batchUpdate(CrmDonation crmDonation) throws Exception {
    // default to simply using one-by-one for CRMs that don't support batching
    updateDonation(crmDonation);
  }
  default void batchFlush() throws Exception {
    // default to no-op
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // EMAIL SYNC
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  List<CrmContact> getEmailContacts(Calendar updatedSince, EnvironmentConfig.EmailList emailList) throws Exception;
  // Map<Contact Id, List<Campaign Name>>
  // We pass the whole list of contacts that we're about to sync to this all at once, then let the implementations
  // decide how to implement it in the most performant way. Some APIs may solely allow retrieval one at a time.
  // Others, like SFDC's SOQL, may allow clauses like "WHERE IN (<list>)" in queries, allowing us to retrieve large
  // batches all at once. This is SUPER important, especially for SFDC, where monthly API limits are in play...
  Map<String, List<String>> getActiveCampaignsByContactIds(List<String> contactIds) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Users
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
  // PORTAL FIELD UTILS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  Map<String, String> getContactLists() throws Exception;
  Map<String, String> getFieldOptions(String object) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // MISC
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  String insertTask(CrmTask crmTask) throws Exception;

  List<CrmCustomField> insertCustomFields(List<CrmCustomField> crmCustomFields);

  EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions();

}
