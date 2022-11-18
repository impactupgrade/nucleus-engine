/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.client.SfdcMetadataClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmCampaign;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmCustomField;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.OpportunityEvent;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.util.Utils;
import com.sforce.soap.metadata.FieldType;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SfdcCrmService implements CrmService {

  private static final Logger log = LogManager.getLogger(SfdcCrmService.class);

  protected Environment env;
  protected SfdcClient sfdcClient;
  protected SfdcMetadataClient sfdcMetadataClient;

  @Override
  public String name() { return "salesforce"; }

  @Override
  public boolean isConfigured(Environment env) {
    return !Strings.isNullOrEmpty(env.getConfig().salesforce.username);
  }

  @Override
  public void init(Environment env) {
    this.env = env;
    this.sfdcClient = env.sfdcClient();
    this.sfdcMetadataClient = env.sfdcMetadataClient();
  }

  @Override
  public Optional<CrmAccount> getAccountById(String id) throws Exception {
    return toCrmAccount(sfdcClient.getAccountById(id));
  }

  @Override
  public Optional<CrmAccount> getAccountByCustomerId(String customerId) throws Exception {
    return toCrmAccount(sfdcClient.getAccountByCustomerId(customerId));
  }

  @Override
  public Optional<CrmContact> getContactById(String id) throws Exception {
    return toCrmContact(sfdcClient.getContactById(id));
  }

  @Override
  public Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception {
    return toCrmContact(sfdcClient.getFilteredContactById(id, filter));
  }

  @Override
  public List<CrmContact> getContactsByIds(List<String> ids) throws Exception {
    return toCrmContact(sfdcClient.getContactsByIds(ids));
  }

  @Override
  // currentPageToken assumed to be the offset index
  public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) throws InterruptedException, ConnectionException {
    return toCrmContact(sfdcClient.searchContacts(contactSearch));
  }

  @Override
  public String insertAccount(CrmAccount crmAccount) throws Exception {
    SObject account = new SObject("Account");
    setAccountFields(account, crmAccount);
    return sfdcClient.insert(account).getId();
  }

  @Override
  public void updateAccount(CrmAccount crmAccount) throws Exception {
    SObject account = new SObject("Account");
    account.setId(crmAccount.id);
    setAccountFields(account, crmAccount);
    sfdcClient.update(account);
  }

  @Override
  public void deleteAccount(String accountId) throws Exception {
    SObject account = new SObject("Account");
    account.setId(accountId);
    sfdcClient.delete(account);
  }

  @Override
  public List<CrmDonation> getDonationsByAccountId(String accountId) throws Exception {
    // TODO
    return null;
  }

  @Override
  public Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
    return toCrmDonation(sfdcClient.getDonationByTransactionId(transactionId));
  }

  @Override
  public List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds) throws Exception {
    return toCrmDonation(sfdcClient.getDonationsByTransactionIds(transactionIds));
  }

  @Override
  public List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception {
    // TODO
    return null;
  }

  @Override
  public List<CrmRecurringDonation> searchOpenRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws InterruptedException, ConnectionException {
    List<String> clauses = searchRecurringDonations(name, email, phone);

    return sfdcClient.searchOpenRecurringDonations(clauses)
        .stream()
        .map(this::toCrmRecurringDonation)
        .collect(Collectors.toList());
  }
  @Override
  public List<CrmRecurringDonation> searchAllRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws InterruptedException, ConnectionException {
    List<String> clauses = searchRecurringDonations(name, email, phone);

    return sfdcClient.searchRecurringDonations(clauses)
            .stream()
            .map(this::toCrmRecurringDonation)
            .collect(Collectors.toList());
  }

  protected List<String> searchRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone){
    List<String> clauses = new ArrayList<>();

    if (name.isPresent()) {
      String[] nameParts = name.get().split("\\s+");

      for (String part : nameParts) {
        List<String> nameClauses = new ArrayList<>();
        nameClauses.add("npe03__Organization__r.name LIKE '%" + part + "%'");
        nameClauses.add("npe03__Contact__r.name LIKE '%" + part + "%'");
        clauses.add(String.join(" OR ", nameClauses));
      }
    }

    if (email.isPresent()) {
      List<String> emailClauses = new ArrayList<>();
      emailClauses.add("npe03__Contact__r.npe01__HomeEmail__c LIKE '%" + email.get() + "%'");
      emailClauses.add("npe03__Contact__r.npe01__WorkEmail__c LIKE '%" + email.get() + "%'");
      emailClauses.add("npe03__Contact__r.npe01__AlternateEmail__c LIKE '%" + email.get() + "%'");
      emailClauses.add("npe03__Contact__r.email LIKE '%" + email.get() + "%'");
      clauses.add(String.join(" OR ", emailClauses));
    }

    if (phone.isPresent()) {
      String phoneClean = phone.get().replaceAll("\\D+", "");
      phoneClean = phoneClean.replaceAll("", "%");
      if (!phoneClean.isEmpty()) {
        List<String> phoneClauses = new ArrayList<>();
        phoneClauses.add("npe03__Organization__r.phone LIKE '%" + phoneClean + "%'");
        phoneClauses.add("npe03__Contact__r.phone LIKE '" + phoneClean + "'");
        phoneClauses.add("npe03__Contact__r.MobilePhone LIKE '" + phoneClean + "'");
        phoneClauses.add("npe03__Contact__r.HomePhone LIKE '" + phoneClean + "'");
        clauses.add(String.join(" OR ", phoneClauses));
      }
    }

    if (clauses.isEmpty()) {
      return Collections.emptyList();
    }

    return clauses;
  }
  @Override
  public Optional<CrmUser> getUserById(String id) throws Exception {
    return toCrmUser(sfdcClient.getUserById(id));
  }

  @Override
  public Optional<CrmUser> getUserByEmail(String email) throws Exception {
    return toCrmUser (sfdcClient.getUserByEmail(email));
  }

  @Override
  public String insertTask(CrmTask crmTask) throws Exception {
    SObject task = new SObject("Task");
    setTaskFields(task, crmTask);
    return sfdcClient.insert(task).getId();
  }

  @Override
  public List<CrmCustomField> insertCustomFields(String layoutName, List<CrmCustomField> crmCustomFields) {
    try {
      List<String> fieldNames = new ArrayList<>();
      // Create custom fields
      for (CrmCustomField crmCustomField: crmCustomFields) {
        sfdcMetadataClient.createCustomField(
                crmCustomField.objectName,
                crmCustomField.name,
                crmCustomField.label,
                toCustomFieldType(crmCustomField.type),
                crmCustomField.length,
                crmCustomField.precision,
                crmCustomField.scale
        );
        fieldNames.add(crmCustomField.name);
      }

      // Add custom fields to layout
      sfdcMetadataClient.addFields(layoutName, "Custom Fields", fieldNames);

    } catch (Exception e) {
      log.error("failed to create custom fields", e);
    }
    return crmCustomFields;
  }

  protected FieldType toCustomFieldType(CrmCustomField.Type type) {
    return switch(type) {
      //case TEXT -> FieldType.Text;
      case DATE -> FieldType.Date;
      case CURRENCY -> FieldType.Currency;
      default -> FieldType.Text; // TODO: default?
    };
  }

  @Override
  public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
    return this.env.getConfig().salesforce.fieldDefinitions;
  }

  protected void setTaskFields(SObject task, CrmTask crmTask) {
    task.setField("WhoId", crmTask.targetId);
    task.setField("OwnerId", crmTask.assignTo);
    task.setField("Subject", crmTask.subject);
    task.setField("Description", crmTask.description);

    switch (crmTask.status) {
      case IN_PROGRESS -> task.setField("Status", "In Progress");
      case DONE -> task.setField("Status", "Completed");
      default -> task.setField("Status", "Not Started");
    }

    switch (crmTask.priority) {
      case LOW -> task.setField("Priority", "Low");
      case HIGH, CRITICAL -> task.setField("Priority", "High");
      default -> task.setField("Priority", "Normal");
    }

    task.setField("ActivityDate", crmTask.dueDate);
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId) throws Exception {
    return toCrmRecurringDonation(sfdcClient.getRecurringDonationBySubscriptionId(subscriptionId));
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    if (!Strings.isNullOrEmpty(manageDonationEvent.getDonationId())) {
      log.info("attempting to retrieve recurring donation by ID {}...", manageDonationEvent.getDonationId());
      return toCrmRecurringDonation(sfdcClient.getRecurringDonationById(manageDonationEvent.getDonationId()));
    } else if (!Strings.isNullOrEmpty(manageDonationEvent.getDonationName())) {
      log.info("attempting to retrieve recurring donation by name {}...", manageDonationEvent.getDonationName());
      return toCrmRecurringDonation(sfdcClient.getRecurringDonationByName(manageDonationEvent.getDonationName()));
    } else {
      return Optional.empty();
    }
  }

  @Override
  public void insertDonationReattempt(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    CrmDonation existingDonation = getDonation(paymentGatewayEvent).get();

    SObject opportunity = new SObject("Opportunity");
    opportunity.setId(existingDonation.id);

    // We need to set all fields again in order to tackle special cases. Ex: if this was a donation in a converted
    // currency, that data won't be available until the transaction actually succeeds. However, note that we currently
    // ignore the campaign -- no need to currently re-provide that side.
    setOpportunityFields(opportunity, Optional.empty(), paymentGatewayEvent);

    sfdcClient.update(opportunity);
  }

  protected void setAccountFields(SObject account, CrmAccount crmAccount) {
    account.setField("Name", crmAccount.name);

    account.setField("BillingStreet", crmAccount.address.street);
    account.setField("BillingCity", crmAccount.address.city);
    account.setField("BillingState", crmAccount.address.state);
    account.setField("BillingPostalCode", crmAccount.address.postalCode);
    account.setField("BillingCountry", crmAccount.address.country);
  }

  @Override
  public String insertContact(CrmContact crmContact) throws Exception {
    SObject contact = new SObject("Contact");
    setContactFields(contact, crmContact);
    return sfdcClient.insert(contact).getId();
  }

  @Override
  public void updateContact(CrmContact crmContact) throws Exception {
    SObject contact = new SObject("Contact");
    contact.setId(crmContact.id);
    setContactFields(contact, crmContact);
    sfdcClient.update(contact);
  }

  @Override
  public void batchUpdateContact(CrmContact crmContact) throws Exception {
    SObject contact = new SObject("Contact");
    contact.setId(crmContact.id);
    setContactFields(contact, crmContact);
    sfdcClient.batchUpdate(contact);
  }

  @Override
  public void batchFlush() throws Exception {
    sfdcClient.batchFlush();
  }

  @Override
  public void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception {
    addContactToCampaign(crmContact.id, campaignId);
  }

  protected void addContactToCampaign(String contactId, String campaignId) throws Exception {
    SObject campaignMember = new SObject("CampaignMember");
    campaignMember.setField("ContactId", contactId);
    campaignMember.setField("CampaignId", campaignId);
    sfdcClient.insert(campaignMember);
  }

  protected void setContactFields(SObject contact, CrmContact crmContact) {
    contact.setField("AccountId", crmContact.accountId);
    contact.setField("FirstName", crmContact.firstName);
    contact.setField("LastName", crmContact.lastName);
    contact.setField("Email", crmContact.email);
    contact.setField("MobilePhone", crmContact.mobilePhone);
    if (crmContact.preferredPhone != null) {
      contact.setField("Npe01__PreferredPhone__c", crmContact.preferredPhone.toString());
    }
    setField(contact, env.getConfig().salesforce.fieldDefinitions.contactLanguage, crmContact.contactLanguage);

    if (crmContact.emailOptIn != null && crmContact.emailOptIn) {
      setField(contact, env.getConfig().salesforce.fieldDefinitions.emailOptIn, true);
      setField(contact, env.getConfig().salesforce.fieldDefinitions.emailOptOut, false);
    }
    if (crmContact.emailOptOut != null && crmContact.emailOptOut) {
      setField(contact, env.getConfig().salesforce.fieldDefinitions.emailOptIn, false);
      setField(contact, env.getConfig().salesforce.fieldDefinitions.emailOptOut, true);
    }

    if (crmContact.smsOptIn != null && crmContact.smsOptIn) {
      setField(contact, env.getConfig().salesforce.fieldDefinitions.smsOptIn, true);
      setField(contact, env.getConfig().salesforce.fieldDefinitions.smsOptOut, false);
    }
    if (crmContact.smsOptOut != null && crmContact.smsOptOut) {
      setField(contact, env.getConfig().salesforce.fieldDefinitions.smsOptIn, false);
      setField(contact, env.getConfig().salesforce.fieldDefinitions.smsOptOut, true);
    }

    if (crmContact.notes != null && crmContact.notes != "") {
      contact.setField("Description", crmContact.notes);
    }
  }

  @Override
  public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<SObject> campaign = getCampaignOrDefault(paymentGatewayEvent);
    String recurringDonationId = paymentGatewayEvent.getCrmRecurringDonationId();

    if (!Strings.isNullOrEmpty(recurringDonationId)) {
      // get the next pledged donation from the recurring donation
      Optional<SObject> pledgedOpportunityO = sfdcClient.getNextPledgedDonationByRecurringDonationId(recurringDonationId);
      if (pledgedOpportunityO.isPresent()) {
        SObject pledgedOpportunity = pledgedOpportunityO.get();
        return processPledgedDonation(pledgedOpportunity, campaign, recurringDonationId, paymentGatewayEvent);
      } else {
        log.warn("unable to find SFDC pledged donation for recurring donation {} that isn't in the future",
            recurringDonationId);
      }
    }

    // not a recurring donation, OR an existing pledged donation didn't exist -- create a new donation
    return processNewDonation(campaign, recurringDonationId, paymentGatewayEvent);
  }

  protected String processPledgedDonation(SObject pledgedOpportunity, Optional<SObject> campaign,
      String recurringDonationId, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    log.info("found SFDC pledged opportunity {} in recurring donation {}",
        pledgedOpportunity.getId(), recurringDonationId);

    // check to see if the recurring donation was a failed attempt or successful
    if (paymentGatewayEvent.isTransactionSuccess()) {
      // update existing pledged donation to Closed Won
      SObject updateOpportunity = new SObject("Opportunity");
      updateOpportunity.setId(pledgedOpportunity.getId());
      setOpportunityFields(updateOpportunity, campaign, paymentGatewayEvent);
      sfdcClient.update(updateOpportunity);
      return pledgedOpportunity.getId();
    } else {
      // subscription payment failed
      // create new Opportunity and post it to the recurring donation leaving the Pledged donation there
      return processNewDonation(campaign, recurringDonationId, paymentGatewayEvent);
    }
  }

  protected String processNewDonation(Optional<SObject> campaign, String recurringDonationId,
      PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    SObject opportunity = new SObject("Opportunity");

    opportunity.setField("AccountId", paymentGatewayEvent.getCrmAccount().id);
    opportunity.setField("Npe03__Recurring_Donation__c", recurringDonationId);

    setOpportunityFields(opportunity, campaign, paymentGatewayEvent);

    String oppId = sfdcClient.insert(opportunity).getId();

    if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().id)) {
      SObject contactRole = new SObject("OpportunityContactRole");
      contactRole.setField("OpportunityId", oppId);
      contactRole.setField("ContactId", paymentGatewayEvent.getCrmContact().id);
      contactRole.setField("IsPrimary", true);
      // TODO: Not present by default at all orgs.
//      contactRole.setField("Role", "Donor");
      sfdcClient.insert(contactRole);
    }

    return oppId;
  }

  protected void setOpportunityFields(SObject opportunity, Optional<SObject> campaign, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayName)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayName, paymentGatewayEvent.getGatewayName());
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayTransactionId)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayTransactionId, paymentGatewayEvent.getTransactionId());
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId, paymentGatewayEvent.getCustomerId());
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.fund)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.fund, paymentGatewayEvent.getMetadataValue(env.getConfig().metadataKeys.fund));
    }

    // check to see if this was a failed payment attempt and set the StageName accordingly
    if (paymentGatewayEvent.isTransactionSuccess()) {
      opportunity.setField("StageName", "Closed Won");
    } else {
      opportunity.setField("StageName", "Failed Attempt");
    }

    opportunity.setField("Amount", paymentGatewayEvent.getTransactionAmountInDollars());
    opportunity.setField("CampaignId", campaign.map(SObject::getId).orElse(null));
    opportunity.setField("CloseDate", GregorianCalendar.from(paymentGatewayEvent.getTransactionDate()));
    opportunity.setField("Description", paymentGatewayEvent.getTransactionDescription());

    // purely a default, but we generally expect this to be overridden
    opportunity.setField("Name", paymentGatewayEvent.getCrmContact().fullName() + " Donation");
  }

  @Override
  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> donation = getDonation(paymentGatewayEvent);

    if (donation.isEmpty()) {
      log.warn("unable to find SFDC donation using transaction {}", paymentGatewayEvent.getTransactionId());
      return;
    }

    SObject opportunity = new SObject("Opportunity");
    opportunity.setId(donation.get().id);
    setOpportunityRefundFields(opportunity, paymentGatewayEvent);

    sfdcClient.update(opportunity);
  }

  protected void setOpportunityRefundFields(SObject opportunity, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundId)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundId, paymentGatewayEvent.getRefundId());
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDate)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDate, GregorianCalendar.from(paymentGatewayEvent.getRefundDate()));
    }
    // TODO: LJI/TER/DR specific? They all have it, but I can't remember if we explicitly added it.
    opportunity.setField("StageName", "Refunded");
  }

  @Override
  public void insertDonationDeposit(List<PaymentGatewayEvent> paymentGatewayEvents) throws Exception {
    for (PaymentGatewayEvent paymentGatewayEvent : paymentGatewayEvents) {
      // make use of additional logic in getDonation
      Optional<CrmDonation> crmDonation = getDonation(paymentGatewayEvent);

      if (crmDonation.isEmpty()) {
        log.warn("unable to find SFDC opportunity using transaction {}", paymentGatewayEvent.getTransactionId());
        continue;
      }

      SObject opportunity = (SObject) crmDonation.get().rawObject;
      SObject opportunityUpdate = new SObject("Opportunity");
      opportunityUpdate.setId(opportunity.getId());
      setDonationDepositFields(opportunity, opportunityUpdate, paymentGatewayEvent);

      sfdcClient.batchUpdate(opportunityUpdate);
    }

    sfdcClient.batchFlush();
  }

  protected void setDonationDepositFields(SObject existingOpportunity, SObject opportunityUpdate,
      PaymentGatewayEvent paymentGatewayEvent) throws InterruptedException {
    // If the payment gateway event has a refund ID, this item in the payout was a refund. Mark it as such!
    if (!Strings.isNullOrEmpty(paymentGatewayEvent.getRefundId())) {
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundId)) {
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDepositDate, GregorianCalendar.from(paymentGatewayEvent.getDepositDate()));
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDepositId, paymentGatewayEvent.getDepositId());
      }
    } else {
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositId)) {
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositDate, GregorianCalendar.from(paymentGatewayEvent.getDepositDate()));
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositId, paymentGatewayEvent.getDepositId());
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositNetAmount, paymentGatewayEvent.getTransactionNetAmountInDollars());
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositFee, paymentGatewayEvent.getTransactionFeeInDollars());
      }
    }
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    SObject recurringDonation = new SObject("Npe03__Recurring_Donation__c");
    setRecurringDonationFields(recurringDonation, getCampaignOrDefault(paymentGatewayEvent), paymentGatewayEvent);
    return sfdcClient.insert(recurringDonation).getId();
  }

  /**
   * Set any necessary fields on an RD before it's inserted.
   */
  protected void setRecurringDonationFields(SObject recurringDonation, Optional<SObject> campaign, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayName)) {
      recurringDonation.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayName, paymentGatewayEvent.getGatewayName());
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewaySubscriptionId)) {
      recurringDonation.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewaySubscriptionId, paymentGatewayEvent.getSubscriptionId());
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId)) {
      recurringDonation.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId, paymentGatewayEvent.getCustomerId());
    }

    recurringDonation.setField("Npe03__Amount__c", paymentGatewayEvent.getSubscriptionAmountInDollars());
    recurringDonation.setField("Npe03__Open_Ended_Status__c", "Open");
    recurringDonation.setField("Npe03__Schedule_Type__c", "Multiply By");
//    recurringDonation.setDonation_Method__c(paymentGatewayEvent.getDonationMethod());
    CrmRecurringDonation.Frequency frequency = CrmRecurringDonation.Frequency.fromName(paymentGatewayEvent.getSubscriptionInterval());
    if (frequency != null) {
      recurringDonation.setField("Npe03__Installment_Period__c", frequency.name());
    }
    recurringDonation.setField("Npe03__Date_Established__c", GregorianCalendar.from(paymentGatewayEvent.getSubscriptionStartDate()));
    recurringDonation.setField("Npe03__Next_Payment_Date__c", GregorianCalendar.from(paymentGatewayEvent.getSubscriptionNextDate()));
    recurringDonation.setField("Npe03__Recurring_Donation_Campaign__c", getCampaignOrDefault(paymentGatewayEvent).map(SObject::getId).orElse(null));

    // Purely a default, but we expect this to be generally overridden.
    recurringDonation.setField("Name", paymentGatewayEvent.getCrmContact().fullName() + " Recurring Donation");

    if (env.getConfig().salesforce.enhancedRecurringDonations) {
      // NPSP Enhanced RDs will not allow you to associate the RD directly with an Account if it's a household, instead
      // forcing us to use the contact. But, since we don't know at this point if this is a business gift, we
      // unfortunately need to assume the existence of a contactId means we should use it.
      if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().id)) {
        recurringDonation.setField("Npe03__Contact__c", paymentGatewayEvent.getCrmContact().id);
      } else {
        recurringDonation.setField("Npe03__Organization__c", paymentGatewayEvent.getCrmAccount().id);
      }

      recurringDonation.setField("npsp__RecurringType__c", "Open");
      // It's a picklist, so it has to be a string and not numeric :(
      recurringDonation.setField("npsp__Day_of_Month__c", paymentGatewayEvent.getSubscriptionStartDate().getDayOfMonth() + "");
    } else {
      // Legacy behavior was to always use the Account, regardless if it was a business or household. Stick with that
      // by default -- we have some orgs that depend on it.
      recurringDonation.setField("Npe03__Organization__c", paymentGatewayEvent.getCrmAccount().id);
    }
  }

  @Override
  public void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = getRecurringDonation(paymentGatewayEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find SFDC recurring donation using subscriptionId {}",
          paymentGatewayEvent.getSubscriptionId());
      return;
    }

    SObject toUpdate = new SObject("Npe03__Recurring_Donation__c");
    toUpdate.setId(recurringDonation.get().id);
    toUpdate.setField("Npe03__Open_Ended_Status__c", "Closed");
    setRecurringDonationFieldsForClose(toUpdate, paymentGatewayEvent);
    sfdcClient.update(toUpdate);
  }

  @Override
  public void closeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = getRecurringDonation(manageDonationEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find CRM recurring donation using recurringDonationId {}", manageDonationEvent.getDonationId());
      return;
    }

    SObject toUpdate = new SObject("Npe03__Recurring_Donation__c");
    toUpdate.setId(recurringDonation.get().id);
    toUpdate.setField("Npe03__Open_Ended_Status__c", "Closed");
    setRecurringDonationFieldsForClose(toUpdate, manageDonationEvent);
    sfdcClient.update(toUpdate);
  }

  // Give orgs an opportunity to clear anything else out that's unique to them, prior to the update
  protected void setRecurringDonationFieldsForClose(SObject recurringDonation,
      PaymentGatewayEvent paymentGatewayEvent) throws Exception {
  }

  // Give orgs an opportunity to clear anything else out that's unique to them, prior to the update
  protected void setRecurringDonationFieldsForClose(SObject recurringDonation,
      ManageDonationEvent manageDonationEvent) throws Exception {
  }

  // Give orgs an opportunity to set anything else that's unique to them, prior to pause
  protected void setRecurringDonationFieldsForPause(SObject recurringDonation,
      ManageDonationEvent manageDonationEvent) throws Exception {
  }

  // Give orgs an opportunity to set anything else that's unique to them, prior to resume
  protected void setRecurringDonationFieldsForResume(SObject recurringDonation,
      ManageDonationEvent manageDonationEvent) throws Exception {
  }

  public void pauseRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = getRecurringDonation(manageDonationEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find SFDC recurring donation using donationId {}", manageDonationEvent.getDonationId());
      return;
    }

    SObject toUpdate = new SObject("Npe03__Recurring_Donation__c");
    toUpdate.setId(manageDonationEvent.getDonationId());
    toUpdate.setField("Npe03__Open_Ended_Status__c", "Closed");
    toUpdate.setFieldsToNull(new String[] {"Npe03__Next_Payment_Date__c"});

    if (manageDonationEvent.getPauseDonationUntilDate() == null) {
      log.info("pausing {} indefinitely...", manageDonationEvent.getDonationId());
    } else {
      log.info("pausing {} until {}...", manageDonationEvent.getDonationId(), manageDonationEvent.getPauseDonationUntilDate().getTime());
    }
    setRecurringDonationFieldsForPause(toUpdate, manageDonationEvent);
    sfdcClient.update(toUpdate);
  }

  public void resumeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = getRecurringDonation(manageDonationEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find SFDC recurring donation using donationId {}", manageDonationEvent.getDonationId());
      return;
    }

    SObject toUpdate = new SObject("Npe03__Recurring_Donation__c");
    toUpdate.setId(manageDonationEvent.getDonationId());
    toUpdate.setField("Npe03__Open_Ended_Status__c", "Open");

    if (manageDonationEvent.getResumeDonationOnDate() == null) {
      log.info("resuming {} immediately...", manageDonationEvent.getDonationId());
      toUpdate.setField("Npe03__Next_Payment_Date__c", Calendar.getInstance().getTime());
    } else {
      log.info("resuming {} on {}...", manageDonationEvent.getDonationId(), manageDonationEvent.getResumeDonationOnDate().getTime());
      toUpdate.setField("Npe03__Next_Payment_Date__c", manageDonationEvent.getResumeDonationOnDate());
    }
    setRecurringDonationFieldsForResume(toUpdate, manageDonationEvent);
    sfdcClient.update(toUpdate);

    sfdcClient.refreshRecurringDonation(manageDonationEvent.getDonationId());
  }

  @Override
  public void addContactToList(CrmContact crmContact, String listId) throws Exception {
    // likely not relevant in SFDC
  }

  /**
   * SFDC does have a Reporting API that we could theoretically use to pull results, then have the Portal task simply
   * feed us that Campaign ID. However, since reports are so open-ended, it'll be really tough to do that flexibly.
   *
   * For now, support two alternatives:
   *
   * - Campaign ID
   * - Opportunity Name (explicit match)
   */
  @Override
  public List<CrmContact> getContactsFromList(String listId) throws Exception {
    List<SObject> sObjects;
    // 701 is the Campaign ID prefix
    if (listId.startsWith("701")) {
      sObjects = sfdcClient.getContactsByCampaignId(listId);
      // 00O - Report ID prefix
    } else if (listId.startsWith("00O")) {
      sObjects = sfdcClient.getContactsByReportId(listId);
    }
    // otherwise, assume it's an explicit Opportunity name
    else {
      sObjects = sfdcClient.getContactsByOpportunityName(listId);
    }
    return toCrmContact(sObjects);
  }

  @Override
  public void removeContactFromList(CrmContact crmContact, String listId) throws Exception {
    // likely not relevant in SFDC
  }

  @Override
  public Map<String, List<String>> getActiveCampaignsByContactIds(List<String> contactIds) throws Exception {
    Map<String, List<String>> contactCampaigns = new HashMap<>();
    List<SObject> campaignMembers = sfdcClient.getActiveCampaignsByContactIds(contactIds);
    for (SObject campaignMember : campaignMembers) {
      String contactId = (String) campaignMember.getField("ContactId");
      String campaignName = (String) campaignMember.getChild("Campaign").getField("Name");
      if (!contactCampaigns.containsKey(contactId)) {
        contactCampaigns.put(contactId, new ArrayList<>());
      }
      contactCampaigns.get(contactId).add(campaignName);
    }
    return contactCampaigns;
  }

  @Override
  public String insertOpportunity(OpportunityEvent opportunityEvent) throws Exception {
    SObject opportunity = new SObject("Opportunity");
    opportunity.setField("RecordTypeId", opportunityEvent.getRecordTypeId());
    opportunity.setField("Name", opportunityEvent.getName());
    opportunity.setField("npsp__Primary_Contact__c", opportunityEvent.getCrmContact().id);
    opportunity.setField("CloseDate", Calendar.getInstance());
    // TODO: Good enough for now, but likely needs to be customized.
    opportunity.setField("StageName", "Pledged");
    opportunity.setField("OwnerId", opportunityEvent.getOwnerId());
    opportunity.setField("CampaignId", opportunityEvent.getCampaignId());
    opportunity.setField("Description", opportunityEvent.getNotes());
    return sfdcClient.insert(opportunity).getId();
  }

  @Override
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = getRecurringDonation(manageDonationEvent);

    if (recurringDonation.isEmpty()) {
      if (Strings.isNullOrEmpty(manageDonationEvent.getDonationId())) {
        log.warn("unable to find SFDC recurring donation using donationId {}", manageDonationEvent.getDonationId());
      } else {
        log.warn("unable to find SFDC recurring donation using donationName {}", manageDonationEvent.getDonationName());
      }
      return;
    }

    SObject toUpdate = new SObject("Npe03__Recurring_Donation__c");
    toUpdate.setId(manageDonationEvent.getDonationId());
    if (manageDonationEvent.getAmount() != null && manageDonationEvent.getAmount() > 0) {
      toUpdate.setField("Npe03__Amount__c", manageDonationEvent.getAmount());
      log.info("Updating Npe03__Amount__c to {}...", manageDonationEvent.getAmount());
    }
    if (manageDonationEvent.getNextPaymentDate() != null) {
      toUpdate.setField("Npe03__Next_Payment_Date__c", manageDonationEvent.getNextPaymentDate());
      log.info("Updating Npe03__Next_Payment_Date__c to {}...", manageDonationEvent.getNextPaymentDate().toString());
    }
    sfdcClient.update(toUpdate);

    if (manageDonationEvent.getPauseDonation() == true) {
      pauseRecurringDonation(manageDonationEvent);
    }

    if (manageDonationEvent.getResumeDonation() == true) {
      resumeRecurringDonation(manageDonationEvent);
    }
  }

  // TODO: Much of this bulk import code needs genericized and pulled upstream!
  @Override
  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
    // hold a map of campaigns so we don't have to visit them each time
    LoadingCache<String, Optional<SObject>> campaignCache = CacheBuilder.newBuilder().build(
        new CacheLoader<>() {
          @Override
          public Optional<SObject> load(String campaignId) throws ConnectionException, InterruptedException {
            log.info("loading campaign {}", campaignId);
            return sfdcClient.getCampaignById(campaignId);
          }
        }
    );

    // This entire method uses bulk queries and bulk inserts wherever possible!
    // We make multiple passes, focusing on one object at a time in order to use the bulk API.

    List<String> contactIds = importEvents.stream().map(e -> e.contactId).filter(contactId -> !Strings.isNullOrEmpty(contactId)).toList();
    Map<String, SObject> existingContactsById = new HashMap<>();
    if (contactIds.size() > 0) {
      sfdcClient.getContactsByIds(contactIds).forEach(c -> existingContactsById.put(c.getId(), c));
    }

    List<String> contactEmails = importEvents.stream().map(e -> e.contactEmail).filter(email -> !Strings.isNullOrEmpty(email)).toList();
    Multimap<String, SObject> existingContactsByEmail = ArrayListMultimap.create();
    if (contactEmails.size() > 0) {
      sfdcClient.getContactsByEmails(contactEmails).forEach(c -> existingContactsByEmail.put((String) c.getField("Email"), c));
    }

    List<String> campaignNames = importEvents.stream().flatMap(e -> Stream.of(e.contactCampaignName, e.opportunityCampaignName)).filter(name -> !Strings.isNullOrEmpty(name)).collect(Collectors.toList());
    Map<String, String> campaignNameToId = Collections.emptyMap();
    if (campaignNames.size() > 0) {
      campaignNameToId = sfdcClient.getCampaignsByNames(campaignNames).stream().collect(Collectors.toMap(c -> (String) c.getField("Name"), SObject::getId));
    }

    // we use by-id maps for batch inserts/updates, since the sheet can contain duplicate contacts/accounts
    // (especially when importing opportunities)
    Map<String, SObject> bulkUpdateAccounts = new HashMap<>();
    Map<String, SObject> bulkInsertContactsByEmail = new HashMap<>();
    Map<String, SObject> bulkInsertContactsByName = new HashMap<>();
    Map<String, SObject> bulkUpdateContacts = new HashMap<>();
    List<SObject> bulkInsertOpportunities = new ArrayList<>();
    Map<String, SObject> bulkUpdateOpportunities = new HashMap<>();
    Map<String, SObject> bulkInsertOpportunityContactRoles = new HashMap<>();

    // If we're doing Opportunity inserts or Campaign updates, we unfortunately can't use batch inserts/updates of accounts/contacts.
    // TODO: We probably *can*, but the code will be rather complex to manage the variety of batch actions paired with CSV rows.
    // Need a way to ensure an Opportunity insert is actually intended. Date seems like a safe assumption...
    boolean oppMode = importEvents.get(0).opportunityDate != null || importEvents.get(0).opportunityId != null;
    boolean nonBatchMode = oppMode || importEvents.get(0).contactCampaignId != null || importEvents.get(0).contactCampaignName != null;

    List<String> nonBatchAccountIds = new ArrayList<>();
    List<String> nonBatchContactIds = new ArrayList<>();

    // TODO: BUGS
    //  1) DONE
    //  2) We're overwriting names each time, which isn't a good idea if the contact already existed. Set Contact names
    //     on insert only, not update.

    for (int i = 0; i < importEvents.size(); i++) {
      CrmImportEvent importEvent = importEvents.get(i);

      log.info("import processing contacts/account on row {} of {}", i + 2, importEvents.size() + 1);

      // If the accountId is explicitly given, run the account update. Otherwise, let the contact queries determine it.
      SObject account = null;
      if (!Strings.isNullOrEmpty(importEvent.accountId)) {
        account = new SObject("Account");
        account.setId(importEvent.accountId);
        setBulkImportAccountFields(account, importEvent, null);
        bulkUpdateAccounts.put(importEvent.accountId, account);
      }

      SObject contact = null;

      // Deep breath.

      // If the explicit Contact ID was given and the contact actually exists, update.
      if (!Strings.isNullOrEmpty(importEvent.contactId) && existingContactsById.containsKey(importEvent.contactId)) {
        String accountId = (String) existingContactsById.get(importEvent.contactId).getField("AccountId");
        if (!Strings.isNullOrEmpty(accountId)) {
          account = updateBulkImportAccount(accountId, importEvent, bulkUpdateAccounts);
        }

        contact = updateBulkImportContact(importEvent.contactId, importEvent, bulkUpdateContacts);
      }
      // Else if a contact already exists with the given email address, update.
      else if (!Strings.isNullOrEmpty(importEvent.contactEmail) && existingContactsByEmail.containsKey(importEvent.contactEmail)) {
        // If the email address has duplicates, use the oldest.
        contact = existingContactsByEmail.get(importEvent.contactEmail).stream()
            .min(Comparator.comparing(c -> ((String) c.getField("CreatedDate")))).get();

        String accountId = (String) contact.getField("AccountId");
        if (!Strings.isNullOrEmpty(accountId)) {
          account = updateBulkImportAccount(accountId, importEvent, bulkUpdateAccounts);
        }

        contact = updateBulkImportContact(contact.getId(), importEvent, bulkUpdateContacts);
      }
      // A little weird looking, but if importing with constituent full names, often it could be either a household
      // name (and email would likely exist) or a business name (almost never email). In either case, hard for us to know
      // which to choose, so by default simply upsert the Account and ignore the contact altogether.
      else if (Strings.isNullOrEmpty(importEvent.contactEmail) && !Strings.isNullOrEmpty(importEvent.contactFullName)) {
        Optional<SObject> existingAccount = sfdcClient.getAccountsByName(importEvent.contactFullName).stream().findFirst();
        if (existingAccount.isPresent()) {
          account = updateBulkImportAccount(existingAccount.get().getId(), importEvent, bulkUpdateAccounts);
        } else {
          account = insertBulkImportAccount(importEvent, importEvent.contactFullName);
        }
      }
      // Else if we have a first and last name but NO email, try searching for an existing contact by name.
      // If 1 match, update. If 0 matches, insert. If 2 or more matches, skip completely out of caution.
      else if (Strings.isNullOrEmpty(importEvent.contactEmail) && !Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(importEvent.contactLastName)) {
        List<SObject> existingContacts = sfdcClient.getContactsByName(importEvent.contactFirstName, importEvent.contactLastName, importEvent.raw);
        log.info("number of contacts for name {} {}: {}", importEvent.contactFirstName, importEvent.contactLastName, existingContacts.size());

        if (existingContacts.size() > 1) {
          // To be safe, let's skip this row for now and deal with it manually...
          log.warn("skipping contact in row {} due to multiple contacts found by-name", i);
        } else if (existingContacts.size() == 1) {
          String accountId = (String) existingContacts.get(0).getField("AccountId");
          if (!Strings.isNullOrEmpty(accountId)) {
            account = updateBulkImportAccount(accountId, importEvent, bulkUpdateAccounts);
          }

          contact = updateBulkImportContact(existingContacts.get(0).getId(), importEvent, bulkUpdateContacts);
        } else {
          contact = insertBulkImportContact(account, importEvent, bulkInsertContactsByEmail, bulkInsertContactsByName,
              existingContactsByEmail, nonBatchMode);
        }
      }
      // Otherwise, abandon all hope and insert, but only if we at least have a lastname or email.
      else if (!Strings.isNullOrEmpty(importEvent.contactEmail) || !Strings.isNullOrEmpty(importEvent.contactLastName)) {
        contact = insertBulkImportContact(account, importEvent, bulkInsertContactsByEmail, bulkInsertContactsByName,
            existingContactsByEmail, nonBatchMode);
      }

      if (contact != null) {
        if (!Strings.isNullOrEmpty(importEvent.contactCampaignId)) {
          addContactToCampaign(contact.getId(), importEvent.contactCampaignId);
        } else if (!Strings.isNullOrEmpty(importEvent.contactCampaignName) && campaignNameToId.containsKey(importEvent.contactCampaignName)) {
          addContactToCampaign(contact.getId(), campaignNameToId.get(importEvent.contactCampaignName));
        }
      }

      if (nonBatchMode) {
        nonBatchAccountIds.add(account != null ? account.getId() : null);
        nonBatchContactIds.add(contact != null ? contact.getId() : null);
      }
    }

    sfdcClient.batchUpdate(bulkUpdateAccounts.values().toArray());
    sfdcClient.batchFlush();
    sfdcClient.batchInsert(bulkInsertContactsByEmail.values().toArray());
    sfdcClient.batchInsert(bulkInsertContactsByName.values().toArray());
    sfdcClient.batchFlush();
    sfdcClient.batchUpdate(bulkUpdateContacts.values().toArray());
    sfdcClient.batchFlush();

    if (oppMode) {
      for (int i = 0; i < importEvents.size(); i++) {
        CrmImportEvent importEvent = importEvents.get(i);

        // If the account and contact upserts both failed, avoid creating an orphaned opp.
        if (nonBatchAccountIds.get(i) == null && nonBatchContactIds.get(i) == null) {
          continue;
        }

        log.info("import processing opportunities on row {} of {}", i + 2, importEvents.size() + 1);

        if (!importEvent.opportunitySkipDuplicateCheck && Strings.isNullOrEmpty(importEvent.opportunityId)) {
          List<SObject> existingOpportunities = sfdcClient.searchDonations(nonBatchAccountIds.get(i), nonBatchContactIds.get(i),
              importEvent.opportunityDate, importEvent.opportunityAmount.doubleValue());
          if (!existingOpportunities.isEmpty()) {
            log.info("skipping opp {} import, due to possible duplicate: {}", i, existingOpportunities.get(0).getId());
            continue;
          }
        }

        SObject opportunity = new SObject("Opportunity");

        opportunity.setField("AccountId", nonBatchAccountIds.get(i));

        if (!Strings.isNullOrEmpty(importEvent.opportunityCampaignId)) {
          opportunity.setField("CampaignId", importEvent.opportunityCampaignId);
        } else if (!Strings.isNullOrEmpty(importEvent.opportunityCampaignName) && campaignNameToId.containsKey(importEvent.opportunityCampaignName)) {
          opportunity.setField("CampaignId", campaignNameToId.get(importEvent.opportunityCampaignName));
        }

        if (!Strings.isNullOrEmpty(importEvent.opportunityId)) {
          opportunity.setId(importEvent.opportunityId);
          bulkUpdateOpportunities.put(importEvent.opportunityId, opportunity);

          // TODO: Do this in a second pass and support opp inserts.
          if (!Strings.isNullOrEmpty(nonBatchContactIds.get(i))) {
            SObject contactRole = new SObject("OpportunityContactRole");
            contactRole.setField("OpportunityId", importEvent.opportunityId);
            contactRole.setField("ContactId", nonBatchContactIds.get(i));
            contactRole.setField("IsPrimary", true);
            contactRole.setField("Role", "Donor");
            bulkInsertOpportunityContactRoles.put(importEvent.opportunityId, contactRole);
          }
        } else {
          bulkInsertOpportunities.add(opportunity);
        }

        setBulkImportOpportunityFields(opportunity, importEvent);
      }

      sfdcClient.batchInsert(bulkInsertOpportunities.toArray());
      sfdcClient.batchFlush();
      sfdcClient.batchUpdate(bulkUpdateOpportunities.values().toArray());
      sfdcClient.batchFlush();
      sfdcClient.batchInsert(bulkInsertOpportunityContactRoles.values().toArray());
      sfdcClient.batchFlush();
    }

    log.info("bulk import complete");
  }

  private SObject updateBulkImportAccount(String accountId, CrmImportEvent importEvent,
      Map<String, SObject> updateAccounts) throws InterruptedException, ConnectionException {
    SObject account = new SObject("Account");
    account.setId(accountId);
    setBulkImportAccountFields(account, importEvent, null);
    updateAccounts.put(accountId, account);
    return account;
  }

  private SObject insertBulkImportAccount(CrmImportEvent importEvent, String accountName)
      throws InterruptedException, ConnectionException {
    SObject account = new SObject("Account");
    setBulkImportAccountFields(account, importEvent, accountName);
    String accountId = sfdcClient.insert(account).getId();
    account.setId(accountId);
    return account;
  }

  private SObject updateBulkImportContact(String contactId, CrmImportEvent importEvent, Map<String, SObject> updateContacts)
      throws InterruptedException, ConnectionException {
    SObject contact = new SObject("Contact");
    contact.setId(contactId);
    setBulkImportContactFields(contact, importEvent);
    updateContacts.put(contactId, contact);
    return contact;
  }

  private SObject insertBulkImportContact(
      SObject account,
      CrmImportEvent importEvent,
      Map<String, SObject> bulkInsertContactsByEmail,
      Map<String, SObject> bulkInsertContactsByName,
      Multimap<String, SObject> existingContactsByEmail,
      boolean nonBatchMode
  ) throws InterruptedException, ConnectionException {
    SObject contact = new SObject("Contact");
    setBulkImportContactFields(contact, importEvent);

    if (account == null) {
      // TODO: Chicken vs. egg problem. We need the account first to import the contact, but need to think through how
      //  to do that in multiple passes when we don't have a clear lookup field. Maybe the row #? Separate loops?
      //  Concerned about doing this, having the contact insert fail, and having a bunch of orphaned households.
      account = insertBulkImportAccount(importEvent, contact.getField("LastName") + " Household");
    }
    contact.setField("AccountId", account.getId());

    if (nonBatchMode) {
      SaveResult saveResult = sfdcClient.insert(contact);
      contact.setId(saveResult.getId());
    } else {
      if (!Strings.isNullOrEmpty((String) contact.getField("Email"))) {
        bulkInsertContactsByEmail.put((String) contact.getField("Email"), contact);
      } else {
        bulkInsertContactsByName.put(contact.getField("FirstName") + " " + contact.getField("LastName"), contact);
      }
    }

    // Since we hold existingContactsByEmail in memory and don't requery it, add entries as we go to prevent
    // duplicate inserts.
    if (!Strings.isNullOrEmpty((String) contact.getField("Email"))) {
      existingContactsByEmail.put((String) contact.getField("Email"), contact);
    }

    return contact;
  }

  @Override
  public List<CrmContact> getEmailContacts(Calendar updatedSince, String filter) throws Exception {
    return sfdcClient.getEmailContacts(updatedSince, filter).stream().map(this::toCrmContact).collect(Collectors.toList());
  }

  @Override
  public double getDonationsTotal(String filter) throws Exception {
    // TODO
    return 0.0;
  }

  protected void setBulkImportContactFields(SObject contact, CrmImportEvent importEvent) throws InterruptedException, ConnectionException {
    if (!Strings.isNullOrEmpty(importEvent.contactRecordTypeId)) {
      contact.setField("RecordTypeId", importEvent.contactRecordTypeId);
    } else if (!Strings.isNullOrEmpty(importEvent.contactRecordTypeName)) {
      // TODO: CACHE THIS!
      SObject salesRecordType = sfdcClient.getRecordTypeByName(importEvent.contactRecordTypeName).get();
      contact.setField("RecordTypeId", salesRecordType.getId());
    }

    // last name is required
    if (Strings.isNullOrEmpty(importEvent.contactLastName)) {
      contact.setField("LastName", "Anonymous");
    } else {
      contact.setField("FirstName", importEvent.contactFirstName);
      contact.setField("LastName", importEvent.contactLastName);
    }

    contact.setField("MailingStreet", importEvent.contactMailingStreet);
    contact.setField("MailingCity", importEvent.contactMailingCity);
    contact.setField("MailingState", importEvent.contactMailingState);
    contact.setField("MailingPostalCode", importEvent.contactMailingZip);
    contact.setField("MailingCountry", importEvent.contactMailingCountry);
    contact.setField("HomePhone", importEvent.contactHomePhone);
    contact.setField("MobilePhone", importEvent.contactMobilePhone);
    contact.setField("Email", importEvent.contactEmail);

    if (importEvent.contactOptInEmail != null && importEvent.contactOptInEmail) {
      setField(contact, env.getConfig().salesforce.fieldDefinitions.emailOptIn, true);
      setField(contact, env.getConfig().salesforce.fieldDefinitions.emailOptOut, false);
    }
    if (importEvent.contactOptOutEmail != null && importEvent.contactOptOutEmail) {
      setField(contact, env.getConfig().salesforce.fieldDefinitions.emailOptIn, false);
      setField(contact, env.getConfig().salesforce.fieldDefinitions.emailOptOut, true);
    }
    if (importEvent.contactOptInSms != null && importEvent.contactOptInSms) {
      setField(contact, env.getConfig().salesforce.fieldDefinitions.smsOptIn, true);
      setField(contact, env.getConfig().salesforce.fieldDefinitions.smsOptOut, false);
    }
    if (importEvent.contactOptOutSms != null && importEvent.contactOptOutSms) {
      setField(contact, env.getConfig().salesforce.fieldDefinitions.smsOptIn, false);
      setField(contact, env.getConfig().salesforce.fieldDefinitions.smsOptOut, true);
    }

    contact.setField("OwnerId", importEvent.ownerId);

    importEvent.raw.entrySet().stream().filter(entry -> entry.getKey().startsWith("Contact Custom "))
        .forEach(entry -> contact.setField(entry.getKey().replace("Contact Custom ", ""), getCustomBulkValue(entry.getValue())));
  }

  protected void setBulkImportAccountFields(SObject account, CrmImportEvent importEvent, String accountName) throws InterruptedException, ConnectionException {
    // TODO: CACHE THIS!
    if (!Strings.isNullOrEmpty(importEvent.accountRecordTypeId)) {
      account.setField("RecordTypeId", importEvent.accountRecordTypeId);
    } else if (!Strings.isNullOrEmpty(importEvent.accountRecordTypeName)) {
      SObject salesRecordType = sfdcClient.getRecordTypeByName(importEvent.accountRecordTypeName).get();
      account.setField("RecordTypeId", salesRecordType.getId());
    }

    setField(account, "Name", accountName);
    setField(account, "BillingStreet", importEvent.accountBillingStreet);
    setField(account, "BillingCity", importEvent.accountBillingCity);
    setField(account, "BillingState", importEvent.accountBillingState);
    setField(account, "BillingPostalCode", importEvent.accountBillingZip);
    setField(account, "BillingCountry", importEvent.accountBillingCountry);

    account.setField("OwnerId", importEvent.ownerId);

    importEvent.raw.entrySet().stream().filter(entry -> entry.getKey().startsWith("Account Custom "))
        .forEach(entry -> account.setField(entry.getKey().replace("Account Custom ", ""), entry.getValue()));
  }

  protected void setBulkImportOpportunityFields(SObject opportunity, CrmImportEvent importEvent) throws ConnectionException, InterruptedException {
    if (!Strings.isNullOrEmpty(importEvent.opportunityRecordTypeId)) {
      opportunity.setField("RecordTypeId", importEvent.opportunityRecordTypeId);
    } else if (!Strings.isNullOrEmpty(importEvent.opportunityRecordTypeName)) {
      SObject salesRecordType = sfdcClient.getRecordTypeByName(importEvent.opportunityRecordTypeName).get();
      opportunity.setField("RecordTypeId", salesRecordType.getId());
    }
    opportunity.setField("Name", importEvent.opportunityName);
    opportunity.setField("Description", importEvent.opportunityDescription);
    if (importEvent.opportunityAmount != null) {
      opportunity.setField("Amount", importEvent.opportunityAmount.doubleValue());
    }
    opportunity.setField("StageName", importEvent.opportunityStageName);
    opportunity.setField("CloseDate", importEvent.opportunityDate);

    opportunity.setField("OwnerId", importEvent.ownerId);

    importEvent.raw.entrySet().stream().filter(entry -> entry.getKey().startsWith("Opportunity Custom "))
        .forEach(entry -> opportunity.setField(entry.getKey().replace("Opportunity Custom ", ""), entry.getValue()));
  }

  // TODO: This is going to be a pain in the butt, but we'll try to dynamically support different data types for custom
  //  fields in bulk imports/updates, without requiring the data type to be provided. This is going to be brittle...
  protected Object getCustomBulkValue(String value) {
    Calendar c = null;
    try {
      c = Utils.getCalendarFromDateString(value);
    } catch (Exception e) {}

    if (c != null) {
      return c;
    } else if ("true".equalsIgnoreCase(value)) {
      return true;
    } else if ("false".equalsIgnoreCase(value)) {
      return false;
    } else {
      return value;
    }
  }

  protected String getStringField(SObject sObject, String name) {
    // Optional field names may not be configured in env.json, so ensure we actually have a name first...
    if (Strings.isNullOrEmpty(name)) {
      return null;
    }

    return (String) sObject.getField(name);
  }

  protected Boolean getBooleanField(SObject sObject, String name) {
    String s = getStringField(sObject, name);
    if (Strings.isNullOrEmpty(s)) return null;
    return Boolean.parseBoolean(s);
  }

  protected void setField(SObject sObject, String name, Object value) {
    // Optional field names may not be configured in env.json, so ensure we actually have a name first...
    // Likewise, don't set a null or empty value.
    if (!Strings.isNullOrEmpty(name) && value != null && !Strings.isNullOrEmpty(value.toString())) {
      sObject.setField(name, value);
    }
  }

  protected Optional<SObject> getCampaignOrDefault(PaymentGatewayEvent paymentGatewayEvent) throws ConnectionException, InterruptedException {
    Optional<SObject> campaign = Optional.empty();

    String campaignIdOrName = paymentGatewayEvent.getMetadataValue(env.getConfig().metadataKeys.campaign);
    if (!Strings.isNullOrEmpty(campaignIdOrName)) {
      if (campaignIdOrName.startsWith("701")) {
        campaign = sfdcClient.getCampaignById(campaignIdOrName);
      } else {
        campaign = sfdcClient.getCampaignByName(campaignIdOrName);
      }
    }

    if (campaign.isEmpty()) {
      String defaultCampaignId = env.getConfig().salesforce.defaultCampaignId;
      if (Strings.isNullOrEmpty(defaultCampaignId)) {
        log.info("campaign {} not found, but no default provided", campaignIdOrName);
      } else {
        log.info("campaign {} not found; using default: {}", campaignIdOrName, defaultCampaignId);
        campaign = sfdcClient.getCampaignById(defaultCampaignId);
      }
    }

    return campaign;
  }

  protected CrmCampaign toCrmCampaign(SObject sObject) {
    String id = sObject.getId();
    return new CrmCampaign(id);
  }

  protected Optional<CrmCampaign> toCrmCampaign(Optional<SObject> sObject) {
    return sObject.map(this::toCrmCampaign);
  }

  // TODO: starting to feel like we need an object mapper lib...

  protected CrmAccount toCrmAccount(SObject sObject) {
    CrmAddress crmAddress = new CrmAddress(
        (String) sObject.getField("BillingStreet"),
        (String) sObject.getField("BillingCity"),
        (String) sObject.getField("BillingState"),
        (String) sObject.getField("BillingPostalCode"),
        (String) sObject.getField("BillingCountry")
    );

    CrmAccount.Type type = CrmAccount.Type.HOUSEHOLD;
    if (sObject.getChild("RecordType") != null) {
      String recordTypeName = (String) sObject.getChild("RecordType").getField("Name");
      recordTypeName = recordTypeName == null ? "" : recordTypeName.toLowerCase(Locale.ROOT);
      // TODO: Customize record type names through env.json?
      if (recordTypeName.contains("business") || recordTypeName.contains("church") || recordTypeName.contains("org") || recordTypeName.contains("group"))  {
        type = CrmAccount.Type.ORGANIZATION;
      }
    }

    return new CrmAccount(
        sObject.getId(),
        (String) sObject.getField("Name"),
        crmAddress,
        type,
        sObject,
        "https://" + env.getConfig().salesforce.url + "/lightning/r/Account/" + sObject.getId() + "/view"
    );
  }

  protected Optional<CrmAccount> toCrmAccount(Optional<SObject> sObject) {
    return sObject.map(this::toCrmAccount);
  }

  protected CrmContact toCrmContact(SObject sObject) {
    CrmContact.PreferredPhone preferredPhone = null;
    if (sObject.getField("npe01__PreferredPhone__c") != null) {
      preferredPhone = CrmContact.PreferredPhone.fromName((String) sObject.getField("npe01__PreferredPhone__c"));
    }

    CrmAddress crmAddress = new CrmAddress();
    Double totalOppAmount = null;
    Integer numberOfClosedOpps = null;
    Calendar firstCloseDate = null;
    Calendar lastCloseDate = null;

    if (sObject.getChild("Account") != null && sObject.getChild("Account").hasChildren()) {
      crmAddress = new CrmAddress(
          // TODO: This was updated to use Account instead of assuming Billing fields are directly on the Contact.
          //  THOROUGHLY test this with all clients.
          (String) sObject.getChild("Account").getField("BillingStreet"),
          (String) sObject.getChild("Account").getField("BillingCity"),
          (String) sObject.getChild("Account").getField("BillingState"),
          (String) sObject.getChild("Account").getField("BillingPostalCode"),
          (String) sObject.getChild("Account").getField("BillingCountry")
      );

      totalOppAmount = Double.valueOf((String) sObject.getChild("Account").getField("npo02__TotalOppAmount__c"));
      numberOfClosedOpps = Double.valueOf((String) sObject.getChild("Account").getField("npo02__NumberOfClosedOpps__c")).intValue();
      try {
        firstCloseDate = Utils.getCalendarFromDateString((String) sObject.getChild("Account").getField("npo02__FirstCloseDate__c"));
        lastCloseDate = Utils.getCalendarFromDateString((String) sObject.getChild("Account").getField("npo02__LastCloseDate__c"));
      } catch (ParseException e) {
        log.error("unable to parse first/last close date", e);
      }
    }

    String ownerName = null;
    if (sObject.getChild("Owner") != null && sObject.getChild("Owner").hasChildren()) {
      ownerName = (String) sObject.getChild("Owner").getField("Name");
    }

    List<String> emailGroups = new ArrayList<>();
    String emailGroupList = getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.emailGroups);
    // assumes a multiselect picklist, which is a single ; separated string
    if (!Strings.isNullOrEmpty(emailGroupList)) {
      emailGroups = Arrays.stream(emailGroupList.split(";")).toList();
    }

    // ALWAYS use the standard Phone field as a default, as we need that for a backup for SMS tools when MobilePhone
    // isn't present. HomePhone itself is rare and we've seen schemas where it's not even included.
    String homePhone = (String) sObject.getField("Phone");
    if (Strings.isNullOrEmpty(homePhone)) {
      homePhone = (String) sObject.getField("HomePhone");
    }

    return new CrmContact(
        sObject.getId(),
        (String) sObject.getField("AccountId"),
        (String) sObject.getField("FirstName"),
        (String) sObject.getField("LastName"),
        (String) sObject.getField("Name"),
        (String) sObject.getField("Email"),
        homePhone,
        (String) sObject.getField("MobilePhone"),
        (String) sObject.getField("npe01__WorkPhone__c"),
        preferredPhone,
        crmAddress,
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.emailOptIn),
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.emailOptOut),
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.smsOptIn),
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.smsOptOut),
        (String) sObject.getField("Owner.Id"),
        ownerName,
        totalOppAmount,
        numberOfClosedOpps,
        firstCloseDate,
        lastCloseDate,
        emailGroups,
        getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.contactLanguage),
        sObject,
        "https://" + env.getConfig().salesforce.url + "/lightning/r/Contact/" + sObject.getId() + "/view",
        sObject::getField
    );
  }

  protected Optional<CrmContact> toCrmContact(Optional<SObject> sObject) {
    return sObject.map(this::toCrmContact);
  }

  protected List<CrmContact> toCrmContact(List<SObject> sObjects) {
    return sObjects.stream().map(this::toCrmContact).collect(Collectors.toList());
  }

  protected PagedResults<CrmContact> toCrmContact(PagedResults<SObject> sObjects) {
    return new PagedResults<>(sObjects.getResults().stream().map(this::toCrmContact).collect(Collectors.toList()),
        sObjects.getPageSize(), sObjects.getNextPageToken());
  }

  protected CrmDonation toCrmDonation(SObject sObject) {
    String id = sObject.getId();
    String paymentGatewayName = getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.paymentGatewayName);
    String paymentGatewayTransactionId = getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.paymentGatewayTransactionId);
    Double amount = Double.valueOf(sObject.getField("Amount").toString());
    Calendar closeDate = null;
    try {
      closeDate = Utils.getCalendarFromDateString((String) sObject.getField("CloseDate"));
    } catch (ParseException e) {
      log.warn("unable to parse date", e);
    }

    // TODO: yuck -- allow subclasses to more easily define custom mappers?
    Object statusNameO = sObject.getField("StageName");
    String statusName = statusNameO == null ? "" : statusNameO.toString();
    CrmDonation.Status status;
    if ("Posted".equalsIgnoreCase(statusName) || "Closed Won".equalsIgnoreCase(statusName)) {
      status = CrmDonation.Status.SUCCESSFUL;
    } else if (statusName.contains("fail") || statusName.contains("Fail")) {
      status = CrmDonation.Status.FAILED;
    } else if (statusName.contains("refund") || statusName.contains("Refund")) {
      status = CrmDonation.Status.REFUNDED;
    } else {
      status = CrmDonation.Status.PENDING;
    }

    CrmAccount account = new CrmAccount();
    account.id = (String) sObject.getField("AccountId");
    if (sObject.getChild("Account") != null && sObject.getChild("Account").hasChildren())
      account = toCrmAccount((SObject) sObject.getChild("Account"));
    CrmContact contact = new CrmContact();
    contact.id = (String) sObject.getField("ContactId");
    if (sObject.getChild("npsp__Primary_Contact__r") != null && sObject.getChild("npsp__Primary_Contact__r").hasChildren())
      contact = toCrmContact((SObject) sObject.getChild("npsp__Primary_Contact__r"));

    return new CrmDonation(
        id,
        (String) sObject.getField("Name"),
        amount,
        paymentGatewayName,
        paymentGatewayTransactionId,
        status,
        closeDate,
        account,
        contact,
        sObject,
        "https://" + env.getConfig().salesforce.url + "/lightning/r/Opportunity/" + sObject.getId() + "/view"
    );
  }

  protected Optional<CrmDonation> toCrmDonation(Optional<SObject> sObject) {
    return sObject.map(this::toCrmDonation);
  }

  protected List<CrmDonation> toCrmDonation(List<SObject> sObjects) {
    return sObjects.stream().map(this::toCrmDonation).collect(Collectors.toList());
  }

  protected CrmRecurringDonation toCrmRecurringDonation(SObject sObject) {
    String id = sObject.getId();
    String subscriptionId = getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.paymentGatewaySubscriptionId);
    String customerId = getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId);
    String paymentGatewayName = getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.paymentGatewayName);
    Double amount = Double.parseDouble(sObject.getField("npe03__Amount__c").toString());
    boolean active = "Open".equalsIgnoreCase(sObject.getField("npe03__Open_Ended_Status__c").toString());
    CrmRecurringDonation.Frequency frequency = CrmRecurringDonation.Frequency.fromName(sObject.getField("npe03__Installment_Period__c").toString());
    String donationName = getStringField(sObject, "Name");

    CrmAccount account = null;
    if (sObject.getChild("npe03__Organization__r") != null && sObject.getChild("npe03__Organization__r").hasChildren())
      account = toCrmAccount((SObject) sObject.getChild("npe03__Organization__r"));
    CrmContact contact = null;
    if (sObject.getChild("npe03__Contact__r") != null && sObject.getChild("npe03__Contact__r").hasChildren())
      contact = toCrmContact((SObject) sObject.getChild("npe03__Contact__r"));

    return new CrmRecurringDonation(
        id,
        subscriptionId,
        customerId,
        amount,
        paymentGatewayName,
        active,
        frequency,
        donationName,
        account,
        contact,
        sObject,
        "https://" + env.getConfig().salesforce.url + "/lightning/r/npe03__Recurring_Donation__c/" + sObject.getId() + "/view"
    );
  }

  protected Optional<CrmRecurringDonation> toCrmRecurringDonation(Optional<SObject> sObject) {
    return sObject.map(this::toCrmRecurringDonation);
  }

  protected CrmUser toCrmUser(SObject sObject) {
    return new CrmUser(sObject.getId(), sObject.getField("Email").toString());
  }

  protected Optional<CrmUser> toCrmUser(Optional<SObject> sObject) {
    return sObject.map(this::toCrmUser);
  }
}
