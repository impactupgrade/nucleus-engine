package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmCustomField;
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

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
  public Optional<CrmAccount> getAccountByCustomerId(String customerId) throws Exception {
    return Optional.empty();
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
  public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) throws Exception {
    return new PagedResults<>(Collections.emptyList(), 0, "");
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
  public void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception {

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
  public Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId) throws Exception {
    return Optional.empty();
  }

  @Override
  public List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public List<CrmRecurringDonation> searchOpenRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception {
    return Collections.emptyList();
  }
  @Override
  public List<CrmRecurringDonation> searchAllRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception {
    return Collections.emptyList();
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
  public void insertDonationDeposit(List<PaymentGatewayEvent> paymentGatewayEvents) throws Exception {

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
    return Collections.emptyList();
  }

  @Override
  public List<CrmContact> getEmailContacts(Calendar updatedSince, String filter) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public Map<String, List<String>> getActiveCampaignsByContactIds(List<String> contactIds) throws Exception {
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
  public void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception {

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
  public String insertTask(CrmTask crmTask) throws Exception {
    return null;
  }

  @Override
  public List<CrmCustomField> insertCustomFields(String layoutName, List<CrmCustomField> crmCustomFields) {
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
