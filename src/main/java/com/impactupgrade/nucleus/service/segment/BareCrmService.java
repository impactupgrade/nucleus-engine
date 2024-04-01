/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
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

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * For DS clients, allow Nucleus tasks like Manage Recurring Donation to work without a CRM.
 * We could muddy the code to check for "Has a CRM?" all over. Or we can use this no-op flavor to simply drop all calls.
 *
 * Additionally, this allows clients to have a bare starting point for a CrmService, with the ability to override
 * only what they actually need.
 */
public class BareCrmService implements CrmService {

  @Override
  public Optional<CrmAccount> getAccountById(String id) throws Exception {
    return Optional.empty();
  }

  @Override
  public List<CrmAccount> getAccountsByEmails(List<String> emails) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public Optional<CrmContact> getContactById(String id) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmContact> getFilteredContactByEmail(String email, String filter) throws Exception {
    return Optional.empty();
  }

  @Override
  public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) throws Exception {
    return new PagedResults<>(Collections.emptyList(), 0, "");
  }

  @Override
  public List<CrmAccount> searchAccounts(AccountSearch accountSearch) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public String insertAccount(CrmAccount crmAccount) throws Exception {
    return null;
  }

  @Override
  public void updateAccount(CrmAccount crmAccount) throws Exception {

  }

  @Override
  public void deleteAccount(String accountId) throws Exception {

  }

  @Override
  public String insertContact(CrmContact crmContact) throws Exception {
    return null;
  }

  @Override
  public void updateContact(CrmContact crmContact) throws Exception {

  }

  @Override
  public void addAccountToCampaign(CrmAccount crmAccount, String campaignId, String status) throws Exception {

  }

  @Override
  public void addContactToCampaign(CrmContact crmContact, String campaignId, String status) throws Exception {

  }

  @Override
  public List<CrmContact> getContactsFromList(String listId) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public void addContactToList(CrmContact crmContact, String listId) throws Exception {

  }

  @Override
  public void removeContactFromList(CrmContact crmContact, String listId) throws Exception {

  }

  @Override
  public List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId, String accountId, String contactId) throws Exception {
    return Optional.empty();
  }

  @Override
  public List<CrmRecurringDonation> searchAllRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception {
    return Collections.emptyList();
  }
  @Override
  public String insertDonation(CrmDonation crmDonation) throws Exception {
    return null;
  }

  @Override
  public void updateDonation(CrmDonation crmDonation) throws Exception {

  }

  @Override
  public void refundDonation(CrmDonation crmDonation) throws Exception {

  }

  @Override
  public void insertDonationDeposit(List<CrmDonation> crmDonations) throws Exception {

  }

  @Override
  public String insertRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
    return null;
  }

  @Override
  public void closeRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {

  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    return Optional.empty();
  }

  @Override
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {

  }

  @Override
  public String insertOpportunity(CrmOpportunity crmOpportunity) throws Exception {
    return null;
  }

  @Override
  public List<CrmDonation> getDonationsUpdatedSince(Calendar updatedSince) throws Exception {
    return null;
  }

  @Override
  public String insertCampaign(CrmCampaign crmCampaign) throws Exception {
    return null;
  }

  @Override
  public void updateCampaign(CrmCampaign crmCampaign) throws Exception {

  }

  @Override
  public Optional<CrmCampaign> getCampaignByExternalReference(String externalReference) throws Exception {
    return Optional.empty();
  }

  @Override
  public void deleteCampaign(String campaignId) throws Exception {

  }

  @Override
  public List<CrmContact> getEmailContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public List<CrmAccount> getEmailAccounts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public List<CrmContact> getSmsContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public List<CrmUser> getUsers() throws Exception {
    return Collections.emptyList();
  }

  @Override
  public Map<String, String> getContactLists(CrmContactListType listType) throws Exception {
    return Collections.emptyMap();
  }

  @Override
  public Map<String, String> getFieldOptions(String object) throws Exception {
    return Collections.emptyMap();
  }

  @Override
  public Map<String, List<String>> getContactCampaignsByContactIds(List<String> contactIds,
      EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return Collections.emptyMap();
  }

  @Override
  public double getDonationsTotal(String filter) throws Exception {
    return 0.0;
  }

  @Override
  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {

  }

  @Override
  public Optional<CrmUser> getUserById(String id) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmUser> getUserByEmail(String email) throws Exception {
    return Optional.empty();
  }

  @Override
  public String insertActivity(CrmActivity crmActivity) throws Exception {
    return null;
  }

  @Override
  public String updateActivity(CrmActivity crmActivity) throws Exception {
    return null;
  }

  @Override
  public Optional<CrmActivity> getActivityByExternalRef(String externalRef) throws Exception {
    return Optional.empty();
  }

  @Override
  public String insertNote(CrmNote crmNote) throws Exception {
    return null;
  }

  @Override
  public List<CrmCustomField> insertCustomFields(List<CrmCustomField> crmCustomFields) {
    return Collections.emptyList();
  }

  @Override
  public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
    return null;
  }

  @Override
  public String name() {
    return "NoOp";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return false;
  }

  @Override
  public void init(Environment env) {

  }
}
