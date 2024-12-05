/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.AccountSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmCampaign;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmCustomField;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmNote;
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmActivity;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * There is typically a huge difference in the feature set of general-purpose CRMs (like SFDC and HS) vs.
 * nonprofit CRMs and donor management/retention platforms (like Bloomerang). The latter rarely
 * include campaigns, lists/reports, non-donation opportunities, tasks, etc. Often, they don't even include an
 * account/household/organization level, instead lumping everything into a single "Donor" object (which we opt
 * to map to Contact). Ditto for recurring donations.
 *
 * So we provide a "basic" interface providing no-op default implementations of the methods not generally supported
 * by the other platforms.
 *
 * Note that we *could* reverse this and split the method definitions between something like a BasicCrmService
 * and "FullCrmService extends BasicCrmService", then have impls choose which one to implement. However, having a single
 * CrmService makes processing way easier and ensures we don't need to do a bunch of instanceof checks.
 */
public interface BasicCrmService extends CrmService {

  default Optional<CrmAccount> getAccountById(String id) throws Exception {
    return Optional.empty();
  }

  default List<CrmAccount> getAccountsByIds(List<String> ids) throws Exception {
    return Collections.emptyList();
  }

  default List<CrmAccount> getAccountsByEmails(List<String> emails) throws Exception {
    return Collections.emptyList();
  }

  default List<CrmAccount> searchAccounts(AccountSearch accountSearch) throws Exception {
    return Collections.emptyList();
  }

  default String insertAccount(CrmAccount crmAccount) throws Exception {
    return null;
  }

  default void updateAccount(CrmAccount crmAccount) throws Exception {
  }

  default void deleteAccount(String accountId) throws Exception {
  }

  default List<CrmRecurringDonation> searchAllRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception {
    return null;
  }

  default String insertRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
    return null;
  }

  default Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId) throws Exception {
    return Optional.empty();
  }

  default Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    return Optional.empty();
  }

  default void closeRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
  }

  default void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
  }

  default void insertDonationDeposit(List<CrmDonation> crmDonations) throws Exception {
  }

  default void addAccountToCampaign(CrmAccount crmAccount, String campaignId, String status) throws Exception {
  }

  default void addContactToCampaign(CrmContact crmContact, String campaignId, String status) throws Exception {
  }

  default List<CrmContact> getContactsFromList(String listId) throws Exception {
    return null;
  }

  default void addContactToList(CrmContact crmContact, String listId) throws Exception {
  }

  default void removeContactFromList(CrmContact crmContact, String listId) throws Exception {
  }

  default String insertOpportunity(CrmOpportunity crmOpportunity) throws Exception {
    return null;
  }

  default String insertCampaign(CrmCampaign crmCampaign) throws Exception {
    return null;
  }

  default void updateCampaign(CrmCampaign crmCampaign) throws Exception {
  }

  default Optional<CrmCampaign> getCampaignByExternalReference(String externalReference) throws Exception {
    return Optional.empty();
  }

  default void deleteCampaign(String campaignId) throws Exception {
  }

  default Optional<CrmUser> getUserById(String id) throws Exception {
    return Optional.empty();
  }

  default Optional<CrmUser> getUserByEmail(String email) throws Exception {
    return Optional.empty();
  }

  default void batchInsertActivity(CrmActivity crmActivity) throws Exception {
  }

  default void batchUpdateActivity(CrmActivity crmActivity) throws Exception {
  }

  default List<CrmActivity> getActivitiesByExternalRefs(List<String> externalRefs) throws Exception {
    return Collections.emptyList();
  }

  default String insertNote(CrmNote crmNote) throws Exception {
    return null;
  }

  default Map<String, List<String>> getContactsCampaigns(List<CrmContact> crmContacts,
      EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return null;
  }

  default List<CrmCustomField> insertCustomFields(List<CrmCustomField> crmCustomFields) {
    return null;
  }

  default void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
  }
}
