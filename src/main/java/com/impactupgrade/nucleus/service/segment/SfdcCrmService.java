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
import com.impactupgrade.nucleus.model.AccountSearch;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmActivity;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmCampaign;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmContactListType;
import com.impactupgrade.nucleus.model.CrmCustomField;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmNote;
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.model.CrmRecord;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.model.UpdateRecurringDonationEvent;
import com.impactupgrade.nucleus.util.CacheUtil;
import com.impactupgrade.nucleus.util.Utils;
import com.sforce.soap.metadata.FieldType;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;

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

import static com.impactupgrade.nucleus.util.Utils.getZonedDateFromDateString;

public class SfdcCrmService implements CrmService {

  protected Environment env;
  protected SfdcClient sfdcClient;
  protected SfdcMetadataClient sfdcMetadataClient;

  // Globally scoped, since these are expensive calls that we want to minimize overall.
  protected static Cache<String, Map<String, String>> objectFieldsCache = CacheUtil.buildManualCache();

  // Simply scoped to the service/environment/request, since it's more of a per-flow optimization (primarily for Bulk Upsert).
  protected LoadingCache<String, String> recordTypeNameToIdCache;
  TODO anywhere recordid is set, also check CrmRecord.recordTypeName;
  /*
  if (!Strings.isNullOrEmpty(crmAccount.recordTypeId)) {
      account.recordTypeId = crmAccount.recordTypeId;
    } else if (!Strings.isNullOrEmpty(crmAccount.recordTypeName)) {
      account.recordTypeId = recordTypeNameToIdCache.get(crmAccount.recordTypeName);
    }
   */

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
        env.logJobError("unable to fetch record type {}", recordTypeName, e);
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
  public List<CrmAccount> getAccountsByEmails(List<String> emails, String... extraFields) throws Exception {
    return toCrmAccount(sfdcClient.getAccountsByEmails(emails, extraFields));
  }

  @Override
  public Optional<CrmAccount> getAccountByUniqueField(String customField, String customFieldValue, String... extraFields) throws Exception {
    return toCrmAccount(sfdcClient.getAccountByUniqueField(customField, customFieldValue, extraFields));
  }

  @Override
  public List<CrmAccount> getAccountsByUniqueField(String customField, List<String> customFieldValues, String... extraFields) throws Exception {
    return toCrmAccount(sfdcClient.getAccountsByUniqueField(customField, customFieldValues, extraFields));
  }

  @Override
  public Optional<CrmContact> getContactById(String id, String... extraFields) throws Exception {
    return toCrmContact(sfdcClient.getContactById(id, extraFields));
  }

  @Override
  public Optional<CrmContact> getFilteredContactById(String id, String filter, String... extraFields) throws Exception {
    return toCrmContact(sfdcClient.getFilteredContactById(id, filter, extraFields));
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
  public List<CrmContact> getContactsByNames(List<String> names, String... extraFields) throws Exception {
    return toCrmContact(sfdcClient.getContactsByNames(names, extraFields));
  }

  @Override
  public Optional<CrmContact> getContactByUniqueField(String customField, String customFieldValue, String... extraFields) throws Exception {
    return toCrmContact(sfdcClient.getContactByUniqueField(customField, customFieldValue, extraFields));
  }

  @Override
  public List<CrmContact> getContactsByUniqueField(String customField, List<String> customFieldValues, String... extraFields) throws Exception {
    return toCrmContact(sfdcClient.getContactsByUniqueField(customField, customFieldValues, extraFields));
  }

  @Override
  // currentPageToken assumed to be the offset index
  public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch, String... extraFields) throws InterruptedException, ConnectionException {
    return toCrmContact(sfdcClient.searchContacts(contactSearch, extraFields));
  }

  @Override
  // currentPageToken assumed to be the offset index
  public PagedResults<CrmAccount> searchAccounts(AccountSearch accountSearch, String... extraFields) throws InterruptedException, ConnectionException {
    return toCrmAccount(sfdcClient.searchAccounts(accountSearch, extraFields));
  }

  @Override
  public String insertAccount(CrmAccount crmAccount) throws Exception {
    SObject account = new SObject("Account");
    setAccountFields(account, crmAccount);
    return sfdcClient.insert(account).getId();
  }

  @Override
  public void batchInsertAccount(CrmAccount crmAccount) throws Exception {
    SObject account = new SObject("Account");
    setAccountFields(account, crmAccount);
    sfdcClient.batchInsert(account);
  }

  @Override
  public void updateAccount(CrmAccount crmAccount) throws Exception {
    SObject account = new SObject("Account");
    account.setId(crmAccount.id);
    setAccountFields(account, crmAccount);
    sfdcClient.update(account);
  }

  @Override
  public void batchUpdateAccount(CrmAccount crmAccount) throws Exception {
    SObject account = new SObject("Account");
    account.setId(crmAccount.id);
    setAccountFields(account, crmAccount);
    sfdcClient.batchUpdate(account);
  }

  @Override
  public void deleteAccount(String accountId) throws Exception {
    SObject account = new SObject("Account");
    account.setId(accountId);
    sfdcClient.delete(account);
  }

  @Override
  public Optional<CrmDonation> getDonationById(String id, String... extraFields) throws Exception {
    return toCrmDonation(sfdcClient.getDonationById(id, extraFields));
  }

  @Override
  public List<CrmDonation> getDonationsByIds(List<String> ids, String... extraFields) throws Exception {
    return toCrmDonation(sfdcClient.getDonationsByIds(ids, extraFields));
  }

  @Override
  public List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds, String accountId, String contactId, String... extraFields) throws Exception {
    return toCrmDonation(sfdcClient.getDonationsByTransactionIds(transactionIds, extraFields));
  }

  @Override
  public Optional<CrmDonation> getDonationByUniqueField(String customField, String customFieldValue, String... extraFields) throws Exception {
    return toCrmDonation(sfdcClient.getDonationByUniqueField(customField, customFieldValue, extraFields));
  }

  @Override
  public List<CrmDonation> getDonationsByUniqueField(String customField, List<String> customFieldValues, String... extraFields) throws Exception {
    return toCrmDonation(sfdcClient.getDonationsByUniqueField(customField, customFieldValues, extraFields));
  }

  @Override
  public List<CrmRecurringDonation> searchAllRecurringDonations(ContactSearch contactSearch, String... extraFields) throws InterruptedException, ConnectionException {
    List<String> clauses = searchRecurringDonations(contactSearch);

    return sfdcClient.searchRecurringDonations(clauses, extraFields)
            .stream()
            .map(this::toCrmRecurringDonation)
            .collect(Collectors.toList());
  }

  protected List<String> searchRecurringDonations(ContactSearch contactSearch) {
    List<String> clauses = new ArrayList<>();

    if (!contactSearch.keywords.isEmpty()) {
      for (String keyword : contactSearch.keywords) {
        keyword = keyword.replaceAll("'", "\\\\'");
        List<String> nameClauses = new ArrayList<>();
        nameClauses.add("npe03__Organization__r.name LIKE '%" + keyword + "%'");
        nameClauses.add("npe03__Contact__r.name LIKE '%" + keyword + "%'");
        clauses.add(String.join(" OR ", nameClauses));
      }
    }

    if (!Strings.isNullOrEmpty(contactSearch.email)) {
      String email = contactSearch.email;
      List<String> emailClauses = new ArrayList<>();
      emailClauses.add("npe03__Contact__r.npe01__HomeEmail__c LIKE '%" + email + "%'");
      emailClauses.add("npe03__Contact__r.npe01__WorkEmail__c LIKE '%" + email + "%'");
      emailClauses.add("npe03__Contact__r.npe01__AlternateEmail__c LIKE '%" + email + "%'");
      emailClauses.add("npe03__Contact__r.email LIKE '%" + email + "%'");
      clauses.add(String.join(" OR ", emailClauses));
    }

    if (!Strings.isNullOrEmpty(contactSearch.phone)) {
      String phoneClean = contactSearch.phone.replaceAll("\\D+", "");
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
  public Optional<CrmUser> getUserByEmail(String email) throws Exception {
    return toCrmUser (sfdcClient.getUserByEmail(email));
  }

  @Override
  public String insertActivity(CrmActivity crmActivity) throws Exception {
    SObject task = new SObject("Task");

    setActivityFields(task, crmActivity);

    // can only be set on inserts
    switch (crmActivity.type) {
      case TASK -> task.setField("TaskSubType", "Task");
      case EMAIL -> task.setField("TaskSubType", "Email");
      case LIST_EMAIL -> task.setField("TaskSubType", "List Email");
      case CADENCE -> task.setField("TaskSubType", "Cadence");
      default -> task.setField("TaskSubType", "Call");
    }

    return sfdcClient.insert(task).getId();
  }

  @Override
  public String updateActivity(CrmActivity crmActivity) throws Exception {
    SObject task = new SObject("Task");
    task.setId(crmActivity.id);
    setActivityFields(task, crmActivity);
    return sfdcClient.update(task).getId();
  }

  @Override
  public Optional<CrmActivity> getActivityByExternalRef(String externalRef) throws Exception {
    Optional<SObject> sObjectO = sfdcClient.getActivityByExternalReference(externalRef);
    CrmActivity crmActivity = sObjectO.map(this::toCrmActivity).orElse(null);
    return Optional.ofNullable(crmActivity);
  }

  @Override
  public String insertNote(CrmNote crmNote) throws Exception {
    SObject cn = new SObject("ContentNote");

    if (!Strings.isNullOrEmpty(crmNote.title)) {
      cn.setField("Title", crmNote.title);
    } else {
      cn.setField("Title", "Note"); // required field
    }
    cn.setField("Content", crmNote.note);
    SaveResult result = sfdcClient.insert(cn);

    if (!result.isSuccess() || Strings.isNullOrEmpty(result.getId())) {
      env.logJobInfo("ContentNote insert may have failed; skipping ContentDocumentLink insert");
      return null;
    }

    SObject cdl = new SObject("ContentDocumentLink");
    cdl.setField("ContentDocumentId", result.getId());
    cdl.setField("LinkedEntityId", crmNote.targetId);
    sfdcClient.insert(cdl);

    return result.getId();
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
      env.logJobError("failed to create custom fields", e);
    }
    return crmCustomFields;
  }

  @Override
  public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
    return this.env.getConfig().salesforce.fieldDefinitions;
  }

  protected void setActivityFields(SObject task, CrmActivity crmActivity) {
    task.setField("WhoId", crmActivity.targetId);
    task.setField("OwnerId", crmActivity.assignTo);
    task.setField("Subject", crmActivity.subject);
    task.setField("Description", crmActivity.description);
    task.setField("ActivityDate", crmActivity.dueDate);
    setField(task, env.getConfig().salesforce.fieldDefinitions.activityExternalReference, crmActivity.externalReference);

    switch (crmActivity.status) {
      case IN_PROGRESS -> task.setField("Status", "In Progress");
      case DONE -> task.setField("Status", "Completed");
      default -> task.setField("Status", "Not Started");
    }

    switch (crmActivity.priority) {
      case LOW -> task.setField("Priority", "Low");
      case HIGH, CRITICAL -> task.setField("Priority", "High");
      default -> task.setField("Priority", "Normal");
    }
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id, String... extraFields) throws Exception {
    return toCrmRecurringDonation(sfdcClient.getRecurringDonationById(id, extraFields));
  }

  @Override
  public List<CrmRecurringDonation> getRecurringDonationsByIds(List<String> ids, String... extraFields) throws Exception {
    return toCrmRecurringDonation(sfdcClient.getRecurringDonationsByIds(ids, extraFields));
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId, String accountId, String contactId, String... extraFields) throws Exception {
    return toCrmRecurringDonation(sfdcClient.getRecurringDonationBySubscriptionId(subscriptionId, extraFields));
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
    account.setField("Description", crmAccount.description);
    setField(account, env.getConfig().salesforce.fieldDefinitions.accountEmail, crmAccount.email);
    account.setField("Phone", crmAccount.phone);
    account.setField("ShippingStreet", crmAccount.mailingAddress.street);
    account.setField("ShippingCity", crmAccount.mailingAddress.city);
    account.setField("ShippingState", crmAccount.mailingAddress.state);
    account.setField("ShippingPostalCode", crmAccount.mailingAddress.postalCode);
    account.setField("ShippingCountry", crmAccount.mailingAddress.country);
    account.setField("Type", crmAccount.type);
    account.setField("Website", crmAccount.website);
    account.setField("OwnerId", crmAccount.ownerId);

    Map<EnvironmentConfig.AccountType, String> accountTypeToRecordTypeIds = env.getConfig().salesforce.accountTypeToRecordTypeIds;
    if (crmAccount.recordType != null && accountTypeToRecordTypeIds.containsKey(crmAccount.recordType)) {
      account.setField("RecordTypeId", accountTypeToRecordTypeIds.get(crmAccount.recordType));
    }
    if (crmAccount.emailOptIn != null && crmAccount.emailOptIn) {
      setField(account, env.getConfig().salesforce.fieldDefinitions.accountEmailOptIn, true);
    }
    if (crmAccount.emailOptOut != null && crmAccount.emailOptOut) {
      setField(account, env.getConfig().salesforce.fieldDefinitions.accountEmailOptOut, true);
    }
    if (crmAccount.emailBounced != null && crmAccount.emailBounced) {
      setField(account, env.getConfig().salesforce.fieldDefinitions.accountEmailBounced, true);
    }

    for (String fieldName : crmAccount.crmRawFieldsToSet.keySet()) {
      account.setField(fieldName, crmAccount.crmRawFieldsToSet.get(fieldName));
    }
  }

  @Override
  public String insertContact(CrmContact crmContact) throws Exception {
    SObject contact = new SObject("Contact");
    setContactFields(contact, crmContact);
    return sfdcClient.insert(contact).getId();
  }

  @Override
  public void batchInsertContact(CrmContact crmContact) throws Exception {
    SObject contact = new SObject("Contact");
    setContactFields(contact, crmContact);
    sfdcClient.batchInsert(contact);
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
  public void addAccountToCampaign(CrmAccount crmAccount, String campaignId) throws Exception {
    addAccountToCampaign(crmAccount.id, campaignId, false);
  }

  @Override
  public void batchAddAccountToCampaign(CrmAccount crmAccount, String campaignId) throws Exception {
    addAccountToCampaign(crmAccount.id, campaignId, true);
  }

  protected void addAccountToCampaign(String accountId, String campaignId, boolean batch) throws Exception {
    SObject campaignMember = new SObject("CampaignMember");
    campaignMember.setField("AccountId", accountId);
    campaignMember.setField("CampaignId", campaignId);
    if (batch) {
      sfdcClient.batchInsert(campaignMember);
    } else {
      sfdcClient.insert(campaignMember);
    }
  }

  @Override
  public void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception {
    addContactToCampaign(crmContact.id, campaignId, false);
  }

  @Override
  public void batchAddContactToCampaign(CrmContact crmContact, String campaignId) throws Exception {
    addContactToCampaign(crmContact.id, campaignId, true);
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
    contact.setField("Salutation", crmContact.salutation);
    contact.setField("Email", crmContact.email);
    contact.setField("MobilePhone", crmContact.mobilePhone);
    contact.setField("HomePhone", crmContact.homePhone);
    if (env.getConfig().salesforce.npsp) {
      contact.setField("npe01__WorkPhone__c", crmContact.workPhone);
      if (crmContact.preferredPhone != null) {
        contact.setField("Npe01__PreferredPhone__c", crmContact.preferredPhone.toString());
      }
    }
    setField(contact, env.getConfig().salesforce.fieldDefinitions.contactLanguage, crmContact.language);
    contact.setField("Description", crmContact.description);
    contact.setField("OwnerId", crmContact.ownerId);

    contact.setField("MailingStreet", crmContact.mailingAddress.street);
    contact.setField("MailingCity", crmContact.mailingAddress.city);
    contact.setField("MailingState", crmContact.mailingAddress.state);
    contact.setField("MailingPostalCode", crmContact.mailingAddress.postalCode);
    contact.setField("MailingCountry", crmContact.mailingAddress.country);

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

    setField(contact, env.getConfig().salesforce.fieldDefinitions.contact.utmSource, crmContact.getRawData("utm_source"));
    setField(contact, env.getConfig().salesforce.fieldDefinitions.contact.utmCampaign, crmContact.getRawData("utm_campaign"));
    setField(contact, env.getConfig().salesforce.fieldDefinitions.contact.utmMedium, crmContact.getRawData("utm_medium"));
    setField(contact, env.getConfig().salesforce.fieldDefinitions.contact.utmTerm, crmContact.getRawData("utm_term"));
    setField(contact, env.getConfig().salesforce.fieldDefinitions.contact.utmContent, crmContact.getRawData("utm_content"));

    for (String fieldName : crmContact.crmRawFieldsToSet.keySet()) {
      contact.setField(fieldName, crmContact.crmRawFieldsToSet.get(fieldName));
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
        env.logJobWarn("unable to find SFDC pledged donation for recurring donation {} that isn't in the future",
            recurringDonationId);
      }
    }

    // not a recurring donation, OR an existing pledged donation didn't exist -- create a new donation
    return processNewDonation(campaign, recurringDonationId, crmDonation);
  }

  protected String processPledgedDonation(SObject pledgedOpportunity, Optional<SObject> campaign,
      String recurringDonationId, CrmDonation crmDonation) throws Exception {
    env.logJobInfo("found SFDC pledged opportunity {} in recurring donation {}",
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
    opportunity.setField("CloseDate", Utils.toCalendar(crmDonation.closeDate, env.getConfig().timezoneId));
    opportunity.setField("Description", crmDonation.description);
    opportunity.setField("OwnerId", crmDonation.ownerId);

    // purely a default, but we generally expect this to be overridden
    if (!Strings.isNullOrEmpty(crmDonation.name)) {
      // 120 is typically the max length
      int length = Math.min(crmDonation.name.length(), 120);
      opportunity.setField("Name", crmDonation.name.substring(0, length));
    } else {
      opportunity.setField("Name", crmDonation.contact.getFullName() + " Donation");
    }

    setField(opportunity, env.getConfig().salesforce.fieldDefinitions.donation.utmSource, crmDonation.getRawData("utm_source"));
    setField(opportunity, env.getConfig().salesforce.fieldDefinitions.donation.utmCampaign, crmDonation.getRawData("utm_campaign"));
    setField(opportunity, env.getConfig().salesforce.fieldDefinitions.donation.utmMedium, crmDonation.getRawData("utm_medium"));
    setField(opportunity, env.getConfig().salesforce.fieldDefinitions.donation.utmTerm, crmDonation.getRawData("utm_term"));
    setField(opportunity, env.getConfig().salesforce.fieldDefinitions.donation.utmContent, crmDonation.getRawData("utm_content"));

    for (String fieldName : crmDonation.crmRawFieldsToSet.keySet()) {
      opportunity.setField(fieldName, crmDonation.crmRawFieldsToSet.get(fieldName));
    }
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

  @Override
  public void batchInsertRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
    SObject recurringDonation = new SObject("Npe03__Recurring_Donation__c");
    setRecurringDonationFields(recurringDonation, getCampaignOrDefault(crmRecurringDonation), crmRecurringDonation);
    sfdcClient.batchInsert(recurringDonation);
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
    if (!Strings.isNullOrEmpty(crmRecurringDonation.status)) {
      recurringDonation.setField("Npe03__Open_Ended_Status__c", crmRecurringDonation.status);
    } else {
      recurringDonation.setField("Npe03__Open_Ended_Status__c", "Open");
    }
    recurringDonation.setField("Npe03__Schedule_Type__c", "Multiply By");
    if (crmRecurringDonation.frequency != null) {
      recurringDonation.setField("Npe03__Installment_Period__c", crmRecurringDonation.frequency.name());
    }
    recurringDonation.setField("Npe03__Date_Established__c", Utils.toCalendar(crmRecurringDonation.subscriptionStartDate, env.getConfig().timezoneId));
    recurringDonation.setField("Npe03__Next_Payment_Date__c", Utils.toCalendar(crmRecurringDonation.subscriptionNextDate, env.getConfig().timezoneId));
    recurringDonation.setField("Npe03__Recurring_Donation_Campaign__c", getCampaignOrDefault(crmRecurringDonation).map(SObject::getId).orElse(null));
    recurringDonation.setField("OwnerId", crmRecurringDonation.ownerId);

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

    for (String fieldName : crmRecurringDonation.crmRawFieldsToSet.keySet()) {
      recurringDonation.setField(fieldName, crmRecurringDonation.crmRawFieldsToSet.get(fieldName));
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
    campaign.setField("Name", crmCampaign.name);
    setField(campaign, env.getConfig().salesforce.fieldDefinitions.campaignExternalReference, crmCampaign.externalReference);
    return sfdcClient.insert(campaign).getId();
  }

  @Override
  public void batchInsertCampaign(CrmCampaign crmCampaign) throws Exception {
    SObject campaign = new SObject("Campaign");
    campaign.setField("Name", crmCampaign.name);
    setField(campaign, env.getConfig().salesforce.fieldDefinitions.campaignExternalReference, crmCampaign.externalReference);
    sfdcClient.batchInsert(campaign);
  }

  @Override
  public void updateCampaign(CrmCampaign crmCampaign) throws Exception {
    SObject campaign = new SObject("Campaign");
    campaign.setId(crmCampaign.id);
    campaign.setField("Name", crmCampaign.name);
    setField(campaign, env.getConfig().salesforce.fieldDefinitions.campaignExternalReference, crmCampaign.externalReference);
    sfdcClient.update(campaign);
  }

  @Override
  public void batchUpdateCampaign(CrmCampaign crmCampaign) throws Exception {
    SObject campaign = new SObject("Campaign");
    campaign.setId(crmCampaign.id);
    campaign.setField("Name", crmCampaign.name);
    setField(campaign, env.getConfig().salesforce.fieldDefinitions.campaignExternalReference, crmCampaign.externalReference);
    sfdcClient.batchUpdate(campaign);
  }

  @Override
  public Optional<CrmCampaign> getCampaignByName(String name, String... extraFields) throws Exception {
    return toCrmCampaign(sfdcClient.getCampaignByName(name, extraFields));
  }

  @Override
  public List<CrmCampaign> getCampaignsByNames(List<String> names, String... extraFields) throws Exception {
    return toCrmCampaign(sfdcClient.getCampaignsByNames(names, extraFields));
  }

  @Override
  public Optional<CrmCampaign> getCampaignByExternalReference(String externalReference, String... extraFields) throws Exception {
    return toCrmCampaign(sfdcClient.getCampaignByExternalReference(externalReference, extraFields));
  }

  @Override
  public void deleteCampaign(String campaignId) throws Exception {
    SObject campaign = new SObject("Campaign");
    campaign.setId(campaignId);
    sfdcClient.delete(campaign);
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
  public List<CrmContact> getContactsFromList(String listId, String... extraFields) throws Exception {
    List<SObject> sObjects;
    // 701 is the Campaign ID prefix
    if (listId.startsWith("701")) {
      sObjects = sfdcClient.getContactsByCampaignId(listId, extraFields);
      // 00O - Report ID prefix
    } else if (listId.startsWith("00O")) {
      // no need for extraFields here, since we're simply grabbing what the report has in it
      sObjects = sfdcClient.getContactsByReportId(listId);
    }
    // otherwise, assume it's an explicit Opportunity name
    else {
      sObjects = sfdcClient.getContactsByOpportunityName(listId, extraFields);
    }
    return toCrmContact(sObjects);
  }

  @Override
  public void removeContactFromList(CrmContact crmContact, String listId) throws Exception {
    // likely not relevant in SFDC
  }

  @Override
  public Map<String, List<String>> getContactCampaignsByContactIds(List<String> contactIds,
      EnvironmentConfig.CommunicationList communicationList) throws Exception {
    Map<String, List<String>> contactCampaigns = new HashMap<>();
    List<SObject> campaignMembers = sfdcClient.getEmailCampaignsByContactIds(
        contactIds, communicationList.crmCampaignMemberFilter);
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
    setOpportunityFields(opportunity, crmOpportunity);
    return sfdcClient.insert(opportunity).getId();
  }

  @Override
  public void batchInsertOpportunity(CrmOpportunity crmOpportunity) throws Exception {
    SObject opportunity = new SObject("Opportunity");
    setOpportunityFields(opportunity, crmOpportunity);
    sfdcClient.batchInsert(opportunity);
  }

  @Override
  public void updateOpportunity(CrmOpportunity crmOpportunity) throws Exception {
    SObject opportunity = new SObject("Opportunity");
    opportunity.setId(crmOpportunity.id);
    setOpportunityFields(opportunity, crmOpportunity);
    sfdcClient.update(opportunity);
  }

  @Override
  public void batchUpdateOpportunity(CrmOpportunity crmOpportunity) throws Exception {
    SObject opportunity = new SObject("Opportunity");
    opportunity.setId(crmOpportunity.id);
    setOpportunityFields(opportunity, crmOpportunity);
    sfdcClient.batchUpdate(opportunity);
  }

  protected void setOpportunityFields(SObject opportunity, CrmOpportunity crmOpportunity) {
    opportunity.setField("RecordTypeId", crmOpportunity.recordTypeId);
    opportunity.setField("Name", crmOpportunity.name);
    opportunity.setField("npsp__Primary_Contact__c", crmOpportunity.contact.id);
    opportunity.setField("CloseDate", Calendar.getInstance());
    // TODO: Good enough for now, but likely needs to be customized.
    opportunity.setField("StageName", "Pledged");
    opportunity.setField("OwnerId", crmOpportunity.ownerId);
    opportunity.setField("CampaignId", crmOpportunity.campaignId);
    opportunity.setField("Description", crmOpportunity.description);
  }

  @Override
  public void updateRecurringDonation(UpdateRecurringDonationEvent updateRecurringDonationEvent) throws Exception {
    CrmRecurringDonation crmRecurringDonation = updateRecurringDonationEvent.getCrmRecurringDonation();

    SObject toUpdate = new SObject("Npe03__Recurring_Donation__c");
    toUpdate.setId(crmRecurringDonation.id);
    if (crmRecurringDonation.amount != null && crmRecurringDonation.amount > 0) {
      toUpdate.setField("Npe03__Amount__c", crmRecurringDonation.amount);
      env.logJobInfo("Updating Npe03__Amount__c to {}...", crmRecurringDonation.amount);
    }
    if (updateRecurringDonationEvent.getNextPaymentDate() != null) {
      toUpdate.setField("Npe03__Next_Payment_Date__c", updateRecurringDonationEvent.getNextPaymentDate());
      env.logJobInfo("Updating Npe03__Next_Payment_Date__c to {}...", updateRecurringDonationEvent.getNextPaymentDate().toString());
    }

    if (updateRecurringDonationEvent.getPauseDonation()) {
      toUpdate.setField("Npe03__Open_Ended_Status__c", "Closed");
      toUpdate.setFieldsToNull(new String[] {"Npe03__Next_Payment_Date__c"});

      if (updateRecurringDonationEvent.getPauseDonationUntilDate() == null) {
        env.logJobInfo("pausing {} indefinitely...", crmRecurringDonation.id);
      } else {
        env.logJobInfo("pausing {} until {}...", crmRecurringDonation.id, updateRecurringDonationEvent.getPauseDonationUntilDate().getTime());
      }
      setRecurringDonationFieldsForPause(toUpdate, updateRecurringDonationEvent);
    }

    if (updateRecurringDonationEvent.getResumeDonation()) {
      toUpdate.setField("Npe03__Open_Ended_Status__c", "Open");

      if (updateRecurringDonationEvent.getResumeDonationOnDate() == null) {
        env.logJobInfo("resuming {} immediately...", crmRecurringDonation.id);
        toUpdate.setField("Npe03__Next_Payment_Date__c", Calendar.getInstance().getTime());
      } else {
        env.logJobInfo("resuming {} on {}...", crmRecurringDonation.id, updateRecurringDonationEvent.getResumeDonationOnDate().getTime());
        toUpdate.setField("Npe03__Next_Payment_Date__c", updateRecurringDonationEvent.getResumeDonationOnDate());
      }
      setRecurringDonationFieldsForResume(toUpdate, updateRecurringDonationEvent);
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
      UpdateRecurringDonationEvent updateRecurringDonationEvent) throws Exception {
  }

  // Give orgs an opportunity to set anything else that's unique to them, prior to resume
  protected void setRecurringDonationFieldsForResume(SObject recurringDonation,
      UpdateRecurringDonationEvent updateRecurringDonationEvent) throws Exception {
  }

  @Override
  public List<CrmContact> getEmailContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    List<CrmContact> contacts = sfdcClient.getEmailContacts(updatedSince, communicationList.crmFilter).stream().map(this::toCrmContact).collect(Collectors.toList());
    if (!Strings.isNullOrEmpty(communicationList.crmLeadFilter)) {
      contacts.addAll(sfdcClient.getEmailLeads(updatedSince, communicationList.crmLeadFilter).stream().map(this::toCrmContact).toList());
    }
    return contacts;
  }

  @Override
  public List<CrmAccount> getEmailAccounts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return sfdcClient.getEmailAccounts(updatedSince, communicationList.crmAccountFilter).stream().map(this::toCrmAccount).collect(Collectors.toList());
  }

  @Override
  public List<CrmContact> getSmsContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return sfdcClient.getSmsContacts(updatedSince, communicationList.crmFilter).stream().map(this::toCrmContact).collect(Collectors.toList());
  }

  @Override
  public List<CrmUser> getUsers() throws Exception {
    return Collections.emptyList();
  }

  @Override
  public Map<String, String> getContactLists(CrmContactListType listType) throws Exception {
    Map<String, String> lists = new HashMap<>();
    List<SObject> listRecords = new ArrayList<>();
    if (listType == CrmContactListType.ALL || listType == CrmContactListType.CAMPAIGN) {
      listRecords.addAll(sfdcClient.getCampaigns());
    }
    if (listType == CrmContactListType.ALL || listType == CrmContactListType.REPORT) {
      listRecords.addAll(sfdcClient.getReports());
    }

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
        env.logJobError("unable to fetch fields from {}", object, e);
        return null;
      }
    });
  }

  @Override
  public double getDonationsTotal(String filter) throws Exception {
    if (Strings.isNullOrEmpty(filter)) {
      env.logJobWarn("no filter provided; out of caution, skipping the query to protect API limits");
      return 0.0;
    }

    SObject result = sfdcClient.querySingle("SELECT SUM(Amount) TotalAmount FROM Opportunity WHERE StageName='Closed Won' AND " + filter).get();
    return (Double) result.getField("TotalAmount");
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
        env.logJobInfo("campaign {} not found, but no default provided", campaignIdOrName);
      } else {
        env.logJobInfo("campaign {} not found; using default: {}", campaignIdOrName, defaultCampaignId);
        campaign = sfdcClient.getCampaignById(defaultCampaignId);
      }
    }

    return campaign;
  }

  protected CrmCampaign toCrmCampaign(SObject sObject) {
    return new CrmCampaign(
        sObject.getId(),
        (String) sObject.getField("Name"),
        (String) sObject.getField(env.getConfig().salesforce.fieldDefinitions.campaignExternalReference),
        sObject,
        "https://" + env.getConfig().salesforce.url + "/lightning/r/Campaign/" + sObject.getId() + "/view"
    );
  }

  protected Optional<CrmCampaign> toCrmCampaign(Optional<SObject> sObject) {
    return sObject.map(this::toCrmCampaign);
  }

  protected List<CrmCampaign> toCrmCampaign(List<SObject> sObjects) {
    return sObjects.stream().map(this::toCrmCampaign).collect(Collectors.toList());
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
        getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.accountEmail),
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

  protected PagedResults<CrmAccount> toCrmAccount(PagedResults<SObject> sObjects) {
    return new PagedResults<>(sObjects.getResults().stream().map(this::toCrmAccount).collect(Collectors.toList()),
        sObjects.getPageSize(), sObjects.getNextPageToken());
  }

  protected CrmContact toCrmContact(SObject sObject) {
    CrmContact.PreferredPhone preferredPhone = null;
    if (env.getConfig().salesforce.npsp && sObject.getField("npe01__PreferredPhone__c") != null) {
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

      if (env.getConfig().salesforce.npsp) {
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
          env.logJobError("unable to parse first/last close date", e);
        }
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
        (String) sObject.getField("Salutation"),
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.smsOptIn),
        getBooleanField(sObject, env.getConfig().salesforce.fieldDefinitions.smsOptOut),
        (String) sObject.getField("Title"),
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

    CrmRecurringDonation crmRecurringDonation = new CrmRecurringDonation();
    crmRecurringDonation.id = (String) sObject.getField("npe03__Recurring_Donation__c");

    return new CrmDonation(
        id,
        account,
        contact,
        crmRecurringDonation,
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
        (String) sObject.getChild("RecordType").getField("Name"),
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
        getStringField(sObject, "OwnerId"),
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

  protected List<CrmRecurringDonation> toCrmRecurringDonation(List<SObject> sObjects) {
    return sObjects.stream().map(this::toCrmRecurringDonation).collect(Collectors.toList());
  }

  protected CrmActivity toCrmActivity(SObject sObject) {
    CrmActivity.Type type;
    switch (sObject.getField("TaskSubType") + "") {
      case "Task" -> type = CrmActivity.Type.TASK;
      case "Email" -> type = CrmActivity.Type.EMAIL;
      case "List Email" -> type = CrmActivity.Type.LIST_EMAIL;
      case "Cadence" -> type = CrmActivity.Type.CADENCE;
      default -> type = CrmActivity.Type.CALL;
    }

    CrmActivity.Status status;
    switch (sObject.getField("Status") + "") {
      case "In Progress" -> status = CrmActivity.Status.IN_PROGRESS;
      case "Completed" -> status = CrmActivity.Status.DONE;
      default -> status = CrmActivity.Status.TO_DO;
    }

    CrmActivity.Priority priority;
    switch (sObject.getField("Priority") + "") {
      case "Low" -> priority = CrmActivity.Priority.LOW;
      case "High" -> priority = CrmActivity.Priority.HIGH;
      default -> priority = CrmActivity.Priority.MEDIUM;
    }

    return new CrmActivity(
        sObject.getId(),
        null, // sObject.getField("WhoId").toString(),
        (String) sObject.getField("OwnerId"),
        (String) sObject.getField("Subject"),
        (String) sObject.getField("Description"),
        type,
        status,
        priority,
        null, // Calendar dueDate,
        getStringField(sObject, env.getConfig().salesforce.fieldDefinitions.activityExternalReference),
        sObject,
        "https://" + env.getConfig().salesforce.url + "/lightning/r/Task/" + sObject.getId() + "/view"
    );
  }

  protected CrmUser toCrmUser(SObject sObject) {
    return new CrmUser(sObject.getId(), sObject.getField("Email").toString());
  }

  protected Optional<CrmUser> toCrmUser(Optional<SObject> sObject) {
    return sObject.map(this::toCrmUser);
  }
}
