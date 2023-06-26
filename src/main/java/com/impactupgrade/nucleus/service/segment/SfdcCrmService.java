/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.LoadingCache;
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
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.model.CrmRecord;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.util.CacheUtil;
import com.impactupgrade.nucleus.util.Utils;
import com.sforce.soap.metadata.FieldType;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SfdcCrmService implements CrmService {

  private static final Logger log = LogManager.getLogger(SfdcCrmService.class);

  protected Environment env;
  protected SfdcClient sfdcClient;
  protected SfdcMetadataClient sfdcMetadataClient;

  // Globally scoped, since these are expensive calls that we want to minimize overall.
  protected static Cache<String, Map<String, String>> objectFieldsCache = CacheUtil.buildManualCache();

  // Simply scoped to the service/environment/request, since it's more of a per-flow optimization (primarily for Bulk Upsert).
  protected LoadingCache<String, String> recordTypeNameToIdCache;

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

    recordTypeNameToIdCache = CacheUtil.buildLoadingCache(recordTypeName -> {
      try {
        return sfdcClient.getRecordTypeByName(recordTypeName).map(SObject::getId).orElse(null);
      } catch (Exception e) {
        log.error("unable to fetch record type {}", recordTypeName, e);
        return null;
      }
    });
  }

  @Override
  public Optional<CrmAccount> getAccountById(String id, String... extraFields) throws Exception {
    return toCrmAccount(sfdcClient.getAccountById(id, extraFields));
  }

  @Override
  public List<CrmAccount> getAccountsByIds(List<String> ids, String... extraFields) throws Exception {
    return toCrmAccount(sfdcClient.getAccountsByIds(ids, extraFields));
  }

  @Override
  public Optional<CrmAccount> getAccountByCustomerId(String customerId) throws Exception {
    return toCrmAccount(sfdcClient.getAccountByCustomerId(customerId));
  }

  @Override
  public Optional<CrmContact> getContactById(String id, String... extraFields) throws Exception {
    return toCrmContact(sfdcClient.getContactById(id, extraFields));
  }

  @Override
  public Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception {
    return toCrmContact(sfdcClient.getFilteredContactById(id, filter));
  }

  @Override
  public List<CrmContact> getContactsByIds(List<String> ids, String... extraFields) throws Exception {
    return toCrmContact(sfdcClient.getContactsByIds(ids, extraFields));
  }

  @Override
  public List<CrmContact> getContactsByEmails(List<String> emails, String... extraFields) throws Exception {
    return toCrmContact(sfdcClient.getContactsByEmails(emails, extraFields));
  }

  @Override
  // currentPageToken assumed to be the offset index
  public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch, String... extraFields) throws InterruptedException, ConnectionException {
    return toCrmContact(sfdcClient.searchContacts(contactSearch, extraFields));
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
  public List<CrmCustomField> insertCustomFields(List<CrmCustomField> crmCustomFields) {
    try {
      // Layout -> Section -> Fields
      Map<String, Map<String, List<String>>> layoutFields = new HashMap<>();

      // Create custom fields
      for (CrmCustomField crmCustomField: crmCustomFields) {
        sfdcMetadataClient.createCustomField(
            crmCustomField.objectName,
            crmCustomField.name,
            crmCustomField.label,
            FieldType.valueOf(crmCustomField.type),
            crmCustomField.length,
            crmCustomField.precision,
            crmCustomField.scale,
            crmCustomField.values,
            null
        );

        if (!layoutFields.containsKey(crmCustomField.layoutName)) {
          layoutFields.put(crmCustomField.layoutName, new HashMap<>());
        }
        if (!layoutFields.get(crmCustomField.layoutName).containsKey(crmCustomField.groupName)) {
          layoutFields.get(crmCustomField.layoutName).put(crmCustomField.groupName, new ArrayList<>());
        }
        layoutFields.get(crmCustomField.layoutName).get(crmCustomField.groupName).add(crmCustomField.name);
      }

      // add custom fields to layout
      for (Map.Entry<String, Map<String, List<String>>> layout : layoutFields.entrySet()) {
        String layoutName = layout.getKey();
        for (Map.Entry<String, List<String>> section : layout.getValue().entrySet()) {
          String sectionLabel = section.getKey();
          List<String> fieldNames = section.getValue();

          sfdcMetadataClient.addFields(layoutName, sectionLabel, fieldNames);
        }
      }

    } catch (Exception e) {
      log.error("failed to create custom fields", e);
    }
    return crmCustomFields;
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
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id, String... extraFields) throws Exception {
    return toCrmRecurringDonation(sfdcClient.getRecurringDonationById(id, extraFields));
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
  public void batchUpdate(CrmContact crmContact) throws Exception {
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
    opportunity.setField("ContactId", crmDonation.contact.id);
    opportunity.setField("Npe03__Recurring_Donation__c", recurringDonationId);

    setOpportunityFields(opportunity, campaign, crmDonation);

    return sfdcClient.insert(opportunity).getId();
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
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.fund, crmDonation.getRawData(env.getConfig().metadataKeys.fund));
    }

    if (crmDonation.transactionType != null) {
      String recordTypeId = env.getConfig().salesforce.transactionTypeToRecordTypeIds.get(crmDonation.transactionType);
      opportunity.setField("RecordTypeId", recordTypeId);
    } else {
      String recordTypeId = crmDonation.getRawData(env.getConfig().metadataKeys.recordType);
      opportunity.setField("RecordTypeId", recordTypeId);
    }

    // check to see if this was a failed payment attempt and set the StageName accordingly
    if (crmDonation.status == CrmDonation.Status.SUCCESSFUL) {
      opportunity.setField("StageName", "Closed Won");
    } else {
      opportunity.setField("StageName", "Failed Attempt");
      // add failure reason
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayFailureReason)) {
        opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayFailureReason, crmDonation.failureReason);
      }
    }

    opportunity.setField("Amount", crmDonation.amount);
    opportunity.setField("CampaignId", campaign.map(SObject::getId).orElse(null));
    opportunity.setField("CloseDate", Utils.toCalendar(crmDonation.closeDate));
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
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDate, Utils.toCalendar(crmDonation.refundDate));
    }
    // TODO: LJI/TER/DR specific? They all have it, but I can't remember if we explicitly added it.
    opportunity.setField("StageName", "Refunded");
  }

  @Override
  public void insertDonationDeposit(List<CrmDonation> crmDonations) throws Exception {
    // Group donation by id to check if we have more than 1 update for each crm donation id
    Map<String, List<CrmDonation>> crmDonationsById = crmDonations.stream()
        .collect(Collectors.groupingBy(crmDonation -> crmDonation.id));

    List<CrmDonation> batchUpdates = new ArrayList<>();
    List<CrmDonation> singleUpdates = new ArrayList<>();

    // One update for id -> can be processed in a batch
    // More than one update for id -> should be processed one by one
    crmDonationsById.entrySet().forEach(e -> {
      List<CrmDonation> donations = e.getValue();
      if (donations.size() > 1) {
        singleUpdates.addAll(donations);
      } else {
        batchUpdates.addAll(donations);
      }
    });

    for (CrmDonation crmDonation : batchUpdates) {
      SObject opportunityUpdate = new SObject("Opportunity");
      opportunityUpdate.setId(crmDonation.id);
      setDonationDepositFields((SObject) crmDonation.rawObject, opportunityUpdate, crmDonation);
      sfdcClient.batchUpdate(opportunityUpdate);
    }
    sfdcClient.batchFlush();

    // Note that the opportunityUpdates map is in place for situations where a charge and its refund are in the same
    // deposit. In that situation, the Donation CRM ID would wind up in the batch update twice, which causes errors
    // downstream. Instead, ensure we're setting the fields for both situations, but on a single object.
    Map<String, SObject> opportunityUpdates = new HashMap<>();

    // closeDate isn't present for refunds, etc.
    Collections.sort(singleUpdates, Comparator.comparing(crmDonation -> crmDonation.closeDate == null ? 0 : crmDonation.closeDate.toEpochSecond()));
    for (CrmDonation crmDonation : singleUpdates) {
      SObject opportunityUpdate;
      if (opportunityUpdates.containsKey(crmDonation.id)) {
        opportunityUpdate = opportunityUpdates.get(crmDonation.id);
      } else {
        opportunityUpdate = new SObject("Opportunity");
        opportunityUpdate.setId(crmDonation.id);
        opportunityUpdates.put(crmDonation.id, opportunityUpdate);
      }
      setDonationDepositFields((SObject) crmDonation.rawObject, opportunityUpdate, crmDonation);

      sfdcClient.update(opportunityUpdate);
    }
  }

  protected void setDonationDepositFields(SObject existingOpportunity, SObject opportunityUpdate,
      CrmDonation crmDonation) throws InterruptedException {
    // If the payment gateway event has a refund ID, this item in the payout was a refund. Mark it as such!
    if (!Strings.isNullOrEmpty(crmDonation.refundId)) {
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundId)) {
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDepositDate, Utils.toCalendar(crmDonation.depositDate));
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDepositId, crmDonation.depositId);
      }
    } else {
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositId)) {
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositDate, Utils.toCalendar(crmDonation.depositDate));
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
    recurringDonation.setField("Npe03__Date_Established__c", Utils.toCalendar(crmRecurringDonation.subscriptionStartDate));
    recurringDonation.setField("Npe03__Next_Payment_Date__c", Utils.toCalendar(crmRecurringDonation.subscriptionNextDate));
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
  public List<CrmUser> getUsers() throws Exception {
    return Collections.emptyList();
  }

  @Override
  public Map<String, String> getContactLists() throws Exception {
    Map<String, String> lists = new HashMap<>();
    List<SObject> listRecords = new ArrayList<>();
    listRecords.addAll(sfdcClient.getCampaigns());
    listRecords.addAll(sfdcClient.getReports());

    String filter = ".*(?i:npsp|sample|nonprofit|health|dashboard).*";
    Pattern pattern = Pattern.compile(filter);

    for(SObject list: listRecords){
      if (!pattern.matcher(list.getField("Name").toString()).find()) {
        lists.put(list.getField("Name").toString(), list.getField("Id").toString());
      }
    }
    return lists;
  }

  @Override
  public Map<String, String> getFieldOptions(String object) throws Exception {
    // include the apiKey for multitenant arenas, like nucleus-core
    String cacheKey = env.getConfig().apiKey + "_" + object;
    return objectFieldsCache.get(cacheKey, () -> {
      try {
        return sfdcMetadataClient.getObjectFields(object);
      } catch (Exception e) {
        log.error("unable to fetch fields from {}", object, e);
        return null;
      }
    });
  }

  @Override
  public double getDonationsTotal(String filter) throws Exception {
    // TODO
    return 0.0;
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

    String campaignIdOrName = crmRecord.getRawData(env.getConfig().metadataKeys.campaign);
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
    return new CrmCampaign(
        sObject.getId(),
        (String) sObject.getField("Name")
    );
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

    String recordTypeName = null;
    EnvironmentConfig.AccountType type = EnvironmentConfig.AccountType.HOUSEHOLD;
    if (sObject.getChild("RecordType") != null) {
      recordTypeName = (String) sObject.getChild("RecordType").getField("Name");
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
        recordTypeName,
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
    Double totalDonationAmount = null;
    Double totalDonationAmountYtd = null;
    Double largestDonationAmount = null;
    Integer numberOfDonations = null;
    Integer numberOfDonationsYtd = null;
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

      totalDonationAmount = Double.valueOf((String) sObject.getChild("Account").getField("npo02__TotalOppAmount__c"));
      totalDonationAmountYtd = Double.valueOf((String) sObject.getChild("Account").getField("npo02__OppAmountThisYear__c"));
      if (sObject.getChild("Account").getField("npo02__LargestAmount__c") != null) {
        largestDonationAmount = Double.valueOf((String) sObject.getChild("Account").getField("npo02__LargestAmount__c"));
      }
      numberOfDonations = Double.valueOf((String) sObject.getChild("Account").getField("npo02__NumberOfClosedOpps__c")).intValue();
      numberOfDonationsYtd = Double.valueOf((String) sObject.getChild("Account").getField("npo02__OppsClosedThisYear__c")).intValue();
      try {
        firstCloseDate = Utils.getCalendarFromDateTimeString((String) sObject.getChild("Account").getField("npo02__FirstCloseDate__c"));
        lastCloseDate = Utils.getCalendarFromDateTimeString((String) sObject.getChild("Account").getField("npo02__LastCloseDate__c"));
      } catch (Exception e) {
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

    CrmAccount account = new CrmAccount();
    account.id = (String) sObject.getField("AccountId");
    if (sObject.getChild("Account") != null && sObject.getChild("Account").hasChildren())
      account = toCrmAccount((SObject) sObject.getChild("Account"));

    return new CrmContact(
        sObject.getId(),
        account,
        crmAddress,
        (String) sObject.getField("Email"),
        emailGroups,
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.emailOptIn),
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.emailOptOut),
        firstCloseDate,
        (String) sObject.getField("FirstName"),
        homePhone,
        largestDonationAmount,
        lastCloseDate,
        (String) sObject.getField("LastName"),
        getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.contactLanguage),
        (String) sObject.getField("MobilePhone"),
        numberOfDonations,
        numberOfDonationsYtd,
        (String) sObject.getField("Owner.Id"),
        ownerName,
        preferredPhone,
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.smsOptIn),
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.smsOptOut),
        totalDonationAmount,
        totalDonationAmountYtd,
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
    ZonedDateTime closeDate = Utils.getZonedDateTimeFromDateTimeString((String) sObject.getField("CloseDate"));

    // TODO: yuck -- allow subclasses to more easily define custom mappers?
    Object statusNameO = sObject.getField("StageName");
    String statusName = statusNameO == null ? "" : statusNameO.toString();
    CrmDonation.Status status;
    String paymentGatewayFailureReason = null;
    if ("Posted".equalsIgnoreCase(statusName) || "Closed Won".equalsIgnoreCase(statusName)) {
      status = CrmDonation.Status.SUCCESSFUL;
    } else if (statusName.contains("fail") || statusName.contains("Fail")) {
      status = CrmDonation.Status.FAILED;
      paymentGatewayFailureReason = getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.paymentGatewayFailureReason);
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
        paymentGatewayFailureReason,
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
