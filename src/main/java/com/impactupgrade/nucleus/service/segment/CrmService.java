/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.OpportunityEvent;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface CrmService extends SegmentService {

  // TODO: As we gain more granular search methods, we should instead think through general purpose options
  //  that take filter as arguments (or a Search object).

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // GENERAL PURPOSE
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  Optional<CrmAccount> getAccountById(String id) throws Exception;
  default List<CrmAccount> getAccountsByIds(List<String> ids) throws Exception {
    List<CrmAccount> accounts = new ArrayList<>();
    for (String id : ids) {
      Optional<CrmAccount> account = getAccountById(id);
      account.ifPresent(accounts::add);
    }
    return accounts;
  }
  // TODO: Business Donations coming soon, but not all CRMs support email at the company/account level.
//  Optional<CrmAccount> getAccountByEmail(String email) throws Exception;
  Optional<CrmAccount> getAccountByCustomerId(String customerId) throws Exception;
  Optional<CrmContact> getContactById(String id) throws Exception;
  Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception;
  default List<CrmContact> getContactsByIds(List<String> ids) throws Exception {
    List<CrmContact> contacts = new ArrayList<>();
    for (String id : ids) {
      Optional<CrmContact> contact = getContactById(id);
      contact.ifPresent(contacts::add);
    }
    return contacts;
  }
  // TODO: Pagination initially needed for Twilio Frontline, but should we start introducing pagination across the board?
  PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) throws Exception;
  String insertAccount(CrmAccount crmAccount) throws Exception;
  void updateAccount(CrmAccount crmAccount) throws Exception;
  // TODO: For now, need this to clean up orphaned accounts (see ContactService). But could eventually expand it
  //  to be a full-blown cascade-delete, much like what we do in IT cleanup.
  void deleteAccount(String accountId) throws Exception;
  String insertContact(CrmContact crmContact) throws Exception;
  void updateContact(CrmContact crmContact) throws Exception;
  default void batchUpdateContact(CrmContact crmContact) throws Exception {
    // default to simply updating one-by-one for CRMs that don't support batching
    updateContact(crmContact);
  }
  default void batchFlush() throws Exception {
    // default to no-op
  }
  // TODO: Business Donations coming soon.
//  boolean hasSecondaryAffiliation(String crmAccountId, String crmContactId) throws Exception;
//  void insertSecondaryAffiliation(String crmAccountId, String crmContactId) throws Exception;
  void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception;
  List<CrmContact> getContactsFromList(String listId) throws Exception;
  void addContactToList(CrmContact crmContact, String listId) throws Exception;
  void removeContactFromList(CrmContact crmContact, String listId) throws Exception;
  Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception;
  // We pass the whole list of donations that we're about to process to this all at once, then let the implementations
  // decide how to implement it in the most performant way. Some APIs may solely allow retrieval one at a time.
  // Others, like SFDC's SOQL, may allow clauses like "WHERE IN (<list>)" in queries, allowing us to retrieve large
  // batches all at once. This is SUPER important, especially for SFDC, where monthly API limits are in play...
  default List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds) throws Exception {
    // By default, handle one at a time.
    List<CrmDonation> crmDonations = new ArrayList<>();
    for (String transactionId : transactionIds) {
      Optional<CrmDonation> crmDonation = getDonationByTransactionId(transactionId);
      crmDonation.ifPresent(crmDonations::add);
    }
    return crmDonations;
  }
  Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId) throws Exception;
  List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception;
  List<CrmRecurringDonation> searchOpenRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // DONATION EVENTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // We allow custom impls, but most orgs only insert the CrmAccount/CrmContact, so do that as a default.
  default String insertAccount(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return insertAccount(paymentGatewayEvent.getCrmAccount());
  }
  default String insertContact(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return insertContact(paymentGatewayEvent.getCrmContact());
  }
  // TODO: This works, but is a double hit on the API. Could refactor the by-transaction-id chain to allow multiple IDs, OR'd together.
  default Optional<CrmDonation> getDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // Start with the most-specific edge cases first (just in case), then fall back to the defaults.
    Optional<CrmDonation> donation = Optional.empty();

    // SOME orgs create separate Opportunities for refunds, then use the Refund IDs in the standard Charge ID field.
    if (!Strings.isNullOrEmpty(paymentGatewayEvent.getRefundId())) {
      donation = getDonationByTransactionId(paymentGatewayEvent.getRefundId());
    }

    if (donation.isEmpty()) {
      donation = getDonationByTransactionId(paymentGatewayEvent.getTransactionId());
    }
    if (donation.isEmpty() && !Strings.isNullOrEmpty(paymentGatewayEvent.getTransactionSecondaryId())) {
      donation = getDonationByTransactionId(paymentGatewayEvent.getTransactionSecondaryId());
    }
    return donation;
  }
  // We pass the whole list of donations that we're about to process to this all at once, then let the implementations
  // decide how to implement it in the most performant way. Some APIs may solely allow retrieval one at a time.
  // Others, like SFDC's SOQL, may allow clauses like "WHERE IN (<list>)" in queries, allowing us to retrieve large
  // batches all at once. This is SUPER important, especially for SFDC, where monthly API limits are in play...
  default List<CrmDonation> getDonations(List<PaymentGatewayEvent> paymentGatewayEvents) throws Exception {
    // use a set to prevent duplicates
    Set<String> transactionIds = new HashSet<>();

    for (PaymentGatewayEvent paymentGatewayEvent : paymentGatewayEvents) {
      // SOME orgs create separate Opportunities for refunds, then use the Refund IDs in the standard Charge ID field.
      if (!Strings.isNullOrEmpty(paymentGatewayEvent.getRefundId())) {
        transactionIds.add(paymentGatewayEvent.getRefundId());
      }
      transactionIds.add(paymentGatewayEvent.getTransactionId());
      if (!Strings.isNullOrEmpty(paymentGatewayEvent.getTransactionSecondaryId())) {
        transactionIds.add(paymentGatewayEvent.getTransactionSecondaryId());
      }
    }

    return getDonationsByTransactionIds(transactionIds.stream().toList());
  }
  String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  void insertDonationReattempt(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  void insertDonationDeposit(List<PaymentGatewayEvent> paymentGatewayEvents) throws Exception;
  default Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return getRecurringDonationBySubscriptionId(paymentGatewayEvent.getSubscriptionId());
  }
  String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // RECURRING DONATION MANAGEMENT EVENTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  Optional<CrmRecurringDonation> getRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception;
  void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception;
  void closeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // NON-DONATION OPPORTUNITY EVENTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // We allow custom impls, but most orgs only insert the CrmContact, so do that as a default.
  default String insertContact(OpportunityEvent opportunityEvent) throws Exception {
    return insertContact(opportunityEvent.getCrmContact());
  }
  // We allow custom impls, but most orgs only insert the CrmContact, so do that as a default.
  default void updateContact(OpportunityEvent opportunityEvent) throws Exception {
    updateContact(opportunityEvent.getCrmContact());
  }
  String insertOpportunity(OpportunityEvent opportunityEvent) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // DONOR PORTAL
  // TODO: Hoping we can genericize these (or move them to DP-specific projects)
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // TODO: potentially a performance issue for long-term donors
  List<CrmDonation> getDonationsByAccountId(String accountId) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // EMAIL SYNC
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  List<CrmContact> getEmailContacts(Calendar updatedSince, String filter) throws Exception;
  // Map<Contact Id, List<Campaign Name>>
  // We pass the whole list of contacts that we're about to sync to this all at once, then let the implementations
  // decide how to implement it in the most performant way. Some APIs may solely allow retrieval one at a time.
  // Others, like SFDC's SOQL, may allow clauses like "WHERE IN (<list>)" in queries, allowing us to retrieve large
  // batches all at once. This is SUPER important, especially for SFDC, where monthly API limits are in play...
  Map<String, List<String>> getActiveCampaignsByContactIds(List<String> contactIds) throws Exception;

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

  void processBulkImport(List<CrmImportEvent> importEvents) throws Exception;
  void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // MISC
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  Optional<CrmUser> getUserById(String id) throws Exception;
  Optional<CrmUser> getUserByEmail(String email) throws Exception;
  String insertTask(CrmTask crmTask) throws Exception;

  EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions();

}
