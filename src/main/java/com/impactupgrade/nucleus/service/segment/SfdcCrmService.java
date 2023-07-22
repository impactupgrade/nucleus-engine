/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.google.common.cache.Cache;
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
import com.impactupgrade.nucleus.util.CacheUtil;
import com.impactupgrade.nucleus.util.Utils;
import com.sforce.soap.metadata.FieldType;
import com.sforce.soap.partner.SaveResult;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.impactupgrade.nucleus.util.Utils.getZonedDateFromDateString;

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
  public Optional<CrmContact> getFilteredContactByEmail(String email, String filter) throws Exception {
    return toCrmContact(sfdcClient.getFilteredContactByEmail(email, filter));
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
      String[] nameParts = name.get().trim().split("\\s+");

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
    if (crmAccount.recordType != null && accountTypeToRecordTypeIds.containsKey(crmAccount.recordType)) {
      account.setField("RecordTypeId", accountTypeToRecordTypeIds.get(crmAccount.recordType));
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
    addContactToCampaign(crmContact.id, campaignId, false);
  }

  protected void addContactToCampaign(String contactId, String campaignId, boolean batch) throws Exception {
    SObject campaignMember = new SObject("CampaignMember");
    campaignMember.setField("ContactId", contactId);
    campaignMember.setField("CampaignId", campaignId);
    if (batch) {
      sfdcClient.batchInsert(campaignMember);
    } else {
      sfdcClient.insert(campaignMember);
    }
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
    if (crmContact.emailBounced != null && crmContact.emailBounced) {
      setField(contact, env.getConfig().salesforce.fieldDefinitions.emailBounced, true);
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

    // TODO: Avoiding setting the mailing address of a Contact, instead allowing the Account to handle it. But should we?
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
      // add failure reason
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayFailureReason)) {
        opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayFailureReason, crmDonation.failureReason);
      }
    }

    opportunity.setField("Amount", crmDonation.amount);
    opportunity.setField("CampaignId", campaign.map(SObject::getId).orElse(null));
    opportunity.setField("CloseDate", Utils.toCalendar(crmDonation.closeDate, env.getConfig().timezoneId));
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
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDate, Utils.toCalendar(crmDonation.refundDate, env.getConfig().timezoneId));
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
      setDonationDepositFields((SObject) crmDonation.crmRawObject, opportunityUpdate, crmDonation);
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
      setDonationDepositFields((SObject) crmDonation.crmRawObject, opportunityUpdate, crmDonation);

      sfdcClient.update(opportunityUpdate);
    }
  }

  protected void setDonationDepositFields(SObject existingOpportunity, SObject opportunityUpdate,
      CrmDonation crmDonation) throws InterruptedException {
    // If the payment gateway event has a refund ID, this item in the payout was a refund. Mark it as such!
    if (!Strings.isNullOrEmpty(crmDonation.refundId)) {
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundId)) {
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDepositDate, Utils.toCalendar(crmDonation.depositDate, env.getConfig().timezoneId));
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDepositId, crmDonation.depositId);
      }
    } else {
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositId)) {
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositDate, Utils.toCalendar(crmDonation.depositDate, env.getConfig().timezoneId));
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
    recurringDonation.setField("Npe03__Date_Established__c", Utils.toCalendar(crmRecurringDonation.subscriptionStartDate, env.getConfig().timezoneId));
    recurringDonation.setField("Npe03__Next_Payment_Date__c", Utils.toCalendar(crmRecurringDonation.subscriptionNextDate, env.getConfig().timezoneId));
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
  public String insertCampaign(CrmCampaign crmCampaign) throws Exception {
    SObject campaign = new SObject("Campaign");
    campaign.setField("Name", crmCampaign.name());
    return sfdcClient.insert(campaign).getId();
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
  public Map<String, List<String>> getEmailCampaignsByContactIds(List<String> contactIds) throws Exception {
    Map<String, List<String>> contactCampaigns = new HashMap<>();
    List<SObject> campaignMembers = sfdcClient.getEmailCampaignsByContactIds(contactIds);
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

  @Override
  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
    // MODES:
    // - Core Records: Accounts + Contacts + Recurring Donations + Opportunities
    // - Campaigns
    // - TODO: Other types of records?

    if (importEvents.isEmpty()) {
      log.warn("no importEvents to import; exiting...");
      return;
    }

    boolean campaignMode = importEvents.stream().flatMap(e -> e.raw.entrySet().stream())
        .anyMatch(entry -> entry.getKey().startsWith("Campaign") && !Strings.isNullOrEmpty(entry.getValue()));
    if (campaignMode) {
      processBulkImportCampaignRecords(importEvents);
      return;
    }

    processBulkImportCoreRecords(importEvents);
  }

  // TODO: Much of this bulk import code needs genericized and pulled upstream!
  protected void processBulkImportCoreRecords(List<CrmImportEvent> importEvents) throws Exception {
    // This entire method uses bulk queries and bulk inserts wherever possible!
    // We make multiple passes, focusing on one object at a time in order to use the bulk API.

    String[] accountCustomFields = importEvents.stream().flatMap(e -> e.getAccountCustomFieldNames().stream()).distinct().toArray(String[]::new);
    String[] contactCustomFields = importEvents.stream().flatMap(e -> e.getContactCustomFieldNames().stream()).distinct().toArray(String[]::new);
    String[] recurringDonationCustomFields = importEvents.stream().flatMap(e -> e.getRecurringDonationCustomFieldNames().stream()).distinct().toArray(String[]::new);
    String[] opportunityCustomFields = importEvents.stream().flatMap(e -> e.getOpportunityCustomFieldNames().stream()).distinct().toArray(String[]::new);

    List<String> accountIds = importEvents.stream().map(e -> e.account.id)
        .filter(accountId -> !Strings.isNullOrEmpty(accountId)).distinct().toList();
    Map<String, SObject> existingAccountsById = new HashMap<>();
    if (accountIds.size() > 0) {
      sfdcClient.getAccountsByIds(accountIds, accountCustomFields).forEach(c -> {
        // cache both the 15 and 18 char versions, so the sheet can use either
        existingAccountsById.put(c.getId(), c);
        existingAccountsById.put(c.getId().substring(0, 15), c);
      });
    }

    Optional<String> accountExtRefKey = importEvents.get(0).raw.keySet().stream()
        .filter(k -> k.startsWith("Account ExtRef ")).distinct().findFirst();
    Optional<String> accountExtRefFieldName = accountExtRefKey.map(k -> k.replace("Account ExtRef ", ""));
    Map<String, SObject> existingAccountsByExtRef = new HashMap<>();
    if (accountExtRefKey.isPresent()) {
      List<String> accountExtRefIds = importEvents.stream().map(e -> e.raw.get(accountExtRefKey.get())).filter(s -> !Strings.isNullOrEmpty(s)).toList();
      if (accountExtRefIds.size() > 0) {
        // The imported sheet data comes in as all strings, so use toString here too to convert numberic extref values.
        sfdcClient.getAccountsByUniqueField(accountExtRefFieldName.get(), accountExtRefIds, accountCustomFields)
            .forEach(c -> existingAccountsByExtRef.put(c.getField(accountExtRefFieldName.get()).toString(), c));
      }
    }

    List<String> accountNames = importEvents.stream().map(e -> e.account.name)
        .filter(name -> !Strings.isNullOrEmpty(name)).distinct().collect(Collectors.toList());
    importEvents.stream().flatMap(e -> e.contactOrganizations.stream()).map(o -> o.name)
        .filter(name -> !Strings.isNullOrEmpty(name)).distinct().forEach(accountNames::add);
    Multimap<String, SObject> existingAccountsByName = ArrayListMultimap.create();
    if (accountNames.size() > 0) {
      // Normalize the case!
      sfdcClient.getAccountsByNames(accountNames, accountCustomFields)
          .forEach(c -> existingAccountsByName.put(c.getField("Name").toString().toLowerCase(Locale.ROOT), c));
    }

    List<String> contactIds = importEvents.stream().map(e -> e.contactId)
        .filter(contactId -> !Strings.isNullOrEmpty(contactId)).distinct().toList();
    Map<String, SObject> existingContactsById = new HashMap<>();
    if (contactIds.size() > 0) {
      sfdcClient.getContactsByIds(contactIds, contactCustomFields).forEach(c -> {
        // cache both the 15 and 18 char versions, so the sheet can use either
        existingContactsById.put(c.getId(), c);
        existingContactsById.put(c.getId().substring(0, 15), c);
      });
    }

    List<String> contactEmails = importEvents.stream().map(e -> e.contactEmail)
        .filter(email -> !Strings.isNullOrEmpty(email)).distinct().toList();
    Multimap<String, SObject> existingContactsByEmail = ArrayListMultimap.create();
    if (contactEmails.size() > 0) {
      // Normalize the case!
      sfdcClient.getContactsByEmails(contactEmails, contactCustomFields)
          .forEach(c -> existingContactsByEmail.put(c.getField("Email").toString().toLowerCase(Locale.ROOT), c));
    }

    List<String> contactNames = importEvents.stream().map(e -> e.contactFirstName + " " + e.contactLastName)
        .filter(name -> !Strings.isNullOrEmpty(name)).distinct().toList();
    Multimap<String, SObject> existingContactsByName = ArrayListMultimap.create();
    if (contactNames.size() > 0) {
      // Normalize the case!
      sfdcClient.getContactsByNames(contactNames, contactCustomFields)
          .forEach(c -> existingContactsByName.put(c.getField("Name").toString().toLowerCase(Locale.ROOT), c));
    }

    Optional<String> contactExtRefKey = importEvents.get(0).raw.keySet().stream()
        .filter(k -> k.startsWith("Contact ExtRef ")).findFirst();
    Optional<String> contactExtRefFieldName = contactExtRefKey.map(k -> k.replace("Contact ExtRef ", ""));
    Map<String, SObject> existingContactsByExtRef = new HashMap<>();
    if (contactExtRefKey.isPresent()) {
      List<String> contactExtRefIds = importEvents.stream().map(e -> e.raw.get(contactExtRefKey.get()))
          .filter(s -> !Strings.isNullOrEmpty(s)).distinct().toList();
      if (contactExtRefIds.size() > 0) {
        // The imported sheet data comes in as all strings, so use toString here too to convert numberic extref values.
        sfdcClient.getContactsByUniqueField(contactExtRefFieldName.get(), contactExtRefIds, contactCustomFields)
            .forEach(c -> existingContactsByExtRef.put(c.getField(contactExtRefFieldName.get()).toString(), c));
      }
    }

    List<String> campaignNames = importEvents.stream()
        .flatMap(e -> Stream.concat(e.contactCampaignNames.stream(), Stream.of(e.opportunityCampaignName)))
        .filter(name -> !Strings.isNullOrEmpty(name)).distinct().collect(Collectors.toList());
    Map<String, String> campaignNameToId = Collections.emptyMap();
    if (campaignNames.size() > 0) {
      // Normalize the case!
      campaignNameToId = sfdcClient.getCampaignsByNames(campaignNames).stream()
          .collect(Collectors.toMap(c -> c.getField("Name").toString().toLowerCase(Locale.ROOT), SObject::getId));
    }

    List<String> recurringDonationIds = importEvents.stream().map(e -> e.recurringDonationId)
        .filter(recurringDonationId -> !Strings.isNullOrEmpty(recurringDonationId)).distinct().toList();
    Map<String, SObject> existingRecurringDonationById = new HashMap<>();
    if (recurringDonationIds.size() > 0) {
      sfdcClient.getRecurringDonationsByIds(recurringDonationIds, recurringDonationCustomFields).forEach(c -> {
        // cache both the 15 and 18 char versions, so the sheet can use either
        existingRecurringDonationById.put(c.getId(), c);
        existingRecurringDonationById.put(c.getId().substring(0, 15), c);
      });
    }

    List<String> opportunityIds = importEvents.stream().map(e -> e.opportunityId)
        .filter(opportunityId -> !Strings.isNullOrEmpty(opportunityId)).distinct().toList();
    Map<String, SObject> existingOpportunitiesById = new HashMap<>();
    if (opportunityIds.size() > 0) {
      sfdcClient.getDonationsByIds(opportunityIds, opportunityCustomFields).forEach(c -> {
        // cache both the 15 and 18 char versions, so the sheet can use either
        existingOpportunitiesById.put(c.getId(), c);
        existingOpportunitiesById.put(c.getId().substring(0, 15), c);
      });
    }

    Optional<String> opportunityExtRefKey = importEvents.get(0).raw.keySet().stream()
        .filter(k -> k.startsWith("Opportunity ExtRef ")).findFirst();
    Map<String, SObject> existingOpportunitiesByExtRefId = new HashMap<>();
    if (opportunityExtRefKey.isPresent()) {
      List<String> opportunityExtRefIds = importEvents.stream().map(e -> e.raw.get(opportunityExtRefKey.get()))
          .filter(s -> !Strings.isNullOrEmpty(s)).distinct().toList();
      if (opportunityExtRefIds.size() > 0) {
        String fieldName = opportunityExtRefKey.get().replace("Opportunity ExtRef ", "");
        sfdcClient.getDonationsByUniqueField(fieldName, opportunityExtRefIds, opportunityCustomFields)
            .forEach(c -> existingOpportunitiesByExtRefId.put((String) c.getField(fieldName), c));
      }
    }

    Set<String> seenRelationships = new HashSet<>();
    if (env.getConfig().salesforce.npsp) {
      List<SObject> relationships = sfdcClient.queryListAutoPaged("SELECT npe5__Contact__c, npe5__Organization__c FROM npe5__Affiliation__c WHERE npe5__Contact__c!='' AND npe5__Organization__c!=''");
      for (SObject relationship : relationships) {
        String from = (String) relationship.getField("npe5__Contact__c");
        String to = (String) relationship.getField("npe5__Organization__c");
        seenRelationships.add(from + "::" + to);
        seenRelationships.add(to + "::" + from);
      }
    } else {
      List<SObject> relationships = sfdcClient.queryListAutoPaged("SELECT ContactId, AccountId FROM AccountContactRelation WHERE ContactId!='' AND AccountId!=''");
      for (SObject relationship : relationships) {
        String from = (String) relationship.getField("ContactId");
        String to = (String) relationship.getField("AccountId");
        seenRelationships.add(from + "::" + to);
        seenRelationships.add(to + "::" + from);
      }
    }

    // we use by-id maps for batch inserts/updates, since the sheet can contain duplicate contacts/accounts
    // (especially when importing opportunities)
    List<String> batchUpdateAccounts = new ArrayList<>();
    List<String> batchInsertContacts = new ArrayList<>();
    List<String> batchUpdateContacts = new ArrayList<>();
    List<String> batchUpdateOpportunities = new ArrayList<>();
    List<String> batchUpdateRecurringDonations = new ArrayList<>();

    boolean accountMode = importEvents.stream().flatMap(e -> e.raw.entrySet().stream())
        .anyMatch(entry -> entry.getKey().startsWith("Account") && !Strings.isNullOrEmpty(entry.getValue()));
    boolean contactMode = importEvents.stream().flatMap(e -> e.raw.entrySet().stream())
        .anyMatch(entry -> entry.getKey().startsWith("Contact") && !Strings.isNullOrEmpty(entry.getValue()));

    // For the following contexts, we unfortunately can't use batch inserts/updates of accounts/contacts.
    // Opportunity/RD inserts, 1..n Organization affiliations, Campaign updates
    // TODO: We probably *can*, but the code will be rather complex to manage the variety of batch actions paired with CSV rows.
    boolean oppMode = importEvents.stream().anyMatch(e -> e.opportunityDate != null || e.opportunityId != null);
    boolean rdMode = importEvents.stream().anyMatch(e -> e.recurringDonationAmount != null || e.recurringDonationId != null);
    boolean orgMode = importEvents.stream().anyMatch(e -> e.contactOrganizations.size() > 0);
    boolean campaignMode = importEvents.stream().anyMatch(e -> !e.contactCampaignIds.isEmpty() || !e.contactCampaignNames.isEmpty());
    boolean nonBatchMode = oppMode || rdMode || orgMode || campaignMode;

    List<String> nonBatchAccountIds = new ArrayList<>();
    List<String> nonBatchContactIds = new ArrayList<>();
    while(nonBatchAccountIds.size() < importEvents.size()) nonBatchAccountIds.add("");
    while(nonBatchContactIds.size() < importEvents.size()) nonBatchContactIds.add("");

    // IMPORTANT IMPORTANT IMPORTANT
    // Process importEvents in two passes, first processing the ones that will result in an update to a contact, then
    // later process the contacts that will result in an insert. Using CLHS as an example:
    // Spouse A is in SFDC due to the Raiser's Edge migration.
    // Spouse B is missing.
    // Spouse A and Spouse B are both included in the FACTS migration.
    // We need Spouse A to be processed first, setting the FACTS Family ID on the *existing* account.
    // Then, Spouse B will land under that same account, since the Family ID is now available for lookup.
    // If it were the other way around, there's a good chance that Spouse B would land in their own isolated Account,
    // unless there was a direct address match.

    for (int _i = 0; _i < importEvents.size() * 2; _i++) {
      // TODO: Not sure if this setup is clever or hacky...
      boolean secondPass = _i >= importEvents.size();
      if (_i == importEvents.size()) {
        sfdcClient.batchFlush();
      }
      int i = _i % importEvents.size();

      CrmImportEvent importEvent = importEvents.get(i);

      if (secondPass && !importEvent.secondPass) {
        continue;
      }

      log.info("import processing contacts/account on row {} of {}", i + 2, importEvents.size() + 1);

      // contactMode tells us if the sheet has Contact columns, period. But we also need to know if this row
      // actually has Contact values in it.
      boolean contactModeRow = !Strings.isNullOrEmpty(importEvent.contactEmail) || !Strings.isNullOrEmpty(importEvent.contactLastName);

      // If the accountId or account extref is explicitly given, run the account update. Otherwise, let the contact queries determine it.
      SObject account = null;
      if (!Strings.isNullOrEmpty(importEvent.account.id)) {
        SObject existingAccount = existingAccountsById.get(importEvent.account.id);

        if (existingAccount != null) {
          account = new SObject("Account");
          account.setId(existingAccount.getId());

          setBulkImportAccountFields(account, existingAccount, importEvent);
          if (!batchUpdateAccounts.contains(existingAccount.getId())) {
            batchUpdateAccounts.add(existingAccount.getId());
            sfdcClient.batchUpdate(account);
          }
        }
      } else if (accountExtRefKey.isPresent() && !Strings.isNullOrEmpty(importEvent.raw.get(accountExtRefKey.get()))) {
        SObject existingAccount = existingAccountsByExtRef.get(importEvent.raw.get(accountExtRefKey.get()));

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
          // TODO: This was mainly due to CLHS' original FACTS migration that created isolated households or, worse,
          //  combined grandparents into the student's household. Raiser's Edge has the correct relationships and
          //  households, so we're using this to override the past.
//          account = insertBulkImportAccount(importEvent.contactLastName + " Household", importEvent,
//              accountExtRefFieldName, existingAccountsByExtRef, accountMode);
        }
      }

      SObject contact = null;

      // A few situations have come up where there were not cleanly-split first vs. last name columns, but instead a
      // "salutation" (firstname) and then a full name. Allow users to provide the former as the firstname
      // and the latter as the "lastname", but clean it up. This must happen before the first names are split up, below!
      if (!Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(importEvent.contactLastName)
          && importEvent.contactLastName.contains(importEvent.contactFirstName)) {
        // TODO: The above may still be important, but this introduces bugs. Examples: Anonymous as the first and last
        //  name (last name will be stripped to empty string), real last names that contain a first name (Brett Bretterson), etc.
//        importEvent.contactLastName = importEvent.contactLastName.replace(importEvent.contactFirstName, "").trim();
      }

      // Deep breath. It gets weird.

      // If we're in the second pass, we already know we need to insert the contact.
      if (secondPass) {
        if (account == null) {
          String accountName = importEvent.account.name;
          if (Strings.isNullOrEmpty(accountName)) {
            accountName = importEvent.contactLastName + " Household";
          }

          account = insertBulkImportAccount(accountName, importEvent,
              accountExtRefFieldName, existingAccountsByExtRef, accountMode);
        }

        contact = insertBulkImportContact(importEvent, account, batchInsertContacts,
            existingContactsByEmail, existingContactsByName, contactExtRefFieldName, existingContactsByExtRef, nonBatchMode);
      }
      // If we're in account-only mode, we have no contact info to match against, so stick to accounts by-name
      else if (accountMode && (!contactMode || !contactModeRow) && !Strings.isNullOrEmpty(importEvent.account.name)) {
        SObject existingAccount;
        if (account != null) {
          existingAccount = account;
        } else {
          existingAccount = existingAccountsByName.get(importEvent.account.name.toLowerCase(Locale.ROOT)).stream().findFirst().orElse(null);
        }

        if (existingAccount == null) {
          account = insertBulkImportAccount(importEvent.account.name, importEvent, accountExtRefFieldName, existingAccountsByExtRef, accountMode);
          existingAccountsByName.put(importEvent.account.name.toLowerCase(Locale.ROOT), account);
        } else {
          account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts, true);
        }
      }
      // If the explicit Contact ID was given and the contact actually exists, update.
      else if (!Strings.isNullOrEmpty(importEvent.contactId) && existingContactsById.containsKey(importEvent.contactId)) {
        SObject existingContact = existingContactsById.get(importEvent.contactId);

        if (account == null) {
          String accountId = (String) existingContact.getField("AccountId");
          if (!Strings.isNullOrEmpty(accountId)) {
            SObject existingAccount = (SObject) existingContact.getChild("Account");
            account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts, accountMode);
          }
        }

        // use accountId, not account.id -- if the contact was recently imported by this current process,
        // the contact.account child relationship will not yet exist in the existingContactsById map
        contact = updateBulkImportContact(existingContact, account, importEvent, batchUpdateContacts);
      }
      // Similarly, if we have an external ref ID, check that next.
      else if (contactExtRefKey.isPresent() && existingContactsByExtRef.containsKey(importEvent.raw.get(contactExtRefKey.get()))) {
        SObject existingContact = existingContactsByExtRef.get(importEvent.raw.get(contactExtRefKey.get()));

        if (account == null) {
          String accountId = (String) existingContact.getField("AccountId");
          if (!Strings.isNullOrEmpty(accountId)) {
            SObject existingAccount = (SObject) existingContact.getChild("Account");
            account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts, accountMode);
          }
        }

        // use accountId, not account.id -- if the contact was recently imported by this current process,
        // the contact.account child relationship will not yet exist in the existingContactsByExtRefId map
        contact = updateBulkImportContact(existingContact, account, importEvent, batchUpdateContacts);
      }
      // Else if a contact already exists with the given email address, update.
      else if (!Strings.isNullOrEmpty(importEvent.contactEmail) && existingContactsByEmail.containsKey(importEvent.contactEmail.toLowerCase(Locale.ROOT))) {
        // If the email address has duplicates, use the oldest.
        SObject existingContact = existingContactsByEmail.get(importEvent.contactEmail.toLowerCase(Locale.ROOT)).stream()
            .min(Comparator.comparing(c -> ((String) c.getField("CreatedDate")))).get();

        if (account == null) {
          String accountId = (String) existingContact.getField("AccountId");
          if (!Strings.isNullOrEmpty(accountId)) {
            SObject existingAccount = (SObject) existingContact.getChild("Account");
            account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts, accountMode);
          }
        }

        // use accountId, not account.id -- if the contact was recently imported by this current process,
        // the contact.account child relationship will not yet exist in the existingContactsByEmail map
        contact = updateBulkImportContact(existingContact, account, importEvent, batchUpdateContacts);
      }
      // If we have a first and last name, try searching for an existing contact by name.
      // Only do this if we can match against street address or mobile number as well. Simply by-name is too risky.
      // Better to allow duplicates than to overwrite records.
      // If 1 match, update. If 0 matches, insert. If 2 or more matches, skip completely out of caution.
      else if (!Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(importEvent.contactLastName)
          && existingContactsByName.containsKey(importEvent.contactFirstName.toLowerCase(Locale.ROOT) + " " + importEvent.contactLastName.toLowerCase(Locale.ROOT))
          && (!Strings.isNullOrEmpty(importEvent.contactMailingStreet) || !Strings.isNullOrEmpty(importEvent.originalStreet) || !Strings.isNullOrEmpty(importEvent.account.billingAddress.street) || !Strings.isNullOrEmpty(importEvent.account.mailingAddress.street) || !Strings.isNullOrEmpty(importEvent.contactMobilePhone))) {
        List<SObject> existingContacts = existingContactsByName.get(importEvent.contactFirstName.toLowerCase(Locale.ROOT) + " " + importEvent.contactLastName.toLowerCase()).stream()
            .filter(c -> {
              // if the SFDC record has no address or phone at all, allow the by-name match
              // TODO: Concerned this somewhat defeats the purpose, but we're running into situations with the schools
              //  where basic records were manually created without contact info, then the SIS syncs need to match
              //  against what was created...
              if (c.getField("MailingStreet") == null && c.getChild("Account").getField("BillingStreet") == null
                  && c.getChild("Account").getField("ShippingStreet") == null && c.getField("MobilePhone") == null) {
                return true;
              }

              // make the address checks a little more resilient by removing all non-alphanumerics
              // ex: 123 Main St. != 123 Main St --> 123MainSt == 123MainSt

              String mailingStreet = c.getField("MailingStreet") == null ? "" : c.getField("MailingStreet").toString().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
              String billingStreet = c.getChild("Account").getField("BillingStreet") == null ? "" : c.getChild("Account").getField("BillingStreet").toString().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
              String shippingStreet = c.getChild("Account").getField("ShippingStreet") == null ? "" : c.getChild("Account").getField("ShippingStreet").toString().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);

              if (!Strings.isNullOrEmpty(importEvent.contactMailingStreet)) {
                String importStreet = importEvent.contactMailingStreet.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                if (importStreet.equals(mailingStreet)) {
                  return true;
                }
              }
              if (!Strings.isNullOrEmpty(importEvent.account.billingAddress.street)) {
                String importStreet = importEvent.account.billingAddress.street.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                if (importStreet.equals(billingStreet)) {
                  return true;
                }
              }
              if (!Strings.isNullOrEmpty(importEvent.account.mailingAddress.street)) {
                String importStreet = importEvent.account.mailingAddress.street.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                if (importStreet.equals(shippingStreet)) {
                  return true;
                }
              }
              if (!Strings.isNullOrEmpty(importEvent.originalStreet)) {
                String importStreet = importEvent.originalStreet.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                if (importStreet.equals(mailingStreet) || importStreet.equals(billingStreet) || importStreet.equals(shippingStreet)) {
                  return true;
                }
              }
              if (!Strings.isNullOrEmpty(importEvent.contactMobilePhone)) {
                String importMobile = importEvent.contactMobilePhone.replaceAll("\\D", "");
                String sfdcMobile = c.getField("MobilePhone") == null ? "" : c.getField("MobilePhone").toString().replaceAll("\\D", "");
                if (importMobile.equals(sfdcMobile)) {
                  return true;
                }
              }

              return false;
            }).toList();
        log.info("number of contacts for name {} {}: {}", importEvent.contactFirstName, importEvent.contactLastName, existingContacts.size());

        if (existingContacts.size() > 1) {
          // To be safe, let's skip this row for now and deal with it manually...
          log.warn("skipping contact in row {} due to multiple contacts found by-name", i + 2);
        } else if (existingContacts.size() == 1) {
          SObject existingContact = existingContacts.get(0);

          if (account == null) {
            String accountId = (String) existingContact.getField("AccountId");
            if (!Strings.isNullOrEmpty(accountId)) {
              SObject existingAccount = (SObject) existingContact.getChild("Account");
              account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts, accountMode);
            }
          }

          contact = updateBulkImportContact(existingContact, account, importEvent, batchUpdateContacts);
        } else {
          importEvent.secondPass = true;
          continue;
        }
      }
      // Otherwise, abandon all hope and insert, but only if we at least have a lastname or email.
      else if (contactModeRow) {
        importEvent.secondPass = true;
        continue;
      }

      if (contact != null) {
        for (String campaignId : importEvent.contactCampaignIds) {
          if (!Strings.isNullOrEmpty(campaignId)) {
            addContactToCampaign(contact.getId(), campaignId, true);
          }
        }

        for (String campaignName : importEvent.contactCampaignNames) {
          if (!Strings.isNullOrEmpty(campaignName)) {
            if (campaignNameToId.containsKey(campaignName.toLowerCase(Locale.ROOT))) {
              addContactToCampaign(contact.getId(), campaignNameToId.get(campaignName.toLowerCase(Locale.ROOT)), true);
            } else {
              String campaignId = insertCampaign(new CrmCampaign(null, campaignName));
              campaignNameToId.put(campaignId, campaignName);
              addContactToCampaign(contact.getId(), campaignId, true);
            }
          }
        }
      }

      if (orgMode && contact != null) {
        importOrgAffiliations(contact, existingAccountsById, existingAccountsByExtRef, existingAccountsByName, batchUpdateAccounts, seenRelationships, importEvent);
      }

      if (nonBatchMode) {
        nonBatchAccountIds.set(i, account != null ? account.getId() : null);
        nonBatchContactIds.set(i, contact != null ? contact.getId() : null);
      }

      env.logJobProgress("Imported " + (i + 1) + " contacts");
    }

    sfdcClient.batchFlush();

    if (rdMode) {
      // TODO: Won't this loop process the same RD over and over each time it appears in an Opp row? Keep track of "visited"?
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

        // TODO: Add a RD Campaign Name column as well?
        if (!Strings.isNullOrEmpty(importEvent.recurringDonationCampaignId)) {
          recurringDonation.setField("Npe03__Recurring_Donation_Campaign__c", importEvent.recurringDonationCampaignId);
        } else if (!Strings.isNullOrEmpty(importEvent.opportunityCampaignName) && campaignNameToId.containsKey(importEvent.opportunityCampaignName.toLowerCase(Locale.ROOT))) {
          recurringDonation.setField("Npe03__Recurring_Donation_Campaign__c", campaignNameToId.get(importEvent.opportunityCampaignName.toLowerCase(Locale.ROOT)));
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

        env.logJobProgress("Imported " + (i + 1) + " recurring donations");
      }

      sfdcClient.batchFlush();
    }

    if (oppMode) {
      for (int i = 0; i < importEvents.size(); i++) {
        CrmImportEvent importEvent = importEvents.get(i);

        log.info("import processing opportunities on row {} of {}", i + 2, importEvents.size() + 1);

        SObject opportunity = new SObject("Opportunity");

        opportunity.setField("AccountId", nonBatchAccountIds.get(i));
        if (!Strings.isNullOrEmpty(nonBatchContactIds.get(i))) {
          opportunity.setField("ContactId", nonBatchContactIds.get(i));
        }

        if (!Strings.isNullOrEmpty(importEvent.opportunityCampaignId)) {
          opportunity.setField("CampaignId", importEvent.opportunityCampaignId);
        } else if (!Strings.isNullOrEmpty(importEvent.opportunityCampaignName) && campaignNameToId.containsKey(importEvent.opportunityCampaignName.toLowerCase(Locale.ROOT))) {
          opportunity.setField("CampaignId", campaignNameToId.get(importEvent.opportunityCampaignName.toLowerCase(Locale.ROOT)));
        }

        if (!Strings.isNullOrEmpty(importEvent.opportunityId)) {
          opportunity.setId(importEvent.opportunityId);
          setBulkImportOpportunityFields(opportunity, existingOpportunitiesById.get(opportunity.getId()), importEvent);
          if (!batchUpdateOpportunities.contains(opportunity.getId())) {
            batchUpdateOpportunities.add(opportunity.getId());
            sfdcClient.batchUpdate(opportunity);
          }
        } else if (opportunityExtRefKey.isPresent() && existingOpportunitiesByExtRefId.containsKey(importEvent.raw.get(opportunityExtRefKey.get()))) {
          SObject existingOpportunity = existingOpportunitiesByExtRefId.get(importEvent.raw.get(opportunityExtRefKey.get()));

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

          sfdcClient.batchInsert(opportunity);
        }
        env.logJobProgress("Imported " + (i + 1) + " opportunities");
      }

      sfdcClient.batchFlush();
    }

    log.info("bulk import complete");
  }

  protected SObject updateBulkImportAccount(SObject existingAccount, CrmImportEvent importEvent,
      List<String> bulkUpdateAccounts, boolean accountMode) throws InterruptedException, ExecutionException {
    // TODO: Odd situation. When insertBulkImportContact creates a contact, it's also creating an Account, sets the
    //  AccountId on the Contact and then adds the Contact to existingContactsByEmail so we can reuse it. But when
    //  we encounter the contact again, the code upstream attempts to update the Account. 1) We don't need to, since it
    //  was just created, and 2) we can't set Contact.Account since that child can't exist when the contact is inserted,
    //  and that insert might happen later during batch processing.
    if (existingAccount == null) {
      return null;
    }

    if (!accountMode) {
      return existingAccount;
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
      Optional<String> accountExtRefFieldName,
      Map<String, SObject> existingAccountsByExtRef,
      boolean accountImports
  ) throws InterruptedException, ExecutionException {
    // TODO: This speeds up, but we have some clients (most recent one: TER) where household auto generation
    //  is kicking off workflows (TER: Primary Contact Changed Process) that nail CPU/query limits. If we want
    //  to use this, we might need to dial back the batch sizes...
    if (!accountImports) {
      return null;
    }

    SObject account = new SObject("Account");

    setField(account, "Name", accountName);
    setBulkImportAccountFields(account, null, importEvent);

    String accountId = sfdcClient.insert(account).getId();
    account.setId(accountId);

    if (accountExtRefFieldName.isPresent() && !Strings.isNullOrEmpty((String) account.getField(accountExtRefFieldName.get()))) {
      existingAccountsByExtRef.put((String) account.getField(accountExtRefFieldName.get()), account);
    }

    return account;
  }

  protected SObject updateBulkImportContact(SObject existingContact, SObject account, CrmImportEvent importEvent,
      List<String> bulkUpdateContacts) throws InterruptedException, ExecutionException {
    SObject contact = new SObject("Contact");
    contact.setId(existingContact.getId());
    if (account != null) {
      contact.setField("AccountId", account.getId());
    }

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
      SObject account,
      List<String> bulkInsertContacts,
      Multimap<String, SObject> existingContactsByEmail,
      Multimap<String, SObject> existingContactsByName,
      Optional<String> contactExtRefFieldName,
      Map<String, SObject> existingContactsByExtRef,
      boolean nonBatchMode
  ) throws InterruptedException, ExecutionException {
    SObject contact = new SObject("Contact");

    String fullName = null;
    // last name is required
    if (Strings.isNullOrEmpty(importEvent.contactLastName)) {
      contact.setField("LastName", "Anonymous");
    } else {
      contact.setField("FirstName", importEvent.contactFirstName);
      contact.setField("LastName", importEvent.contactLastName);
      fullName = importEvent.contactFirstName + " " + importEvent.contactLastName;
    }

    boolean isAnonymous = false;
    if ("Anonymous".equalsIgnoreCase((String) contact.getField("LastName"))) {
      isAnonymous = true;
    }

    setBulkImportContactFields(contact, null, importEvent);

    if (account != null) {
      contact.setField("AccountId", account.getId());
    }

    if (nonBatchMode) {
      SaveResult saveResult = sfdcClient.insert(contact);
      contact.setId(saveResult.getId());
    } else {
      // TODO: We need to prevent contacts that appear on multiple rows (especially for opportunity imports) from being
      //  created over and over. Incorporate id and extref too? What about common first/last names? Allow
      //  that since duplicates within the SAME SHEET are likely the same person?
      String key = null;
      if (!Strings.isNullOrEmpty((String) contact.getField("Email"))) {
        key = (String) contact.getField("Email");
      } else if (!isAnonymous) {
        key = contact.getField("FirstName") + " " + contact.getField("LastName");
      }

      if (key == null) {
        sfdcClient.batchInsert(contact);
      } else if (!bulkInsertContacts.contains(key)) {
        bulkInsertContacts.add(key);
        sfdcClient.batchInsert(contact);
      }
    }

    // Since we hold maps in memory and don't requery them. Add entries as we go to prevent duplicate inserts.
    if (!Strings.isNullOrEmpty((String) contact.getField("Email"))) {
      existingContactsByEmail.put(contact.getField("Email").toString().toLowerCase(Locale.ROOT), contact);
    }
    if (!isAnonymous && !Strings.isNullOrEmpty(fullName)) {
      existingContactsByName.put(fullName.toLowerCase(Locale.ROOT), contact);
    }
    if (contactExtRefFieldName.isPresent() && !Strings.isNullOrEmpty((String) contact.getField(contactExtRefFieldName.get()))) {
      existingContactsByExtRef.put((String) contact.getField(contactExtRefFieldName.get()), contact);
    }

    return contact;
  }

  protected void setBulkImportContactFields(SObject contact, SObject existingContact, CrmImportEvent importEvent)
      throws ExecutionException {
    if (!Strings.isNullOrEmpty(importEvent.contactRecordTypeId)) {
      contact.setField("RecordTypeId", importEvent.contactRecordTypeId);
    } else if (!Strings.isNullOrEmpty(importEvent.contactRecordTypeName)) {
      contact.setField("RecordTypeId", recordTypeNameToIdCache.get(importEvent.contactRecordTypeName));
    }

    contact.setField("Salutation", importEvent.contactSalutation);
    contact.setField("Description", importEvent.contactDescription);
    contact.setField("MailingStreet", importEvent.contactMailingStreet);
    contact.setField("MailingCity", importEvent.contactMailingCity);
    contact.setField("MailingState", importEvent.contactMailingState);
    contact.setField("MailingPostalCode", importEvent.contactMailingZip);
    contact.setField("MailingCountry", importEvent.contactMailingCountry);
    contact.setField("HomePhone", importEvent.contactHomePhone);
    contact.setField("MobilePhone", importEvent.contactMobilePhone);
    if (env.getConfig().salesforce.npsp) {
      contact.setField("npe01__WorkPhone__c", importEvent.contactWorkPhone);
      contact.setField("npe01__PreferredPhone__c", importEvent.contactPreferredPhone);
    }

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
      throws ExecutionException {
    if (!Strings.isNullOrEmpty(importEvent.account.recordTypeId)) {
      account.setField("RecordTypeId", importEvent.account.recordTypeId);
    } else if (!Strings.isNullOrEmpty(importEvent.account.recordTypeName)) {
      account.setField("RecordTypeId", recordTypeNameToIdCache.get(importEvent.account.recordTypeName));
    }

    setField(account, "BillingStreet", importEvent.account.billingAddress.street);
    setField(account, "BillingCity", importEvent.account.billingAddress.city);
    setField(account, "BillingState", importEvent.account.billingAddress.state);
    setField(account, "BillingPostalCode", importEvent.account.billingAddress.postalCode);
    setField(account, "BillingCountry", importEvent.account.billingAddress.country);
    setField(account, "ShippingStreet", importEvent.account.mailingAddress.street);
    setField(account, "ShippingCity", importEvent.account.mailingAddress.city);
    setField(account, "ShippingState", importEvent.account.mailingAddress.state);
    setField(account, "ShippingPostalCode", importEvent.account.mailingAddress.postalCode);
    setField(account, "ShippingCountry", importEvent.account.mailingAddress.country);

    account.setField("Description", importEvent.account.description);
    account.setField("OwnerId", importEvent.account.ownerId);
    account.setField("Phone", importEvent.account.phone);
    account.setField("Type", importEvent.account.type);
    account.setField("Website", importEvent.account.website);

    setBulkImportCustomFields(account, existingAccount, "Account", importEvent);
  }

  // TODO: This mostly duplicates the primary import's accounts by-id, by-extref, and by-name. DRY it up?
  protected void importOrgAffiliations(
      SObject contact,
      Map<String, SObject> existingAccountsById,
      Map<String, SObject> existingAccountsByExtRef,
      Multimap<String, SObject> existingAccountsByName,
      List<String> batchUpdateAccounts,
      Set<String> seenRelationships,
      CrmImportEvent importEvent
  ) throws ExecutionException, InterruptedException, ConnectionException {
    for (int j = 0; j < importEvent.contactOrganizations.size(); j++) {
      CrmAccount crmOrg = importEvent.contactOrganizations.get(j);
      String role = importEvent.contactOrganizationRoles.get(j);

      int finalJ = j + 1;
      Optional<String> orgExtRefKey = importEvent.raw.keySet().stream()
          .filter(k -> k.startsWith("Organization " + finalJ + " ExtRef ")).distinct().findFirst();
      Optional<String> orgExtRefFieldName = orgExtRefKey.map(k -> k.replace("Organization " + finalJ + " ExtRef ", ""));

      SObject org = null;
      if (!Strings.isNullOrEmpty(crmOrg.id)) {
        SObject existingOrg = existingAccountsById.get(crmOrg.id);

        if (existingOrg != null) {
          org = new SObject("Account");
          org.setId(existingOrg.getId());

          setBulkImportAccountFields(org, existingOrg, importEvent);
          if (!batchUpdateAccounts.contains(existingOrg.getId())) {
            batchUpdateAccounts.add(existingOrg.getId());
            sfdcClient.batchUpdate(org);
          }
        }
      } else if (orgExtRefKey.isPresent() && !Strings.isNullOrEmpty(importEvent.raw.get(orgExtRefKey.get()))) {
        SObject existingOrg = existingAccountsByExtRef.get(importEvent.raw.get(orgExtRefKey.get()));

        if (existingOrg != null) {
          org = new SObject("Account");
          org.setId(existingOrg.getId());

          setBulkImportAccountFields(org, existingOrg, importEvent);
          if (!batchUpdateAccounts.contains(existingOrg.getId())) {
            batchUpdateAccounts.add(existingOrg.getId());
            sfdcClient.batchUpdate(org);
          }
        } else {
          // IMPORTANT: We're making an assumption here that if an ExtRef is provided, the expectation is that the
          // account already exists (which wasn't true, above) OR that it should be created. REGARDLESS of the contact's
          // current account, if any. We're opting to create the new account, update the contact's account ID, and
          // possibly abandon its old account (if it exists).
          // TODO: This was mainly due to CLHS' original FACTS migration that created isolated households or, worse,
          //  combined grandparents into the student's household. Raiser's Edge has the correct relationships and
          //  households, so we're using this to override the past.
          org = insertBulkImportAccount(crmOrg.name, importEvent, orgExtRefFieldName, existingAccountsByExtRef, true);
        }
      } else {
        SObject existingOrg = existingAccountsByName.get(crmOrg.name.toLowerCase(Locale.ROOT)).stream().findFirst().orElse(null);

        if (existingOrg == null) {
          org = insertBulkImportAccount(crmOrg.name, importEvent, orgExtRefFieldName, existingAccountsByExtRef, true);
          existingAccountsByName.put(crmOrg.name.toLowerCase(Locale.ROOT), org);
        } else {
          org = updateBulkImportAccount(existingOrg, importEvent, batchUpdateAccounts, true);
        }
      }

      if (env.getConfig().salesforce.npsp) {
        if (seenRelationships.contains(contact.getId() + "::" + org.getId()) || seenRelationships.contains(org.getId() + "::" + contact.getId())) {
          continue;
        }

        SObject affiliation = new SObject("npe5__Affiliation__c");
        affiliation.setField("npe5__Contact__c", contact.getId());
        affiliation.setField("npe5__Organization__c", org.getId());
        affiliation.setField("npe5__Status__c", "Current");
        affiliation.setField("npe5__Role__c", role);
        sfdcClient.batchInsert(affiliation);
      } else {
        // In commercial SFDC, a contact is considered "private" if it does not have a primary account (AccountId).
        // So in our setup, we need to ensure Org #1 is set as AccountId, if and only if the Contact does not already
        // have AccountId set. But note that AccountId will automatically create an AccountContactRelation, but without
        // a defined role. So we still need to update it to set that role.
        // That's confusing. Examples, assuming we're importing Contact (C) and Organization 1 (O1)
        // - C has no AccountId. Set it to O1, allow the AccountContactRelation to be created, then update it to set the role.
        // - C has an AccountId and it's already set to O1. Update the AccountContactRelation to set the role, if it's not already set.
        // - C has an AccountId and it's a different Org. Leave AccountId alone and create the new AccountContactRelation.

        if (Strings.isNullOrEmpty((String) contact.getField("AccountId"))) {
          // Private contact. Set AccountId and update the relation's role.

          SObject contactUpdate = new SObject("Contact");
          contactUpdate.setId(contact.getId());
          contactUpdate.setField("AccountId", org.getId());
          // cannot batch -- need to wait for the update to finish so the relation (below) is available
          sfdcClient.update(contactUpdate);

          if (!Strings.isNullOrEmpty(role)) {
            Optional<SObject> relation = sfdcClient.querySingle("SELECT Id FROM AccountContactRelation WHERE ContactId='" + contact.getId() + "' AND AccountId='" + org.getId() + "'");
            if (relation.isPresent()) {
              SObject relationUpdate = new SObject("AccountContactRelation");
              relationUpdate.setId(relation.get().getId());
              // TODO: The default Roles field is a multiselect picklist. We nearly ALWAYS create this custom, free-text
              //  field instead. But we're obviously making an assumption here...
              relationUpdate.setField("Role__c", role);
              sfdcClient.batchUpdate(relationUpdate);
            } else {
              log.error("AccountContactRelation could not be found for {} and {}", contact.getId(), org.getId());
            }
          }
        } else {
          if (!Strings.isNullOrEmpty(role)) {
            Optional<SObject> relation = sfdcClient.querySingle("SELECT Id, Role__c FROM AccountContactRelation WHERE ContactId='" + contact.getId() + "' AND AccountId='" + org.getId() + "'");
            if (relation.isPresent()) {
              if (Strings.isNullOrEmpty((String) relation.get().getField("Role__c"))) {
                SObject relationUpdate = new SObject("AccountContactRelation");
                relationUpdate.setId(relation.get().getId());
                // TODO: The default Roles field is a multiselect picklist. We nearly ALWAYS create this custom, free-text
                //  field instead. But we're obviously making an assumption here...
                relationUpdate.setField("Role__c", role);
                sfdcClient.batchUpdate(relationUpdate);
              }
            } else {
              SObject affiliation = new SObject("AccountContactRelation");
              affiliation.setField("ContactId", contact.getId());
              affiliation.setField("AccountId", org.getId());
              affiliation.setField("IsActive", true);
              // TODO: The default Roles field is a multiselect picklist. We nearly ALWAYS create this custom, free-text
              //  field instead. But we're obviously making an assumption here...
              affiliation.setField("Role__c", role);
              sfdcClient.batchInsert(affiliation);
            }
          }
        }
      }

      seenRelationships.add(contact.getId() + "::" + org.getId());
      seenRelationships.add(org.getId() + "::" + contact.getId());
    }
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
      throws ExecutionException {
    if (!Strings.isNullOrEmpty(importEvent.opportunityRecordTypeId)) {
      opportunity.setField("RecordTypeId", importEvent.opportunityRecordTypeId);
    } else if (!Strings.isNullOrEmpty(importEvent.opportunityRecordTypeName)) {
      opportunity.setField("RecordTypeId", recordTypeNameToIdCache.get(importEvent.opportunityRecordTypeName));
    }
    if (!Strings.isNullOrEmpty(importEvent.opportunityName)) {
      // 120 is typically the max length
      int length = Math.min(importEvent.opportunityName.length(), 120);
      opportunity.setField("Name", importEvent.opportunityName.substring(0, length));
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
        sObject.setField(key, value);
      } else {
        sObject.setField(key, getCustomBulkValue(entry.getValue()));
      }
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

  protected void processBulkImportCampaignRecords(List<CrmImportEvent> importEvents) throws Exception {
    String[] campaignCustomFields = importEvents.stream().flatMap(e -> e.raw.keySet().stream())
        .distinct().filter(k -> k.startsWith("Campaign Custom "))
        .map(k -> k.replace("Campaign Custom ", "").replace("Append ", "")).toArray(String[]::new);

    List<String> campaignIds = importEvents.stream().map(e -> e.campaignId)
        .filter(campaignId -> !Strings.isNullOrEmpty(campaignId)).distinct().toList();
    Map<String, SObject> existingCampaignById = new HashMap<>();
    if (campaignIds.size() > 0) {
      sfdcClient.getCampaignsByIds(campaignIds, campaignCustomFields).forEach(c -> existingCampaignById.put(c.getId(), c));
    }

    for (int i = 0; i < importEvents.size(); i++) {
      CrmImportEvent importEvent = importEvents.get(i);

      log.info("import processing campaigns on row {} of {}", i + 2, importEvents.size() + 1);

      SObject campaign = new SObject("Campaign");
      campaign.setField("Name", importEvent.campaignName);

      if (!Strings.isNullOrEmpty(importEvent.campaignRecordTypeId)) {
        campaign.setField("RecordTypeId", importEvent.campaignRecordTypeId);
      } else if (!Strings.isNullOrEmpty(importEvent.campaignRecordTypeName)) {
        campaign.setField("RecordTypeId", recordTypeNameToIdCache.get(importEvent.campaignRecordTypeName));
      }

      if (!Strings.isNullOrEmpty(importEvent.campaignId)) {
        campaign.setId(importEvent.campaignId);
        setBulkImportCustomFields(campaign, existingCampaignById.get(importEvent.campaignId), "Campaign", importEvent);
        sfdcClient.batchUpdate(campaign);
      } else {
        setBulkImportCustomFields(campaign, null, "Campaign", importEvent);
        sfdcClient.batchInsert(campaign);
      }

      env.logJobProgress("Imported " + (i + 1) + " campaigns");
    }

    sfdcClient.batchFlush();
  }

  // TODO: This is going to be a pain in the butt, but we'll try to dynamically support different data types for custom
  //  fields in bulk imports/updates, without requiring the data type to be provided. This is going to be brittle...
  protected Object getCustomBulkValue(String value) {
    Calendar c = null;
    try {
      // If a client is uploading a sheet for bulk upsert,
      // the dates are most likely to be in their own TZ
      c = Utils.getCalendarFromDateString(value, env.getConfig().timezoneId);
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

    String recordTypeId = null;
    String recordTypeName = null;
    EnvironmentConfig.AccountType recordType = EnvironmentConfig.AccountType.HOUSEHOLD;
    if (sObject.getChild("RecordType") != null) {
      recordTypeId = (String) sObject.getChild("RecordType").getField("Id");
      recordTypeName = (String) sObject.getChild("RecordType").getField("Name");
      recordTypeName = recordTypeName == null ? "" : recordTypeName.toLowerCase(Locale.ROOT);
      // TODO: Customize record type names through env.json?
      if (recordTypeName.contains("business") || recordTypeName.contains("church") || recordTypeName.contains("org") || recordTypeName.contains("group"))  {
        recordType = EnvironmentConfig.AccountType.ORGANIZATION;
      }
    }

    return new CrmAccount(
        sObject.getId(),
        billingAddress,
        (String) sObject.getField("Description"),
        shippingAddress,
        (String) sObject.getField("Name"),
        (String) sObject.getField("OwnerId"),
        (String) sObject.getField("Phone"),
        recordType,
        recordTypeId,
        recordTypeName,
        (String) sObject.getField("Type"),
        (String) sObject.getField("Website"),
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
        (String) sObject.getField("Description"),
        (String) sObject.getField("Email"),
        emailGroups,
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.emailBounced),
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.emailOptIn),
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.emailOptOut),
        firstCloseDate,
        (String) sObject.getField("FirstName"),
        homePhone,
        largestDonationAmount,
        lastCloseDate,
        (String) sObject.getField("LastName"),
        getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.contactLanguage),
        crmAddress,
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
    String subscriptionId = getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.paymentGatewaySubscriptionId);
    String customerId = getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId);
    String paymentGatewayName = getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.paymentGatewayName);
    Double amount = Double.parseDouble(sObject.getField("npe03__Amount__c").toString());
    boolean active = "Open".equalsIgnoreCase(sObject.getField("npe03__Open_Ended_Status__c").toString());
    CrmRecurringDonation.Frequency frequency = CrmRecurringDonation.Frequency.fromName(sObject.getField("npe03__Installment_Period__c").toString());

    CrmAccount account = null;
    if (sObject.getChild("npe03__Organization__r") != null && sObject.getChild("npe03__Organization__r").hasChildren())
      account = toCrmAccount((SObject) sObject.getChild("npe03__Organization__r"));
    CrmContact contact = null;
    if (sObject.getChild("npe03__Contact__r") != null && sObject.getChild("npe03__Contact__r").hasChildren())
      contact = toCrmContact((SObject) sObject.getChild("npe03__Contact__r"));

    return new CrmRecurringDonation(
        sObject.getId(),
        account,
        contact,
        active,
        amount,
        customerId,
        null, // String description,
        getStringField(sObject, "Name"),
        frequency,
        paymentGatewayName,
        getStringField(sObject, "npe03__Open_Ended_Status__c"),
        null, // String subscriptionCurrency,
        subscriptionId,
        // TODO: npsp__EndDate__c is technically available in NPSP, but not visible by default. This would require
        //  a profile update for every client.
        null, // getZonedDateFromDateString(getStringField(sObject, "npsp__EndDate__c"), env.getConfig().timezoneId),
        getZonedDateFromDateString(getStringField(sObject, "npe03__Next_Payment_Date__c"), env.getConfig().timezoneId),
        getZonedDateFromDateString(getStringField(sObject, "Npe03__Date_Established__c"), env.getConfig().timezoneId),
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
