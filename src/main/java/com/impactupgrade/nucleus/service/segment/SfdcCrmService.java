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
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.model.CrmRecord;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.util.Utils;
import com.sforce.soap.metadata.FieldType;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.ParseException;
import java.time.ZonedDateTime;
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
  public List<CrmAccount> getAccountsByIds(List<String> ids) throws Exception {
    return toCrmAccount(sfdcClient.getAccountsByIds(ids));
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
  public List<CrmContact> getContactsByEmails(List<String> emails) throws Exception {
    return toCrmContact(sfdcClient.getContactsByEmails(emails));
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
  public List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds) throws Exception {
    return toCrmDonation(sfdcClient.getDonationsByTransactionIds(transactionIds));
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
        part = part.replaceAll("'", "\\\\'");
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
            crmCustomField.scale,
            // TODO: picklist support in CrmCustomField
            null,
            null
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
  public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId, String accountId, String contactId) throws Exception {
    return toCrmRecurringDonation(sfdcClient.getRecurringDonationBySubscriptionId(subscriptionId));
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    return toCrmRecurringDonation(sfdcClient.getRecurringDonationById(id));
  }

  @Override
  public void updateDonation(CrmDonation crmDonation) throws Exception {
    SObject opportunity = new SObject("Opportunity");
    opportunity.setId(crmDonation.id);

    // We need to set all fields again in order to tackle special cases. Ex: if this was a donation in a converted
    // currency, that data won't be available until the transaction actually succeeds. However, note that we currently
    // ignore the campaign -- no need to currently re-provide that side.
    setOpportunityFields(opportunity, Optional.empty(), crmDonation);

    sfdcClient.update(opportunity);
  }

  protected void setAccountFields(SObject account, CrmAccount crmAccount) {
    account.setField("Name", crmAccount.name);

    account.setField("BillingStreet", crmAccount.billingAddress.street);
    account.setField("BillingCity", crmAccount.billingAddress.city);
    account.setField("BillingState", crmAccount.billingAddress.state);
    account.setField("BillingPostalCode", crmAccount.billingAddress.postalCode);
    account.setField("BillingCountry", crmAccount.billingAddress.country);
    account.setField("ShippingStreet", crmAccount.mailingAddress.street);
    account.setField("ShippingCity", crmAccount.mailingAddress.city);
    account.setField("ShippingState", crmAccount.mailingAddress.state);
    account.setField("ShippingPostalCode", crmAccount.mailingAddress.postalCode);
    account.setField("ShippingCountry", crmAccount.mailingAddress.country);

    Map<EnvironmentConfig.AccountType, String> accountTypeToRecordTypeIds = env.getConfig().salesforce.accountTypeToRecordTypeIds;
    if (crmAccount.type != null && accountTypeToRecordTypeIds.containsKey(crmAccount.type)) {
      account.setField("RecordTypeId", accountTypeToRecordTypeIds.get(crmAccount.type));
    }
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
    contact.setField("AccountId", crmContact.account.id);
    contact.setField("FirstName", crmContact.firstName);
    contact.setField("LastName", crmContact.lastName);
    contact.setField("Email", crmContact.email);
    contact.setField("MobilePhone", crmContact.mobilePhone);
    if (crmContact.preferredPhone != null) {
      contact.setField("Npe01__PreferredPhone__c", crmContact.preferredPhone.toString());
    }
    setField(contact, env.getConfig().salesforce.fieldDefinitions.contactLanguage, crmContact.language);

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
  public String insertDonation(CrmDonation crmDonation) throws Exception {
    Optional<SObject> campaign = getCampaignOrDefault(crmDonation);
    String recurringDonationId = crmDonation.recurringDonation.id;

    if (!Strings.isNullOrEmpty(recurringDonationId)) {
      // get the next pledged donation from the recurring donation
      Optional<SObject> pledgedOpportunityO = sfdcClient.getNextPledgedDonationByRecurringDonationId(recurringDonationId);
      if (pledgedOpportunityO.isPresent()) {
        SObject pledgedOpportunity = pledgedOpportunityO.get();
        return processPledgedDonation(pledgedOpportunity, campaign, recurringDonationId, crmDonation);
      } else {
        log.warn("unable to find SFDC pledged donation for recurring donation {} that isn't in the future",
            recurringDonationId);
      }
    }

    // not a recurring donation, OR an existing pledged donation didn't exist -- create a new donation
    return processNewDonation(campaign, recurringDonationId, crmDonation);
  }

  protected String processPledgedDonation(SObject pledgedOpportunity, Optional<SObject> campaign,
      String recurringDonationId, CrmDonation crmDonation) throws Exception {
    log.info("found SFDC pledged opportunity {} in recurring donation {}",
        pledgedOpportunity.getId(), recurringDonationId);

    // check to see if the recurring donation was a failed attempt or successful
    if (crmDonation.status == CrmDonation.Status.SUCCESSFUL) {
      // update existing pledged donation to Closed Won
      SObject updateOpportunity = new SObject("Opportunity");
      updateOpportunity.setId(pledgedOpportunity.getId());
      setOpportunityFields(updateOpportunity, campaign, crmDonation);
      sfdcClient.update(updateOpportunity);
      return pledgedOpportunity.getId();
    } else {
      // subscription payment failed
      // create new Opportunity and post it to the recurring donation leaving the Pledged donation there
      return processNewDonation(campaign, recurringDonationId, crmDonation);
    }
  }

  protected String processNewDonation(Optional<SObject> campaign, String recurringDonationId,
      CrmDonation crmDonation) throws Exception {
    SObject opportunity = new SObject("Opportunity");

    opportunity.setField("AccountId", crmDonation.account.id);
    opportunity.setField("Npe03__Recurring_Donation__c", recurringDonationId);

    setOpportunityFields(opportunity, campaign, crmDonation);

    String oppId = sfdcClient.insert(opportunity).getId();

    if (!Strings.isNullOrEmpty(crmDonation.contact.id)) {
      SObject contactRole = new SObject("OpportunityContactRole");
      contactRole.setField("OpportunityId", oppId);
      contactRole.setField("ContactId", crmDonation.contact.id);
      contactRole.setField("IsPrimary", true);
      // TODO: Not present by default at all orgs.
//      contactRole.setField("Role", "Donor");
      sfdcClient.insert(contactRole);
    }

    return oppId;
  }

  protected void setOpportunityFields(SObject opportunity, Optional<SObject> campaign, CrmDonation crmDonation) throws Exception {
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayName)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayName, crmDonation.gatewayName);
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayTransactionId)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayTransactionId, crmDonation.transactionId);
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId, crmDonation.customerId);
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.fund)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.fund, crmDonation.getMetadataValue(env.getConfig().metadataKeys.fund));
    }

    if (crmDonation.transactionType != null) {
      String recordTypeId = env.getConfig().salesforce.transactionTypeToRecordTypeIds.get(crmDonation.transactionType);
      opportunity.setField("RecordTypeId", recordTypeId);
    } else {
      String recordTypeId = crmDonation.getMetadataValue(env.getConfig().metadataKeys.recordType);
      opportunity.setField("RecordTypeId", recordTypeId);
    }

    // check to see if this was a failed payment attempt and set the StageName accordingly
    if (crmDonation.status == CrmDonation.Status.SUCCESSFUL) {
      opportunity.setField("StageName", "Closed Won");
    } else {
      opportunity.setField("StageName", "Failed Attempt");
    }

    opportunity.setField("Amount", crmDonation.amount);
    opportunity.setField("CampaignId", campaign.map(SObject::getId).orElse(null));
    opportunity.setField("CloseDate", GregorianCalendar.from(crmDonation.closeDate));
    opportunity.setField("Description", crmDonation.description);

    // purely a default, but we generally expect this to be overridden
    opportunity.setField("Name", crmDonation.contact.getFullName() + " Donation");
  }

  @Override
  public void refundDonation(CrmDonation crmDonation) throws Exception {
    SObject opportunity = new SObject("Opportunity");
    opportunity.setId(crmDonation.id);
    setOpportunityRefundFields(opportunity, crmDonation);

    sfdcClient.update(opportunity);
  }

  protected void setOpportunityRefundFields(SObject opportunity, CrmDonation crmDonation) throws Exception {
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundId)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundId, crmDonation.refundId);
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDate)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDate, GregorianCalendar.from(crmDonation.refundDate));
    }
    // TODO: LJI/TER/DR specific? They all have it, but I can't remember if we explicitly added it.
    opportunity.setField("StageName", "Refunded");
  }

  @Override
  public void insertDonationDeposit(List<CrmDonation> crmDonations) throws Exception {
    Map<String, SObject> opportunityUpdates = new HashMap<>();

    for (CrmDonation crmDonation : crmDonations) {
      // Note that the opportunityUpdates map is in place for situations where a charge and its refund are in the same
      // deposit. In that situation, the Donation CRM ID would wind up in the batch update twice, which causes errors
      // downstream. Instead, ensure we're setting the fields for both situations, but on a single object.
      SObject opportunityUpdate;
      if (opportunityUpdates.containsKey(crmDonation.id)) {
        opportunityUpdate = opportunityUpdates.get(crmDonation.id);
      } else {
        opportunityUpdate = new SObject("Opportunity");
        opportunityUpdate.setId(crmDonation.id);
        opportunityUpdates.put(crmDonation.id, opportunityUpdate);
      }
      setDonationDepositFields((SObject) crmDonation.crmRawObject, opportunityUpdate, crmDonation);

      sfdcClient.batchUpdate(opportunityUpdate);
    }

    sfdcClient.batchFlush();
  }

  protected void setDonationDepositFields(SObject existingOpportunity, SObject opportunityUpdate,
      CrmDonation crmDonation) throws InterruptedException {
    // If the payment gateway event has a refund ID, this item in the payout was a refund. Mark it as such!
    if (!Strings.isNullOrEmpty(crmDonation.refundId)) {
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundId)) {
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDepositDate, GregorianCalendar.from(crmDonation.depositDate));
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDepositId, crmDonation.depositId);
      }
    } else {
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositId)) {
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositDate, GregorianCalendar.from(crmDonation.depositDate));
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositId, crmDonation.depositId);
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositNetAmount, crmDonation.netAmountInDollars);
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositFee, crmDonation.feeInDollars);
      }
    }
  }

  @Override
  public String insertRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
    SObject recurringDonation = new SObject("Npe03__Recurring_Donation__c");
    setRecurringDonationFields(recurringDonation, getCampaignOrDefault(crmRecurringDonation), crmRecurringDonation);
    return sfdcClient.insert(recurringDonation).getId();
  }

  /**
   * Set any necessary fields on an RD before it's inserted.
   */
  protected void setRecurringDonationFields(SObject recurringDonation, Optional<SObject> campaign, CrmRecurringDonation crmRecurringDonation) throws Exception {
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayName)) {
      recurringDonation.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayName, crmRecurringDonation.gatewayName);
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewaySubscriptionId)) {
      recurringDonation.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewaySubscriptionId, crmRecurringDonation.subscriptionId);
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId)) {
      recurringDonation.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId, crmRecurringDonation.customerId);
    }

    recurringDonation.setField("Npe03__Amount__c", crmRecurringDonation.amount);
    recurringDonation.setField("Npe03__Open_Ended_Status__c", "Open");
    recurringDonation.setField("Npe03__Schedule_Type__c", "Multiply By");
    if (crmRecurringDonation.frequency != null) {
      recurringDonation.setField("Npe03__Installment_Period__c", crmRecurringDonation.frequency.name());
    }
    recurringDonation.setField("Npe03__Date_Established__c", GregorianCalendar.from(crmRecurringDonation.subscriptionStartDate));
    recurringDonation.setField("Npe03__Next_Payment_Date__c", GregorianCalendar.from(crmRecurringDonation.subscriptionNextDate));
    recurringDonation.setField("Npe03__Recurring_Donation_Campaign__c", getCampaignOrDefault(crmRecurringDonation).map(SObject::getId).orElse(null));

    // Purely a default, but we expect this to be generally overridden.
    recurringDonation.setField("Name", crmRecurringDonation.contact.getFullName() + " Recurring Donation");

    if (env.getConfig().salesforce.enhancedRecurringDonations) {
      // NPSP Enhanced RDs will not allow you to associate the RD directly with an Account if it's a household, instead
      // forcing us to use the contact. But, since we don't know at this point if this is a business gift, we
      // unfortunately need to assume the existence of a contactId means we should use it.
      if (!Strings.isNullOrEmpty(crmRecurringDonation.contact.id)) {
        recurringDonation.setField("Npe03__Contact__c", crmRecurringDonation.contact.id);
      } else {
        recurringDonation.setField("Npe03__Organization__c", crmRecurringDonation.account.id);
      }

      recurringDonation.setField("npsp__RecurringType__c", "Open");
      // It's a picklist, so it has to be a string and not numeric :(
      recurringDonation.setField("npsp__Day_of_Month__c", crmRecurringDonation.subscriptionStartDate.getDayOfMonth() + "");
    } else {
      // Legacy behavior was to always use the Account, regardless if it was a business or household. Stick with that
      // by default -- we have some orgs that depend on it.
      recurringDonation.setField("Npe03__Organization__c", crmRecurringDonation.account.id);
    }
  }

  @Override
  public void closeRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
    SObject toUpdate = new SObject("Npe03__Recurring_Donation__c");
    toUpdate.setId(crmRecurringDonation.id);
    toUpdate.setField("Npe03__Open_Ended_Status__c", "Closed");
    setRecurringDonationFieldsForClose(toUpdate, crmRecurringDonation);
    sfdcClient.update(toUpdate);
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
  public String insertOpportunity(CrmOpportunity crmOpportunity) throws Exception {
    SObject opportunity = new SObject("Opportunity");
    opportunity.setField("RecordTypeId", crmOpportunity.recordTypeId);
    opportunity.setField("Name", crmOpportunity.name);
    opportunity.setField("npsp__Primary_Contact__c", crmOpportunity.contact.id);
    opportunity.setField("CloseDate", Calendar.getInstance());
    // TODO: Good enough for now, but likely needs to be customized.
    opportunity.setField("StageName", "Pledged");
    opportunity.setField("OwnerId", crmOpportunity.ownerId);
    opportunity.setField("CampaignId", crmOpportunity.campaignId);
    opportunity.setField("Description", crmOpportunity.description);
    return sfdcClient.insert(opportunity).getId();
  }

  @Override
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    CrmRecurringDonation crmRecurringDonation = manageDonationEvent.getCrmRecurringDonation();

    SObject toUpdate = new SObject("Npe03__Recurring_Donation__c");
    toUpdate.setId(crmRecurringDonation.id);
    if (crmRecurringDonation.amount != null && crmRecurringDonation.amount > 0) {
      toUpdate.setField("Npe03__Amount__c", crmRecurringDonation.amount);
      log.info("Updating Npe03__Amount__c to {}...", crmRecurringDonation.amount);
    }
    if (manageDonationEvent.getNextPaymentDate() != null) {
      toUpdate.setField("Npe03__Next_Payment_Date__c", manageDonationEvent.getNextPaymentDate());
      log.info("Updating Npe03__Next_Payment_Date__c to {}...", manageDonationEvent.getNextPaymentDate().toString());
    }

    if (manageDonationEvent.getPauseDonation() == true) {
      toUpdate.setField("Npe03__Open_Ended_Status__c", "Closed");
      toUpdate.setFieldsToNull(new String[] {"Npe03__Next_Payment_Date__c"});

      if (manageDonationEvent.getPauseDonationUntilDate() == null) {
        log.info("pausing {} indefinitely...", crmRecurringDonation.id);
      } else {
        log.info("pausing {} until {}...", crmRecurringDonation.id, manageDonationEvent.getPauseDonationUntilDate().getTime());
      }
      setRecurringDonationFieldsForPause(toUpdate, manageDonationEvent);
    }

    if (manageDonationEvent.getResumeDonation() == true) {
      toUpdate.setField("Npe03__Open_Ended_Status__c", "Open");

      if (manageDonationEvent.getResumeDonationOnDate() == null) {
        log.info("resuming {} immediately...", crmRecurringDonation.id);
        toUpdate.setField("Npe03__Next_Payment_Date__c", Calendar.getInstance().getTime());
      } else {
        log.info("resuming {} on {}...", crmRecurringDonation.id, manageDonationEvent.getResumeDonationOnDate().getTime());
        toUpdate.setField("Npe03__Next_Payment_Date__c", manageDonationEvent.getResumeDonationOnDate());
      }
      setRecurringDonationFieldsForResume(toUpdate, manageDonationEvent);
    }

    sfdcClient.update(toUpdate);
    sfdcClient.refreshRecurringDonation(crmRecurringDonation.id);
  }

  // Give orgs an opportunity to clear anything else out that's unique to them, prior to the update
  protected void setRecurringDonationFieldsForClose(SObject recurringDonation,
      CrmRecurringDonation crmRecurringDonation) throws Exception {
  }

  // Give orgs an opportunity to set anything else that's unique to them, prior to pause
  protected void setRecurringDonationFieldsForPause(SObject recurringDonation,
      ManageDonationEvent manageDonationEvent) throws Exception {
  }

  // Give orgs an opportunity to set anything else that's unique to them, prior to resume
  protected void setRecurringDonationFieldsForResume(SObject recurringDonation,
      ManageDonationEvent manageDonationEvent) throws Exception {
  }

  @Override
  public List<CrmContact> getEmailContacts(Calendar updatedSince, EnvironmentConfig.EmailList emailList) throws Exception {
    List<CrmContact> contacts = sfdcClient.getEmailContacts(updatedSince, emailList.crmFilter).stream().map(this::toCrmContact).collect(Collectors.toList());
    if (!Strings.isNullOrEmpty(emailList.crmLeadFilter)) {
      contacts.addAll(sfdcClient.getEmailLeads(updatedSince, emailList.crmLeadFilter).stream().map(this::toCrmContact).toList());
    }
    return contacts;
  }

  @Override
  public double getDonationsTotal(String filter) throws Exception {
    // TODO
    return 0.0;
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

    if (importEvents.isEmpty()) {
      log.warn("no importEvents to import; exiting...");
      return;
    }

    // This entire method uses bulk queries and bulk inserts wherever possible!
    // We make multiple passes, focusing on one object at a time in order to use the bulk API.

    String[] accountCustomFields = getAccountCustomFields(importEvents);
    String[] contactCustomFields = getContactCustomFields(importEvents);
    String[] recurringDonationCustomFields = getRecurringDonationCustomFields(importEvents);
    String[] opportunityCustomFields = getOpportunityCustomFields(importEvents);

    List<String> accountIds = importEvents.stream().map(e -> e.accountId).filter(accountId -> !Strings.isNullOrEmpty(accountId)).toList();
    Map<String, SObject> existingAccountsById = new HashMap<>();
    if (accountIds.size() > 0) {
      sfdcClient.getAccountsByIds(accountIds, accountCustomFields).forEach(c -> existingAccountsById.put(c.getId(), c));
    }

    Optional<String> accountExternalRefKey = importEvents.get(0).raw.keySet().stream()
        .filter(k -> k.startsWith("Account ExtRef ")).findFirst();
    Optional<String> accountExternalRefFieldName = accountExternalRefKey.map(k -> k.replace("Account ExtRef ", ""));
    Map<String, SObject> existingAccountsByExRef = new HashMap<>();
    if (accountExternalRefKey.isPresent()) {
      List<String> accountExRefIds = importEvents.stream().map(e -> e.raw.get(accountExternalRefKey.get())).filter(s -> !Strings.isNullOrEmpty(s)).toList();
      if (accountExRefIds.size() > 0) {
        sfdcClient.getAccountsByUniqueField(accountExternalRefFieldName.get(), accountExRefIds, accountCustomFields)
            .forEach(c -> existingAccountsByExRef.put((String) c.getField(accountExternalRefFieldName.get()), c));
      }
    }

    List<String> contactIds = importEvents.stream().map(e -> e.contactId).filter(contactId -> !Strings.isNullOrEmpty(contactId)).toList();
    Map<String, SObject> existingContactsById = new HashMap<>();
    if (contactIds.size() > 0) {
      sfdcClient.getContactsByIds(contactIds, contactCustomFields).forEach(c -> existingContactsById.put(c.getId(), c));
    }

    List<String> contactEmails = importEvents.stream().map(e -> e.contactEmail).filter(email -> !Strings.isNullOrEmpty(email)).toList();
    Multimap<String, SObject> existingContactsByEmail = ArrayListMultimap.create();
    if (contactEmails.size() > 0) {
      sfdcClient.getContactsByEmails(contactEmails, contactCustomFields).forEach(c -> existingContactsByEmail.put((String) c.getField("Email"), c));
    }

    Optional<String> contactExternalRefKey = importEvents.get(0).raw.keySet().stream()
        .filter(k -> k.startsWith("Contact ExtRef ")).findFirst();
    Optional<String> contactExternalRefFieldName = contactExternalRefKey.map(k -> k.replace("Contact ExtRef ", ""));
    Map<String, SObject> existingContactsByExRef = new HashMap<>();
    if (contactExternalRefKey.isPresent()) {
      List<String> contactExRefIds = importEvents.stream().map(e -> e.raw.get(contactExternalRefKey.get())).filter(s -> !Strings.isNullOrEmpty(s)).toList();
      if (contactExRefIds.size() > 0) {
        sfdcClient.getContactsByUniqueField(contactExternalRefFieldName.get(), contactExRefIds, contactCustomFields)
            .forEach(c -> existingContactsByExRef.put((String) c.getField(contactExternalRefFieldName.get()), c));
      }
    }

    List<String> campaignNames = importEvents.stream().flatMap(e -> Stream.of(e.contactCampaignName, e.opportunityCampaignName)).filter(name -> !Strings.isNullOrEmpty(name)).collect(Collectors.toList());
    Map<String, String> campaignNameToId = Collections.emptyMap();
    if (campaignNames.size() > 0) {
      campaignNameToId = sfdcClient.getCampaignsByNames(campaignNames).stream().collect(Collectors.toMap(c -> (String) c.getField("Name"), SObject::getId));
    }

    List<String> recurringDonationIds = importEvents.stream().map(e -> e.recurringDonationId).filter(recurringDonationId -> !Strings.isNullOrEmpty(recurringDonationId)).toList();
    Map<String, SObject> existingRecurringDonationById = new HashMap<>();
    if (recurringDonationIds.size() > 0) {
      sfdcClient.getRecurringDonationsByIds(recurringDonationIds, recurringDonationCustomFields).forEach(c -> existingRecurringDonationById.put(c.getId(), c));
    }

    List<String> opportunityIds = importEvents.stream().map(e -> e.opportunityId).filter(opportunityId -> !Strings.isNullOrEmpty(opportunityId)).toList();
    Map<String, SObject> existingOpportunitiesById = new HashMap<>();
    if (opportunityIds.size() > 0) {
      sfdcClient.getDonationsByIds(opportunityIds, opportunityCustomFields).forEach(c -> existingOpportunitiesById.put(c.getId(), c));
    }

    Optional<String> opportunityExternalRefKey = importEvents.get(0).raw.keySet().stream()
        .filter(k -> k.startsWith("Opportunity ExtRef ")).findFirst();
    Map<String, SObject> existingOpportunitiesByExRefId = new HashMap<>();
    if (opportunityExternalRefKey.isPresent()) {
      List<String> opportunityExRefIds = importEvents.stream().map(e -> e.raw.get(opportunityExternalRefKey.get())).filter(s -> !Strings.isNullOrEmpty(s)).toList();
      if (opportunityExRefIds.size() > 0) {
        String fieldName = opportunityExternalRefKey.get().replace("Opportunity ExtRef ", "");
        sfdcClient.getDonationsByUniqueField(fieldName, opportunityExRefIds, opportunityCustomFields)
            .forEach(c -> existingOpportunitiesByExRefId.put((String) c.getField(fieldName), c));
      }
    }

    // we use by-id maps for batch inserts/updates, since the sheet can contain duplicate contacts/accounts
    // (especially when importing opportunities)
    List<String> batchUpdateAccounts = new ArrayList<>();
    List<String> batchInsertContacts = new ArrayList<>();
    List<String> batchUpdateContacts = new ArrayList<>();
    List<String> batchUpdateOpportunities = new ArrayList<>();
    List<String> batchInsertOpportunityContactRoles = new ArrayList<>();
    List<String> batchUpdateRecurringDonations = new ArrayList<>();

    // If we're doing Opportunity/RD inserts or Campaign updates, we unfortunately can't use batch inserts/updates of accounts/contacts.
    // TODO: We probably *can*, but the code will be rather complex to manage the variety of batch actions paired with CSV rows.
    boolean oppMode = importEvents.get(0).opportunityDate != null || importEvents.get(0).opportunityId != null;
    boolean rdMode = importEvents.get(0).recurringDonationAmount != null || importEvents.get(0).recurringDonationId != null;
    boolean nonBatchMode = oppMode || rdMode || importEvents.get(0).contactCampaignId != null || importEvents.get(0).contactCampaignName != null;

    List<String> nonBatchAccountIds = new ArrayList<>();
    List<String> nonBatchContactIds = new ArrayList<>();

    for (int i = 0; i < importEvents.size(); i++) {
      CrmImportEvent importEvent = importEvents.get(i);

//      if (i < 18000) {
//        continue;
//      }

      log.info("import processing contacts/account on row {} of {}", i + 2, importEvents.size() + 1);

      // If the accountId or account extref is explicitly given, run the account update. Otherwise, let the contact queries determine it.
      SObject account = null;
      if (!Strings.isNullOrEmpty(importEvent.accountId)) {
        SObject existingAccount = existingAccountsById.get(importEvent.accountId);

        if (existingAccount != null) {
          account = new SObject("Account");
          account.setId(existingAccount.getId());

          setBulkImportAccountFields(account, existingAccount, importEvent);
          if (!batchUpdateAccounts.contains(existingAccount.getId())) {
            batchUpdateAccounts.add(existingAccount.getId());
            sfdcClient.batchUpdate(account);
          }
        }
      } else if (accountExternalRefKey.isPresent()) {
        SObject existingAccount = existingAccountsByExRef.get(importEvent.raw.get(accountExternalRefKey.get()));

        if (existingAccount != null) {
          account = new SObject("Account");
          account.setId(existingAccount.getId());

          setBulkImportAccountFields(account, existingAccount, importEvent);
          if (!batchUpdateAccounts.contains(existingAccount.getId())) {
            batchUpdateAccounts.add(existingAccount.getId());
            sfdcClient.batchUpdate(account);
          }
        } else {
          // IMPORTANT: We're making an assumption here that if an ExtRef is provided, the expectation is that the
          // account already exists (which wasn't true, above) OR that it should be created. REGARDLESS of the contact's
          // current account, if any. We're opting to create the new account, update the contact's account ID, and
          // possibly abandon its old account (if it exists).
          // TODO: This was mainly due to CLHS, where an original FACTS migration created isolated households or, worse,
          //  combined grandparents into the student's household. Raiser's Edge has the correct relationships and
          //  and households, so we're using this to override the past.
//          account = insertBulkImportAccount(importEvent.contactLastName + " Household", importEvent);
        }
      }

      SObject contact = null;

      // A few situations have come up where there were not cleanly-split first vs. last name columns, but instead a
      // "salutation" (firstname) and then a full name. Allow users to provide the former as the firstname
      // and the latter as the "lastname", but clean it up. This must happen before the first names are split up, below!
      if (!Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(importEvent.contactLastName)
          && importEvent.contactLastName.contains(importEvent.contactFirstName)) {
        importEvent.contactLastName = importEvent.contactLastName.replace(importEvent.contactFirstName, "").trim();
      }

      // Some sources combine multiple contacts into a single comma-separated list of first names. The above
      // would deal with that if there were an ID or Email match. But here, before we insert, we'll split it up
      // into multiple contacts. Allow the first listed contact to be the primary.
      List<String> secondaryFirstNames = new ArrayList<>();
      if (!Strings.isNullOrEmpty(importEvent.contactFirstName)) {
        String[] split = importEvent.contactFirstName
            .replaceAll(",*\\s+and\\s+", ",").replaceAll(",*\\s+&\\s+", ",")
            .split(",+");
        importEvent.contactFirstName = split[0];
        secondaryFirstNames = Arrays.stream(split).skip(1).toList();
      }

      // Deep breath. It gets weird.

      // If the explicit Contact ID was given and the contact actually exists, update.
      if (!Strings.isNullOrEmpty(importEvent.contactId) && existingContactsById.containsKey(importEvent.contactId)) {
        SObject existingContact = existingContactsById.get(importEvent.contactId);

        String accountId = (String) existingContact.getField("AccountId");
        if (account == null) {
          if (!Strings.isNullOrEmpty(accountId)) {
            SObject existingAccount = (SObject) existingContact.getChild("Account");
            account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts);
          }
        }

        // use accountId, not account.id -- if the contact was recently imported by this current process,
        // the contact.account child relationship will not yet exist in the existingContactsById map
        contact = updateBulkImportContact(existingContact, accountId, importEvent, batchUpdateContacts);
      }
      // Similarly, if we have an external ref ID, check that next.
      else if (contactExternalRefKey.isPresent() && existingContactsByExRef.containsKey(importEvent.raw.get(contactExternalRefKey.get()))) {
        SObject existingContact = existingContactsByExRef.get(importEvent.raw.get(contactExternalRefKey.get()));

        String accountId = (String) existingContact.getField("AccountId");
        if (account == null) {
          if (!Strings.isNullOrEmpty(accountId)) {
            SObject existingAccount = (SObject) existingContact.getChild("Account");
            account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts);
          }
        }

        // use accountId, not account.id -- if the contact was recently imported by this current process,
        // the contact.account child relationship will not yet exist in the existingContactsByExRefId map
        contact = updateBulkImportContact(existingContact, accountId, importEvent, batchUpdateContacts);
      }
      // Else if a contact already exists with the given email address, update.
      else if (!Strings.isNullOrEmpty(importEvent.contactEmail) && existingContactsByEmail.containsKey(importEvent.contactEmail)) {
        // If the email address has duplicates, use the oldest.
        SObject existingContact = existingContactsByEmail.get(importEvent.contactEmail).stream()
            .min(Comparator.comparing(c -> ((String) c.getField("CreatedDate")))).get();

        String accountId = (String) existingContact.getField("AccountId");
        if (account == null) {
          if (!Strings.isNullOrEmpty(accountId)) {
            SObject existingAccount = (SObject) existingContact.getChild("Account");
            account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts);
          }
        }

        // use accountId, not account.id -- if the contact was recently imported by this current process,
        // the contact.account child relationship will not yet exist in the existingContactsByEmail map
        contact = updateBulkImportContact(existingContact, accountId, importEvent, batchUpdateContacts);
      }
      // A little weird looking, but if importing with constituent full names, often it could be either a household
      // name (and email would likely exist) or a business name (almost never email). In either case, hard for us to know
      // which to choose, so by default simply upsert the Account and ignore the contact altogether.
      // Similarly, if we only have a last name or an account name, treat it the same.
      else if (
          Strings.isNullOrEmpty(importEvent.contactEmail) && (
              !Strings.isNullOrEmpty(importEvent.contactFullName)
              || !Strings.isNullOrEmpty(importEvent.accountName)
              || (Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(importEvent.contactLastName))
          )
      ) {
        String fullname;
        if (!Strings.isNullOrEmpty(importEvent.contactFullName)) {
          fullname = importEvent.contactFullName;
        } else if (!Strings.isNullOrEmpty(importEvent.accountName)) {
          fullname = importEvent.accountName;
        } else {
          fullname = importEvent.contactLastName;
        }

        SObject existingAccount;
        if (accountExternalRefKey.isPresent() && existingAccountsByExRef.containsKey(importEvent.raw.get(accountExternalRefKey.get()))) {
          // If we have an external ref, try that first.
          existingAccount = existingAccountsByExRef.get(importEvent.raw.get(accountExternalRefKey.get()));
        } else {
          // Otherwise, by name.
          existingAccount = sfdcClient.getAccountsByName(fullname, accountCustomFields).stream().findFirst().orElse(null);
        }

        if (existingAccount == null) {
          account = insertBulkImportAccount(fullname, importEvent, accountExternalRefFieldName, existingAccountsByExRef);
        } else {
          account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts);
        }
      }
      // If we have a first and last name, try searching for an existing contact by name.
      // If 1 match, update. If 0 matches, insert. If 2 or more matches, skip completely out of caution.
      else if (!Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(importEvent.contactLastName)) {
        List<SObject> existingContacts = sfdcClient.getContactsByName(importEvent.contactFirstName, importEvent.contactLastName, importEvent.raw, contactCustomFields);
        log.info("number of contacts for name {} {}: {}", importEvent.contactFirstName, importEvent.contactLastName, existingContacts.size());

        if (existingContacts.size() > 1) {
          // To be safe, let's skip this row for now and deal with it manually...
          log.warn("skipping contact in row {} due to multiple contacts found by-name", i + 2);
        } else if (existingContacts.size() == 1) {
          SObject existingContact = existingContacts.get(0);

          String accountId = (String) existingContact.getField("AccountId");
          if (account == null) {
            if (!Strings.isNullOrEmpty(accountId)) {
              SObject existingAccount = (SObject) existingContact.getChild("Account");
              account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts);
            }
          }

          contact = updateBulkImportContact(existingContact, accountId, importEvent, batchUpdateContacts);
        } else {
          if (accountExternalRefKey.isPresent() && existingAccountsByExRef.containsKey(importEvent.raw.get(accountExternalRefKey.get()))) {
            // If we have an external ref, try that first.
            account = existingAccountsByExRef.get(importEvent.raw.get(accountExternalRefKey.get()));
          }
          if (account == null) {
            account = insertBulkImportAccount(importEvent.contactLastName + " Household", importEvent,
                accountExternalRefFieldName, existingAccountsByExRef);
          }

          contact = insertBulkImportContact(importEvent, account.getId(), batchInsertContacts,
              existingContactsByEmail, contactExternalRefFieldName, existingContactsByExRef, nonBatchMode);
        }
      }
      // Otherwise, abandon all hope and insert, but only if we at least have a lastname or email.
      else if (!Strings.isNullOrEmpty(importEvent.contactEmail) || !Strings.isNullOrEmpty(importEvent.contactLastName)) {
        if (accountExternalRefKey.isPresent() && existingAccountsByExRef.containsKey(importEvent.raw.get(accountExternalRefKey.get()))) {
          // If we have an external ref, try that first.
          account = existingAccountsByExRef.get(importEvent.raw.get(accountExternalRefKey.get()));
        }
        if (account == null) {
          account = insertBulkImportAccount(importEvent.contactLastName + " Household", importEvent,
              accountExternalRefFieldName, existingAccountsByExRef);
        }

        contact = insertBulkImportContact(importEvent, account.getId(), batchInsertContacts,
            existingContactsByEmail, contactExternalRefFieldName, existingContactsByExRef, nonBatchMode);
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

      // Save off the secondary contacts, always batching them (no need to hold onto IDs for later use).
      // But, skip this whole process if for some reason we don't already have an Account to add them into.
      if (!secondaryFirstNames.isEmpty() && account != null && !Strings.isNullOrEmpty(account.getId())) {
        // Retrieve all the contacts on this account. If it previously existed, ensure we're not creating duplicate
        // contacts from the secondary list.
        List<SObject> accountContacts = sfdcClient.getContactsByAccountId(account.getId(), contactCustomFields);

        for (String secondaryFirstName : secondaryFirstNames) {
          importEvent.contactFirstName = secondaryFirstName;
          // null out the email/phone, since they're typically relevant only to the primary
          importEvent.contactEmail = null;
          importEvent.contactMobilePhone = null;
          importEvent.contactHomePhone = null;

          List<SObject> existingContacts = accountContacts.stream()
              .filter(c -> secondaryFirstName.equalsIgnoreCase((String) c.getField("FirstName"))).toList();
          if (existingContacts.size() == 1) {
            SObject existingContact = existingContacts.get(0);

            updateBulkImportContact(existingContact, account.getId(), importEvent, batchUpdateContacts);
          } else if (existingContacts.size() == 0) {
            insertBulkImportContact(importEvent, account.getId(), batchInsertContacts,
                existingContactsByEmail, contactExternalRefFieldName, existingContactsByExRef, nonBatchMode);
          }
        }
      }
    }

    sfdcClient.batchFlush();

    if (rdMode) {
      for (int i = 0; i < importEvents.size(); i++) {
        CrmImportEvent importEvent = importEvents.get(i);

        log.info("import processing recurring donations on row {} of {}", i + 2, importEvents.size() + 1);

        SObject recurringDonation = new SObject("npe03__Recurring_Donation__c");

        // TODO: duplicates setRecurringDonationFields
        if (env.getConfig().salesforce.enhancedRecurringDonations) {
          if (nonBatchContactIds.get(i) != null) {
            recurringDonation.setField("Npe03__Contact__c", nonBatchContactIds.get(i));
          } else {
            recurringDonation.setField("Npe03__Organization__c", nonBatchAccountIds.get(i));
          }
        } else {
          recurringDonation.setField("Npe03__Organization__c", nonBatchAccountIds.get(i));
        }

        if (!Strings.isNullOrEmpty(importEvent.recurringDonationCampaignId)) {
          recurringDonation.setField("Npe03__Recurring_Donation_Campaign__c", importEvent.recurringDonationCampaignId);
        } else if (!Strings.isNullOrEmpty(importEvent.opportunityCampaignName) && campaignNameToId.containsKey(importEvent.opportunityCampaignName)) {
          recurringDonation.setField("Npe03__Recurring_Donation_Campaign__c", campaignNameToId.get(importEvent.opportunityCampaignName));
        }

        if (!Strings.isNullOrEmpty(importEvent.recurringDonationId)) {
          recurringDonation.setId(importEvent.recurringDonationId);
          setBulkImportRecurringDonationFields(recurringDonation, existingRecurringDonationById.get(importEvent.recurringDonationId), importEvent);
          if (!batchUpdateRecurringDonations.contains(importEvent.recurringDonationId)) {
            batchUpdateRecurringDonations.add(importEvent.recurringDonationId);
            sfdcClient.batchUpdate(recurringDonation);
          }
        } else {
          // If the account and contact upserts both failed, avoid creating an orphaned rd.
          if (nonBatchAccountIds.get(i) == null && nonBatchContactIds.get(i) == null) {
            continue;
          }

          setBulkImportRecurringDonationFields(recurringDonation, null, importEvent);
          sfdcClient.batchInsert(recurringDonation);
        }
      }

      sfdcClient.batchFlush();
    }

    if (oppMode) {
      for (int i = 0; i < importEvents.size(); i++) {
        CrmImportEvent importEvent = importEvents.get(i);

        log.info("import processing opportunities on row {} of {}", i + 2, importEvents.size() + 1);

        SObject opportunity = new SObject("Opportunity");

        opportunity.setField("AccountId", nonBatchAccountIds.get(i));

        if (!Strings.isNullOrEmpty(importEvent.opportunityCampaignId)) {
          opportunity.setField("CampaignId", importEvent.opportunityCampaignId);
        } else if (!Strings.isNullOrEmpty(importEvent.opportunityCampaignName) && campaignNameToId.containsKey(importEvent.opportunityCampaignName)) {
          opportunity.setField("CampaignId", campaignNameToId.get(importEvent.opportunityCampaignName));
        }

        if (!Strings.isNullOrEmpty(importEvent.opportunityId)) {
          opportunity.setId(importEvent.opportunityId);
          setBulkImportOpportunityFields(opportunity, existingOpportunitiesById.get(opportunity.getId()), importEvent);
          if (!batchUpdateOpportunities.contains(opportunity.getId())) {
            batchUpdateOpportunities.add(opportunity.getId());
            sfdcClient.batchUpdate(opportunity);
          }
        } else if (opportunityExternalRefKey.isPresent() && existingOpportunitiesByExRefId.containsKey(importEvent.raw.get(opportunityExternalRefKey.get()))) {
          SObject existingOpportunity = existingOpportunitiesByExRefId.get(importEvent.raw.get(opportunityExternalRefKey.get()));

          opportunity.setId(existingOpportunity.getId());
          setBulkImportOpportunityFields(opportunity, existingOpportunity, importEvent);
          if (!batchUpdateOpportunities.contains(opportunity.getId())) {
            batchUpdateOpportunities.add(opportunity.getId());
            sfdcClient.batchUpdate(opportunity);
          }
        } else {
          // If the account and contact upserts both failed, avoid creating an orphaned opp.
          if (nonBatchAccountIds.get(i) == null && nonBatchContactIds.get(i) == null) {
            log.info("skipping opp {} import, due to account/contact failure", i + 2);
            continue;
          }
          if (!importEvent.opportunitySkipDuplicateCheck && importEvent.opportunityAmount != null && Strings.isNullOrEmpty(importEvent.opportunityId)) {
            List<SObject> existingOpportunities = sfdcClient.searchDonations(nonBatchAccountIds.get(i), nonBatchContactIds.get(i),
                importEvent.opportunityDate, importEvent.opportunityAmount.doubleValue(), opportunityCustomFields);
            if (!existingOpportunities.isEmpty()) {
              log.info("skipping opp {} import, due to possible duplicate: {}", i + 2, existingOpportunities.get(0).getId());
              continue;
            }
          }

          setBulkImportOpportunityFields(opportunity, null, importEvent);

          // TODO: Can't currently batch this, since it's an opp insert and we need the opp id to add the OpportunityContactRole.
          //  Rethink this!
//          bulkInsertOpportunities.add(opportunity);
          SaveResult saveResult = sfdcClient.insert(opportunity);

          if (!Strings.isNullOrEmpty(nonBatchContactIds.get(i))) {
            SObject contactRole = new SObject("OpportunityContactRole");
            contactRole.setField("OpportunityId", saveResult.getId());
            contactRole.setField("ContactId", nonBatchContactIds.get(i));
            contactRole.setField("IsPrimary", true);
            // TODO: Not present by default at all orgs.
//            contactRole.setField("Role", "Donor");
            if (!batchInsertOpportunityContactRoles.contains(saveResult.getId())) {
              batchInsertOpportunityContactRoles.add(saveResult.getId());
              sfdcClient.batchInsert(contactRole);
            }
          }
        }
      }

      sfdcClient.batchFlush();
    }

    log.info("bulk import complete");
  }

  protected SObject updateBulkImportAccount(SObject existingAccount, CrmImportEvent importEvent,
      List<String> bulkUpdateAccounts) throws InterruptedException, ConnectionException {
    // TODO: Odd situation. When insertBulkImportContact creates a contact, it's also creating an Account, sets the
    //  AccountId on the Contact and then adds the Contact to existingContactsByEmail so we can reuse it. But when
    //  we encounter the contact again, the code upstream attempts to update the Account. 1) We don't need to, since it
    //  was just created, and 2) we can't set Contact.Account since that child can't exist when the contact is inserted,
    //  and that insert might happen later during batch processing.
    if (existingAccount == null) {
      return null;
    }

    SObject account = new SObject("Account");
    account.setId(existingAccount.getId());

    setBulkImportAccountFields(account, existingAccount, importEvent);

    if (!bulkUpdateAccounts.contains(account.getId())) {
      bulkUpdateAccounts.add(account.getId());
      sfdcClient.batchUpdate(account);
    }

    return account;
  }

  protected SObject insertBulkImportAccount(
      String accountName,
      CrmImportEvent importEvent,
      Optional<String> accountExternalRefFieldName,
      Map<String, SObject> existingAccountsByExRef
  ) throws InterruptedException, ConnectionException {
    SObject account = new SObject("Account");

    setField(account, "Name", accountName);
    setBulkImportAccountFields(account, null, importEvent);

    String accountId = sfdcClient.insert(account).getId();
    account.setId(accountId);

    // Since we hold existingContactsByEmail in memory and don't requery it, add entries as we go to prevent
    // duplicate inserts.
    if (accountExternalRefFieldName.isPresent() && !Strings.isNullOrEmpty((String) account.getField(accountExternalRefFieldName.get()))) {
      existingAccountsByExRef.put((String) account.getField(accountExternalRefFieldName.get()), account);
    }

    return account;
  }

  protected SObject updateBulkImportContact(SObject existingContact, String accountId, CrmImportEvent importEvent,
      List<String> bulkUpdateContacts) throws InterruptedException, ConnectionException {
    SObject contact = new SObject("Contact");
    contact.setId(existingContact.getId());
    contact.setField("AccountId", accountId);

    contact.setField("FirstName", importEvent.contactFirstName);
    contact.setField("LastName", importEvent.contactLastName);

    setBulkImportContactFields(contact, existingContact, importEvent);
    if (!bulkUpdateContacts.contains(existingContact.getId())) {
      bulkUpdateContacts.add(existingContact.getId());
      sfdcClient.batchUpdate(contact);
    }

    return contact;
  }

  protected SObject insertBulkImportContact(
      CrmImportEvent importEvent,
      String accountId,
      List<String> bulkInsertContacts,
      Multimap<String, SObject> existingContactsByEmail,
      Optional<String> contactExternalRefFieldName,
      Map<String, SObject> existingContactsByExRef,
      boolean nonBatchMode
  ) throws InterruptedException, ConnectionException {
    SObject contact = new SObject("Contact");

    // last name is required
    if (Strings.isNullOrEmpty(importEvent.contactLastName)) {
      contact.setField("LastName", "Anonymous");
    } else {
      contact.setField("FirstName", importEvent.contactFirstName);
      contact.setField("LastName", importEvent.contactLastName);
    }

    setBulkImportContactFields(contact, null, importEvent);

    contact.setField("AccountId", accountId);

    if (nonBatchMode) {
      SaveResult saveResult = sfdcClient.insert(contact);
      contact.setId(saveResult.getId());
    } else {
      String key;
      if (!Strings.isNullOrEmpty((String) contact.getField("Email"))) {
        key = (String) contact.getField("Email");
      } else {
        key = contact.getField("FirstName") + " " + contact.getField("LastName");
      }

      if (!bulkInsertContacts.contains(key)) {
        bulkInsertContacts.add(key);
        sfdcClient.batchInsert(contact);
      }
    }

    // Since we hold existingContactsByEmail in memory and don't requery it, add entries as we go to prevent
    // duplicate inserts.
    if (!Strings.isNullOrEmpty((String) contact.getField("Email"))) {
      existingContactsByEmail.put((String) contact.getField("Email"), contact);
    }
    // Ditto for extrefs.
    if (contactExternalRefFieldName.isPresent() && !Strings.isNullOrEmpty((String) contact.getField(contactExternalRefFieldName.get()))) {
      existingContactsByExRef.put((String) contact.getField(contactExternalRefFieldName.get()), contact);
    }

    return contact;
  }

  protected void setBulkImportContactFields(SObject contact, SObject existingContact, CrmImportEvent importEvent)
      throws InterruptedException, ConnectionException {
    if (!Strings.isNullOrEmpty(importEvent.contactRecordTypeId)) {
      contact.setField("RecordTypeId", importEvent.contactRecordTypeId);
    } else if (!Strings.isNullOrEmpty(importEvent.contactRecordTypeName)) {
      // TODO: CACHE THIS!
      SObject salesRecordType = sfdcClient.getRecordTypeByName(importEvent.contactRecordTypeName).get();
      contact.setField("RecordTypeId", salesRecordType.getId());
    }

    contact.setField("MailingStreet", importEvent.contactMailingStreet);
    contact.setField("MailingCity", importEvent.contactMailingCity);
    contact.setField("MailingState", importEvent.contactMailingState);
    contact.setField("MailingPostalCode", importEvent.contactMailingZip);
    contact.setField("MailingCountry", importEvent.contactMailingCountry);
    contact.setField("HomePhone", importEvent.contactHomePhone);
    contact.setField("MobilePhone", importEvent.contactMobilePhone);

    if (!Strings.isNullOrEmpty(importEvent.contactEmail) && !"na".equalsIgnoreCase(importEvent.contactEmail) && !"n/a".equalsIgnoreCase(importEvent.contactEmail)) {
      // Some sources provide comma separated lists. Simply use the first one.
      String email = importEvent.contactEmail.split("[,;\\s]+")[0];
      contact.setField("Email", email);
    }

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

    contact.setField("OwnerId", importEvent.contactOwnerId);

    setBulkImportCustomFields(contact, existingContact, "Contact", importEvent);
  }

  protected void setBulkImportAccountFields(SObject account, SObject existingAccount, CrmImportEvent importEvent)
      throws InterruptedException, ConnectionException {
    // TODO: CACHE THIS!
    if (!Strings.isNullOrEmpty(importEvent.accountRecordTypeId)) {
      account.setField("RecordTypeId", importEvent.accountRecordTypeId);
    } else if (!Strings.isNullOrEmpty(importEvent.accountRecordTypeName)) {
      SObject salesRecordType = sfdcClient.getRecordTypeByName(importEvent.accountRecordTypeName).get();
      account.setField("RecordTypeId", salesRecordType.getId());
    }

    setField(account, "BillingStreet", importEvent.accountBillingStreet);
    setField(account, "BillingCity", importEvent.accountBillingCity);
    setField(account, "BillingState", importEvent.accountBillingState);
    setField(account, "BillingPostalCode", importEvent.accountBillingZip);
    setField(account, "BillingCountry", importEvent.accountBillingCountry);

    account.setField("OwnerId", importEvent.accountOwnerId);

    setBulkImportCustomFields(account, existingAccount, "Account", importEvent);
  }

  protected void setBulkImportRecurringDonationFields(SObject recurringDonation, SObject existingRecurringDonation, CrmImportEvent importEvent)
      throws ConnectionException, InterruptedException {
    if (importEvent.recurringDonationAmount != null) {
      recurringDonation.setField("Npe03__Amount__c", importEvent.recurringDonationAmount.doubleValue());
    }
    recurringDonation.setField("Npe03__Open_Ended_Status__c", importEvent.recurringDonationStatus);
    recurringDonation.setField("Npe03__Schedule_Type__c", "Multiply By");
    CrmRecurringDonation.Frequency frequency = CrmRecurringDonation.Frequency.fromName(importEvent.recurringDonationInterval);
    if (frequency != null) {
      recurringDonation.setField("Npe03__Installment_Period__c", frequency.name());
    }
    recurringDonation.setField("Npe03__Date_Established__c", importEvent.recurringDonationStartDate);
    recurringDonation.setField("Npe03__Next_Payment_Date__c", importEvent.recurringDonationNextPaymentDate);

    if (env.getConfig().salesforce.enhancedRecurringDonations) {
      recurringDonation.setField("npsp__RecurringType__c", "Open");
      // It's a picklist, so it has to be a string and not numeric :(
      recurringDonation.setField("npsp__Day_of_Month__c", importEvent.recurringDonationStartDate.get(Calendar.DAY_OF_MONTH) + "");
    }

    recurringDonation.setField("OwnerId", importEvent.recurringDonationOwnerId);

    setBulkImportCustomFields(recurringDonation, existingRecurringDonation, "Recurring Donation", importEvent);
  }

  protected void setBulkImportOpportunityFields(SObject opportunity, SObject existingOpportunity, CrmImportEvent importEvent)
      throws ConnectionException, InterruptedException {
    if (!Strings.isNullOrEmpty(importEvent.opportunityRecordTypeId)) {
      opportunity.setField("RecordTypeId", importEvent.opportunityRecordTypeId);
    } else if (!Strings.isNullOrEmpty(importEvent.opportunityRecordTypeName)) {
      SObject salesRecordType = sfdcClient.getRecordTypeByName(importEvent.opportunityRecordTypeName).get();
      opportunity.setField("RecordTypeId", salesRecordType.getId());
    }
    if (!Strings.isNullOrEmpty(importEvent.opportunityName)) {
      opportunity.setField("Name", importEvent.opportunityName);
    } else if (existingOpportunity == null || Strings.isNullOrEmpty((String) existingOpportunity.getField("Name"))) {
      opportunity.setField("Name", importEvent.contactFullName() + " Donation");
    }
    opportunity.setField("Description", importEvent.opportunityDescription);
    if (importEvent.opportunityAmount != null) {
      opportunity.setField("Amount", importEvent.opportunityAmount.doubleValue());
    }
    if (!Strings.isNullOrEmpty(importEvent.opportunityStageName)) {
      opportunity.setField("StageName", importEvent.opportunityStageName);
    } else {
      opportunity.setField("StageName", "Closed Won");
    }
    opportunity.setField("CloseDate", importEvent.opportunityDate);

    opportunity.setField("OwnerId", importEvent.opportunityOwnerId);

    setBulkImportCustomFields(opportunity, existingOpportunity, "Opportunity", importEvent);
  }

  protected void setBulkImportCustomFields(SObject sObject, SObject existingSObject, String type, CrmImportEvent importEvent) {
    String prefix = type + " Custom ";

    importEvent.raw.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix) && !Strings.isNullOrEmpty(entry.getValue())).forEach(entry -> {
      String key = entry.getKey().replace(prefix, "");

      if (key.startsWith("Append ")) {
        // appending to a multiselect picklist
        key = key.replace("Append ", "");
        String value = getMultiselectPicklistValue(key, entry.getValue(), existingSObject);
        sObject.setField(key, getCustomBulkValue(value));
      }

      sObject.setField(key, getCustomBulkValue(entry.getValue()));
    });
  }
  
  protected String getMultiselectPicklistValue(String key, String value, SObject existingSObject) {
    if (existingSObject != null) {
      String existingValue = (String) existingSObject.getField(key);
      if (!Strings.isNullOrEmpty(existingValue) && !existingValue.contains(value)) {
        return Strings.isNullOrEmpty(existingValue) ? value : existingValue + ";" + value;
      }
    }
    return value;
  }

  // When querying for existing records, we need to include the custom values the import event cares about. We will need
  // those custom values in order to, for example, append to existing multiselect picklists.
  protected String[] getAccountCustomFields(List<CrmImportEvent> importEvents) {
    return importEvents.stream().flatMap(e -> e.raw.keySet().stream())
        .distinct().filter(k -> k.startsWith("Account Custom "))
        .map(k -> k.replace("Account Custom ", "").replace("Append ", "")).toArray(String[]::new);
  }
  protected String[] getContactCustomFields(List<CrmImportEvent> importEvents) {
    String[] contactFields = importEvents.stream().flatMap(e -> e.raw.keySet().stream())
        .distinct().filter(k -> k.startsWith("Contact Custom "))
        .map(k -> k.replace("Contact Custom ", "").replace("Append ", "")).toArray(String[]::new);
    // We also need the account values!
    String[] accountFields = importEvents.stream().flatMap(e -> e.raw.keySet().stream())
        .distinct().filter(k -> k.startsWith("Account Custom "))
        .map(k -> "Account." + k.replace("Account Custom ", "").replace("Append ", "")).toArray(String[]::new);
    return ArrayUtils.addAll(contactFields, accountFields);
  }
  protected String[] getRecurringDonationCustomFields(List<CrmImportEvent> importEvents) {
    return importEvents.stream().flatMap(e -> e.raw.keySet().stream())
        .distinct().filter(k -> k.startsWith("Recurring Donation Custom "))
        .map(k -> k.replace("Recurring Donation Custom ", "").replace("Append ", "")).toArray(String[]::new);
  }
  protected String[] getOpportunityCustomFields(List<CrmImportEvent> importEvents) {
    return importEvents.stream().flatMap(e -> e.raw.keySet().stream())
        .distinct().filter(k -> k.startsWith("Opportunity Custom "))
        .map(k -> k.replace("Opportunity Custom ", "").replace("Append ", "")).toArray(String[]::new);
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
    } else if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value)) {
      return true;
    } else if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value)) {
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

  protected Optional<SObject> getCampaignOrDefault(CrmRecord crmRecord) throws ConnectionException, InterruptedException {
    Optional<SObject> campaign = Optional.empty();

    String campaignIdOrName = crmRecord.getMetadataValue(env.getConfig().metadataKeys.campaign);
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
    CrmAddress billingAddress = new CrmAddress(
        (String) sObject.getField("BillingStreet"),
        (String) sObject.getField("BillingCity"),
        (String) sObject.getField("BillingState"),
        (String) sObject.getField("BillingPostalCode"),
        (String) sObject.getField("BillingCountry")
    );
    CrmAddress shippingAddress = new CrmAddress(
        (String) sObject.getField("ShippingStreet"),
        (String) sObject.getField("ShippingCity"),
        (String) sObject.getField("ShippingState"),
        (String) sObject.getField("ShippingPostalCode"),
        (String) sObject.getField("ShippingCountry")
    );

    EnvironmentConfig.AccountType type = EnvironmentConfig.AccountType.HOUSEHOLD;
    if (sObject.getChild("RecordType") != null) {
      String recordTypeName = (String) sObject.getChild("RecordType").getField("Name");
      recordTypeName = recordTypeName == null ? "" : recordTypeName.toLowerCase(Locale.ROOT);
      // TODO: Customize record type names through env.json?
      if (recordTypeName.contains("business") || recordTypeName.contains("church") || recordTypeName.contains("org") || recordTypeName.contains("group"))  {
        type = EnvironmentConfig.AccountType.ORGANIZATION;
      }
    }

    return new CrmAccount(
        sObject.getId(),
        billingAddress,
        shippingAddress,
        (String) sObject.getField("Name"),
        type,
        sObject,
        "https://" + env.getConfig().salesforce.url + "/lightning/r/Account/" + sObject.getId() + "/view"
    );
  }

  protected Optional<CrmAccount> toCrmAccount(Optional<SObject> sObject) {
    return sObject.map(this::toCrmAccount);
  }

  protected List<CrmAccount> toCrmAccount(List<SObject> sObjects) {
    return sObjects.stream().map(this::toCrmAccount).collect(Collectors.toList());
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
        new CrmAccount((String) sObject.getField("AccountId")),
        crmAddress,
        (String) sObject.getField("Email"),
        emailGroups,
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.emailOptIn),
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.emailOptOut),
        firstCloseDate,
        (String) sObject.getField("FirstName"),
        homePhone,
        lastCloseDate,
        (String) sObject.getField("LastName"),
        getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.contactLanguage),
        (String) sObject.getField("MobilePhone"),
        numberOfClosedOpps,
        (String) sObject.getField("Owner.Id"),
        ownerName,
        preferredPhone,
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.smsOptIn),
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.smsOptOut),
        totalOppAmount,
        (String) sObject.getField("npe01__WorkPhone__c"),
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
    ZonedDateTime closeDate = Utils.getZonedDateFromDateString((String) sObject.getField("CloseDate"));

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
        account,
        contact,
        new CrmRecurringDonation(),
        amount,
        null, // String customerId,
        null, // ZonedDateTime depositDate,
        null, // String depositId,
        null, // String depositTransactionId,
        paymentGatewayName,
        null, // EnvironmentConfig.PaymentEventType paymentEventType,
        null, // String paymentMethod,
        null, // String refundId,
        null, // ZonedDateTime refundDate,
        status,
        false, // boolean transactionCurrencyConverted,
        null, // Double transactionExchangeRate,
        null, // Double transactionFeeInDollars,
        paymentGatewayTransactionId,
        null, // Double transactionNetAmountInDollars,
        null, // Double transactionOriginalAmountInDollars,
        null, // String transactionOriginalCurrency,
        null, // String transactionSecondaryId,
        null, // String transactionUrl,
        (String) sObject.getField("CampaignId"),
        closeDate,
        (String) sObject.getField("Description"),
        (String) sObject.getField("Name"),
        (String) sObject.getField("OwnerId"),
        (String) sObject.getField("RecordTypeId"),
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
        account,
        contact,
        active,
        amount,
        customerId,
        null, // String description,
        donationName,
        frequency,
        paymentGatewayName,
        null, // String subscriptionCurrency,
        subscriptionId,
        null, // ZonedDateTime subscriptionNextDate,
        null, // ZonedDateTime subscriptionStartDate,
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
