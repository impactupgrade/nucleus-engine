package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.OpportunityEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * There is typically a huge difference in the feature set of general-purpose CRMs (like SFDC and HS) vs.
 * nonprofit CRMs and donor management/retention platforms (like Bloomerang and Donor Wrangler). The latter rarely
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

  default Optional<CrmAccount> getAccountByCustomerId(String customerId) throws Exception {
    return Optional.empty();
  }

  default String insertAccount(CrmAccount crmAccount) throws Exception {
    return null;
  }

  default void updateAccount(CrmAccount crmAccount) throws Exception {
  }

  default void deleteAccount(String accountId) throws Exception {
  }

  default List<CrmDonation> getDonationsByAccountId(String accountId) throws Exception {
    return Collections.emptyList();
  }

  default List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception {
    return null;
  }

  default String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return null;
  }

  default Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    return Optional.empty();
  }

  default Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId) throws Exception {
    return Optional.empty();
  }

  default Optional<CrmRecurringDonation> getRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    return Optional.empty();
  }

  default void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
  }

  default void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    throw new RuntimeException("not implemented");
  }

  default void closeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    throw new RuntimeException("not implemented");
  }

  default void insertDonationDeposit(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
  }

  default void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception {
  }

  default List<CrmContact> getContactsFromList(String listId) throws Exception {
    return null;
  }

  default void addContactToList(CrmContact crmContact, String listId) throws Exception {
  }

  default void removeContactFromList(CrmContact crmContact, String listId) throws Exception {
  }

  default String insertOpportunity(OpportunityEvent opportunityEvent) throws Exception {
    return null;
  }

  default Optional<CrmUser> getUserById(String id) throws Exception {
    return Optional.empty();
  }

  default String insertTask(CrmTask crmTask) throws Exception {
    return null;
  }

  default List<String> getCampaigns(CrmContact contact) throws Exception{
    return null;
  }

}
