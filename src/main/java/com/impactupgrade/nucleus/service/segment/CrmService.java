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
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.OpportunityEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;

import java.util.Calendar;
import java.util.List;
import java.util.Optional;

public interface CrmService extends SegmentService {

  // TODO: As we gain more granular search methods, we should instead think through general purpose options
  //  that take filter as arguments (or a Search object).

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // GENERAL PURPOSE
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  Optional<CrmAccount> getAccountById(String id) throws Exception;
  // TODO: Business Donations coming soon, but not all CRMs support email at the company/account level.
//  Optional<CrmAccount> getAccountByEmail(String email) throws Exception;
  Optional<CrmContact> getContactById(String id) throws Exception;
  Optional<CrmContact> getContactByEmail(String email) throws Exception;
  Optional<CrmContact> getContactByPhone(String phone) throws Exception;
  String insertAccount(CrmAccount crmAccount) throws Exception;
  void updateAccount(CrmAccount crmAccount) throws Exception;
  String insertContact(CrmContact crmContact) throws Exception;
  void updateContact(CrmContact crmContact) throws Exception;
  // TODO: Business Donations coming soon.
//  boolean hasSecondaryAffiliation(String crmAccountId, String crmContactId) throws Exception;
//  void insertSecondaryAffiliation(String crmAccountId, String crmContactId) throws Exception;
  void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception;
  List<CrmContact> getContactsFromList(String listId) throws Exception;
  void addContactToList(CrmContact crmContact, String listId) throws Exception;
  void removeContactFromList(CrmContact crmContact, String listId) throws Exception;
  Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception;
  List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception;

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
  Optional<CrmDonation> getDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  void insertDonationReattempt(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  void insertDonationDeposit(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
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

  List <CrmContact> getContactsSince(Calendar calendar) throws Exception;
  List<CrmContact> getDonorsSince(Calendar calendar) throws Exception; //TODO Implement (look for donations then get the contacts in implementation)

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // BULK UTILS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  void processBulkImport(List<CrmImportEvent> importEvents) throws Exception;
  void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception;

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // MISC
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  Optional<CrmUser> getUserById(String id) throws Exception;
}
