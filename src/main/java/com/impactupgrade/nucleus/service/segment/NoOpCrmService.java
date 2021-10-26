package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
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

/**
 * This is lazy cheating. For DS clients, allow Nucleus tasks like Manage Recurring Donation to work without a CRM.
 * We could muddy the code to check for "Has a CRM?" all over. Or we can use this no-op flavor to simply drop all calls.
 */
public class NoOpCrmService implements CrmService {

  @Override
  public Optional<CrmAccount> getAccountById(String id) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmContact> getContactById(String id) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmContact> getContactByEmail(String email) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmContact> getContactByPhone(String phone) throws Exception {
    return Optional.empty();
  }

  @Override
  public String insertAccount(CrmAccount crmAccount) throws Exception {
    return null;
  }

  @Override
  public void updateAccount(CrmAccount crmAccount) throws Exception {

  }

  @Override
  public String insertContact(CrmContact crmContact) throws Exception {
    return null;
  }

  @Override
  public void updateContact(CrmContact crmContact) throws Exception {

  }

  @Override
  public void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception {

  }

  @Override
  public List<CrmContact> getContactsFromList(String listId) throws Exception {
    return null;
  }

  @Override
  public void addContactToList(CrmContact crmContact, String listId) throws Exception {

  }

  @Override
  public void removeContactFromList(CrmContact crmContact, String listId) throws Exception {

  }

  @Override
  public Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    return Optional.empty();
  }

  @Override
  public List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception {
    return null;
  }

  @Override
  public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return null;
  }

  @Override
  public void insertDonationReattempt(PaymentGatewayEvent paymentGatewayEvent) throws Exception {

  }

  @Override
  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {

  }

  @Override
  public void insertDonationDeposit(PaymentGatewayEvent paymentGatewayEvent) throws Exception {

  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return Optional.empty();
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return null;
  }

  @Override
  public void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {

  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    return Optional.empty();
  }

  @Override
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {

  }

  @Override
  public void closeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {

  }

  @Override
  public String insertOpportunity(OpportunityEvent opportunityEvent) throws Exception {
    return null;
  }

  @Override
  public List<CrmDonation> getDonationsByAccountId(String accountId) throws Exception {
    return null;
  }

  @Override
  public List<CrmContact> getContactsUpdatedSince(Calendar calendar) throws Exception {
    return null;
  }

  @Override
  public List<CrmContact> getDonorContactsSince(Calendar calendar) throws Exception {
    return null;
  }

  @Override
  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {

  }

  @Override
  public void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception {

  }

  @Override
  public Optional<CrmUser> getUserById(String id) throws Exception {
    return Optional.empty();
  }

  @Override
  public String name() {
    return null;
  }

  @Override
  public void init(Environment env) {

  }
}
