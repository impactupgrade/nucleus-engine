/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.VirtuousClient;
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
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmNote;
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PagedResults;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VirtuousCrmService implements CrmService {

  private static final String DATE_FORMAT = "MM/dd/yyyy";
  private static final String DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";

  private VirtuousClient virtuousClient;
  protected Environment env;

  @Override
  public String name() {
    return "virtuous";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return !Strings.isNullOrEmpty(env.getConfig().virtuous.secretKey);
  }

  @Override
  public void init(Environment env) {
    this.env = env;
    this.virtuousClient = env.virtuousClient();
  }

  @Override
  public Optional<CrmAccount> getAccountById(String id) throws Exception {
    return Optional.empty();
  }

  @Override
  public List<CrmAccount> getAccountsByEmails(List<String> emails) throws Exception {
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
  public Optional<CrmContact> getContactById(String id) throws Exception {
    int contactId;
    try {
      contactId = Integer.parseInt(id);
    } catch (NumberFormatException nfe) {
      env.logJobError("Failed to parse numeric id from string {}!", id);
      return Optional.empty();
    }
    VirtuousClient.Contact contact = virtuousClient.getContactById(contactId);
    return Optional.ofNullable(asCrmContact(contact));
  }

  @Override
  public Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception {
    // TODO
    return Optional.empty();
  }

  @Override
  public Optional<CrmContact> getFilteredContactByEmail(String email, String filter) throws Exception {
    // TODO
    return Optional.empty();
  }

  @Override
  public String insertContact(CrmContact crmContact) throws Exception {
    VirtuousClient.Contact contact = asContact(crmContact);
    VirtuousClient.Contact createdContact = virtuousClient.createContact(contact);
    return createdContact == null ? null : createdContact.id + "";
  }

  @Override
  public void updateContact(CrmContact crmContact) throws Exception {
    VirtuousClient.Contact updatingContact = asContact(crmContact);
    VirtuousClient.Contact existingContact = virtuousClient.getContactById(updatingContact.id);

    VirtuousClient.ContactIndividual updatingIndividual = getPrimaryContactIndividual(updatingContact);
    VirtuousClient.ContactIndividual existingIndividual = getPrimaryContactIndividual(existingContact);

    List<VirtuousClient.ContactMethod> contactMethodsToCreate = getContactMethodsToCreate(existingIndividual, updatingIndividual);
    for (VirtuousClient.ContactMethod contactMethod : contactMethodsToCreate) {
      env.logJobInfo("Creating contact method...");
      VirtuousClient.ContactMethod createdContactMethod = virtuousClient.createContactMethod(contactMethod);
      if (createdContactMethod == null) {
        env.logJobWarn("Failed to create contact method {}/{}!", contactMethod.id, contactMethod.type);
        return;
      }
      env.logJobInfo("Contact method created.");
    }

    List<VirtuousClient.ContactMethod> contactMethodsToUpdate = getContactMethodsToUpdate(existingIndividual, updatingIndividual);
    for (VirtuousClient.ContactMethod contactMethod : contactMethodsToUpdate) {
      env.logJobInfo("Updating contact method...");
      if (virtuousClient.updateContactMethod(contactMethod) == null) {
        env.logJobWarn("Failed to update contact method {}/{}!", contactMethod.id, contactMethod.type);
        return;
      }
      env.logJobInfo("Contact method updated.");
    }

    List<VirtuousClient.ContactMethod> contactMethodsToDelete = getContactMethodsToDelete(existingIndividual, updatingIndividual);
    for (VirtuousClient.ContactMethod contactMethod : contactMethodsToDelete) {
      env.logJobInfo("Deleting contact method...");
      virtuousClient.deleteContactMethod(contactMethod);
    }

    virtuousClient.updateContact(updatingContact);
  }

  @Override
  public void addAccountToCampaign(CrmAccount crmAccount, String campaignId, String status) throws Exception {

  }

  @Override
  public void addContactToCampaign(CrmContact crmContact, String campaignId, String status) throws Exception {

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
  public String insertOpportunity(CrmOpportunity crmOpportunity) throws Exception {
    return null;
  }

  private List<VirtuousClient.ContactMethod> getContactMethodsToCreate(VirtuousClient.ContactIndividual existing, VirtuousClient.ContactIndividual updating) {
    List<VirtuousClient.ContactMethod> toCreate = new ArrayList<>();
    for (VirtuousClient.ContactMethod updatingContactMethod : updating.contactMethods) {
      boolean contactMethodExists = existing.contactMethods.stream()
          .anyMatch(contactMethod -> StringUtils.equals(contactMethod.type, updatingContactMethod.type));
      if (!contactMethodExists) {
        updatingContactMethod.contactIndividualId = existing.id;
        toCreate.add(updatingContactMethod);
      }
    }
    return toCreate;
  }

  private List<VirtuousClient.ContactMethod> getContactMethodsToUpdate(VirtuousClient.ContactIndividual existing, VirtuousClient.ContactIndividual updating) {
    for (VirtuousClient.ContactMethod existingContactMethod : existing.contactMethods) {
      for (VirtuousClient.ContactMethod updatingContactMethod : updating.contactMethods) {
        // Assuming contact individual has 1 of each type (as crmContact has)
        if (StringUtils.equals(existingContactMethod.type, updatingContactMethod.type)) {
          existingContactMethod.value = updatingContactMethod.value;
          existingContactMethod.isOptedIn = updatingContactMethod.isOptedIn;
          existingContactMethod.isPrimary = updatingContactMethod.isPrimary;
          existingContactMethod.canBePrimary = updatingContactMethod.canBePrimary;
        }
      }
    }
    return existing.contactMethods;
  }

  private List<VirtuousClient.ContactMethod> getContactMethodsToDelete(VirtuousClient.ContactIndividual existing, VirtuousClient.ContactIndividual updating) {
    List<VirtuousClient.ContactMethod> toDelete = new ArrayList<>();
    for (VirtuousClient.ContactMethod existingContactMethod : existing.contactMethods) {
      boolean updatingContactMethod = updating.contactMethods.stream()
          .anyMatch(contactMethod -> StringUtils.equals(contactMethod.type, existingContactMethod.type));
      if (!updatingContactMethod) {
        toDelete.add(existingContactMethod);
      }
    }
    return toDelete;
  }

  @Override
  public List<CrmAccount> searchAccounts(AccountSearch accountSearch) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) {
    List<VirtuousClient.QueryCondition> conditions = new ArrayList<>();
    if (!Strings.isNullOrEmpty(contactSearch.email)) {
      conditions.add(queryCondition("Email Address", "Is", contactSearch.email));
    }
    if (!Strings.isNullOrEmpty(contactSearch.phone)) {
      // TODO: Contains?
      conditions.add(queryCondition("Phone Number", "Is", contactSearch.phone));
    }
    // TODO: by name?
    if (!contactSearch.keywords.isEmpty()) {
      for (String keyword : contactSearch.keywords) {
        keyword = keyword.trim();
        if (keyword.equalsIgnoreCase("and") || keyword.equalsIgnoreCase("&")) {
          continue;
        }
        conditions.add(queryCondition("Contact Name", "Contains", keyword));
        // TODO: other Virtuous fields that could contain the keyword?
      }
    }

    VirtuousClient.Query query = contactQuery(conditions);
    List<CrmContact> contacts = virtuousClient.queryContacts(query).stream().map(this::asCrmContact).collect(Collectors.toList());
    return PagedResults.getPagedResultsFromCurrentOffset(contacts, contactSearch);
  }

  private VirtuousClient.Query contactQuery(List<VirtuousClient.QueryCondition> queryConditions) {
    VirtuousClient.QueryConditionGroup group = new VirtuousClient.QueryConditionGroup();
    group.conditions = queryConditions;
    VirtuousClient.Query query = new VirtuousClient.Query();
    //query.queryLocation = null; // TODO: decide if we need this param
    query.groups = List.of(group);
    query.sortBy = "Last Name";
    query.descending = false;
    return query;
  }

  // Donations
  @Override
  public List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds) throws Exception {
    // TODO: possible to query for the whole list at once?
    // TODO: For now, safe to assume Stripe here, but might need an interface change...
    List<CrmDonation> donations = new ArrayList<>();
    for (String transactionId : transactionIds) {
      VirtuousClient.Gift gift = virtuousClient.getGiftByTransactionSourceAndId("stripe", transactionId);
      if (gift != null) {
        donations.add(asCrmDonation(gift));
      }
    }
    return donations;
  }

  @Override
  public String insertDonation(CrmDonation crmDonation) throws Exception {
    VirtuousClient.Gift gift = asGift(crmDonation);

    String recurringDonationId = crmDonation.recurringDonation.id;
    if (!Strings.isNullOrEmpty(recurringDonationId)) {
      VirtuousClient.CreateRecurringGiftPayment createPayment = new VirtuousClient.CreateRecurringGiftPayment();
      createPayment.id = Integer.parseInt(recurringDonationId);
      createPayment.amount = crmDonation.amount;
      createPayment.state = "Add";
      gift.recurringGiftPayments.add(createPayment);
    }

    VirtuousClient.Gift createdGift = virtuousClient.createGift(gift);
    if (Objects.nonNull(createdGift)) {
      return createdGift.id + "";
    } else {
      return null;
    }
  }

  @Override
  public void updateDonation(CrmDonation crmDonation) throws Exception {
    VirtuousClient.Gift gift = asGift(crmDonation);
    try {
      gift.id = Integer.parseInt(crmDonation.id);
    } catch (NumberFormatException nfe) {
      env.logJobError("Failed to parse numeric id from string {}!", crmDonation.id);
      return;
    }
    virtuousClient.updateGift(gift);
  }

  @Override
  public void refundDonation(CrmDonation crmDonation) throws Exception {
    VirtuousClient.Gift gift = virtuousClient.getGiftByTransactionSourceAndId(crmDonation.gatewayName, crmDonation.transactionId);
    if (Objects.nonNull(gift)) {
      virtuousClient.createReversingTransaction(gift);
    }
  }

  @Override
  public void insertDonationDeposit(List<CrmDonation> crmDonations) throws Exception {
    for (CrmDonation crmDonation : crmDonations) {
      VirtuousClient.Gift gift = (VirtuousClient.Gift) crmDonation.crmRawObject;

      // If the payment gateway event has a refund ID, this item in the payout was a refund. Mark it as such!
      if (!Strings.isNullOrEmpty(crmDonation.refundId)) {
        // TODO
      } else {
        EnvironmentConfig.VirtuousPlatform virtuousPlatform = env.getConfig().virtuous;
        if (!Strings.isNullOrEmpty(virtuousPlatform.fieldDefinitions.paymentGatewayDepositId)) {
          VirtuousClient.CustomField customField = new VirtuousClient.CustomField();
          customField.name = virtuousPlatform.fieldDefinitions.paymentGatewayDepositId;
          customField.dataType = "Text";
          customField.value = crmDonation.depositId;
          gift.customFields.add(customField);
        }
        if (!Strings.isNullOrEmpty(virtuousPlatform.fieldDefinitions.paymentGatewayDepositDate)) {
          VirtuousClient.CustomField customField = new VirtuousClient.CustomField();
          customField.name = virtuousPlatform.fieldDefinitions.paymentGatewayDepositDate;
          customField.dataType = "Date";
          customField.value = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT).format(crmDonation.depositDate);
          gift.customFields.add(customField);
        }
        if (!Strings.isNullOrEmpty(virtuousPlatform.fieldDefinitions.paymentGatewayDepositFee)) {
          VirtuousClient.CustomField customField = new VirtuousClient.CustomField();
          customField.name = virtuousPlatform.fieldDefinitions.paymentGatewayDepositFee;
          customField.dataType = "Text";
          customField.value = crmDonation.feeInDollars + "";
          gift.customFields.add(customField);
        }
        if (!Strings.isNullOrEmpty(virtuousPlatform.fieldDefinitions.paymentGatewayDepositNetAmount)) {
          VirtuousClient.CustomField customField = new VirtuousClient.CustomField();
          customField.name = virtuousPlatform.fieldDefinitions.paymentGatewayDepositNetAmount;
          customField.dataType = "Text";
          customField.value = crmDonation.netAmountInDollars + "";
          gift.customFields.add(customField);
        }

        virtuousClient.updateGift(gift);
      }
    }
  }

  @Override
  public String insertRecurringDonation(CrmRecurringDonation recurringDonation) throws Exception {
    VirtuousClient.RecurringGift recurringGift = asRecurringGift(recurringDonation);
    recurringGift = virtuousClient.createRecurringGift(recurringGift);
    return recurringGift.id + "";
  }

  @Override
  public void closeRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
    virtuousClient.cancelRecurringGift(Integer.parseInt(crmRecurringDonation.id));
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
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    CrmRecurringDonation crmRecurringDonation = manageDonationEvent.getCrmRecurringDonation();
    VirtuousClient.RecurringGift recurringGift = (VirtuousClient.RecurringGift) crmRecurringDonation.crmRawObject;

    if (crmRecurringDonation.amount != null && crmRecurringDonation.amount > 0) {
      recurringGift.amount = crmRecurringDonation.amount;
      env.logJobInfo("Updating amount to {}...", crmRecurringDonation.amount);
    }

    if (manageDonationEvent.getNextPaymentDate() != null) {
      recurringGift.nextExpectedPaymentDate = new SimpleDateFormat("yyyy-MM-dd").format(manageDonationEvent.getNextPaymentDate().getTime());
    }

    if (manageDonationEvent.getPauseDonation()) {
      // TODO
    }

    if (manageDonationEvent.getResumeDonation()) {
      // TODO
    }

    virtuousClient.updateRecurringGift(recurringGift);
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    VirtuousClient.RecurringGift recurringGift = virtuousClient.getRecurringGiftById(Integer.parseInt(id));
    return Optional.ofNullable(asCrmRecurringDonation(recurringGift));
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId, String accountId, String contactId) throws Exception {
    VirtuousClient.RecurringGifts recurringGifts = virtuousClient.getRecurringGiftsByContact(Integer.parseInt(contactId));
    Optional<VirtuousClient.RecurringGift> recurringGift = recurringGifts.list.stream()
        .filter(rg -> subscriptionId.equalsIgnoreCase(rg.paymentGatewaySubscriptionId(env)))
        .findFirst();
    return asCrmRecurringDonation(recurringGift);
  }

  @Override
  public List<CrmRecurringDonation> searchAllRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception {
    ContactSearch contactSearch = new ContactSearch();
    name.ifPresent(s -> contactSearch.keywords = Set.of(s));
    email.ifPresent(s -> contactSearch.email = s);
    phone.ifPresent(s -> contactSearch.phone = s);
    PagedResults<CrmContact> crmContacts = searchContacts(contactSearch);

    List<CrmRecurringDonation> results = new ArrayList<>();

    // TODO: Auto-page? Or stick to the first page out of performance concerns?
    for (CrmContact crmContact : crmContacts.getResults()) {
      VirtuousClient.RecurringGifts recurringGifts = virtuousClient.getRecurringGiftsByContact(Integer.parseInt(crmContact.id));
      for (VirtuousClient.RecurringGift recurringGift : recurringGifts.list) {
        results.add(asCrmRecurringDonation(recurringGift));
      }
    }

    return results;
  }

  @Override
  public List<CrmContact> getEmailContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    List<VirtuousClient.Contact> contacts = virtuousClient.getContactsModifiedAfter(updatedSince);
    if (CollectionUtils.isEmpty(contacts)) {
      return Collections.emptyList();
    }

    if (!Strings.isNullOrEmpty(communicationList.crmFilter)) {
      List<VirtuousClient.ContactIndividualShort> contactIndividuals = virtuousClient.getContactIndividuals(communicationList.crmFilter);
      if (CollectionUtils.isEmpty(contactIndividuals)) {
        return Collections.emptyList();
      }
      Set<Integer> ids = contactIndividuals.stream()
          .map(contactIndividualShort -> contactIndividualShort.id)
          .collect(Collectors.toSet());
      contacts = contacts.stream()
          .filter(contact -> ids.contains(contact.id))
          .collect(Collectors.toList());
    }

    return contacts.stream()
        .map(this::asCrmContact)
        .collect(Collectors.toList());
  }

  @Override
  public List<CrmAccount> getEmailAccounts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public List<CrmContact> getSmsContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    List<VirtuousClient.Contact> contacts = virtuousClient.getContactsModifiedAfter(updatedSince);
    if (CollectionUtils.isEmpty(contacts)) {
      return Collections.emptyList();
    }

    if (!Strings.isNullOrEmpty(communicationList.crmFilter)) {
      List<VirtuousClient.ContactIndividualShort> contactIndividuals = virtuousClient.getContactIndividuals(communicationList.crmFilter);
      if (CollectionUtils.isEmpty(contactIndividuals)) {
        return Collections.emptyList();
      }
      Set<Integer> ids = contactIndividuals.stream()
          .map(contactIndividualShort -> contactIndividualShort.id)
          .collect(Collectors.toSet());
      contacts = contacts.stream()
          .filter(contact -> ids.contains(contact.id))
          .collect(Collectors.toList());
    }

    return contacts.stream()
        .map(this::asCrmContact)
        .collect(Collectors.toList());
  }

  @Override
  public Map<String, List<String>> getContactCampaignsByContactIds(List<String> contactIds,
      EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return null;
  }

  @Override
  public List<CrmUser> getUsers() throws Exception {
    return Collections.emptyList();
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
  public Map<String, String> getContactLists(CrmContactListType listType) throws Exception {
    return Collections.emptyMap();
  }

  @Override
  public Map<String, String> getFieldOptions(String object) throws Exception {
    return Collections.emptyMap();
  }

  @Override
  public String insertActivity(CrmActivity crmActivity) throws Exception {
    VirtuousClient.Task task = asTask(crmActivity);
    VirtuousClient.Task createdTask = virtuousClient.createTask(task);
    return createdTask == null ? null : createdTask.id + "";
  }

  @Override
  public String updateActivity(CrmActivity crmActivity) throws Exception {
    // TODO: May not be possible?
    return null;
  }

  @Override
  public Optional<CrmActivity> getActivityByExternalRef(String externalRef) throws Exception {
    // TODO
    return Optional.empty();
  }

  @Override
  public String insertNote(CrmNote crmNote) throws Exception {
    return null;
  }

  @Override
  public List<CrmCustomField> insertCustomFields(List<CrmCustomField> crmCustomFields) {
    return null;
  }

  private VirtuousClient.Task asTask(CrmActivity crmActivity) {
    if (crmActivity == null) {
      return null;
    }
    VirtuousClient.Task task = new VirtuousClient.Task();
    task.taskType = VirtuousClient.Task.Type.GENERAL;
    task.subject = crmActivity.subject;
    task.description = crmActivity.description;
    if (crmActivity.dueDate != null) {
      task.dueDateTime = new SimpleDateFormat(DATE_TIME_FORMAT).format(crmActivity.dueDate.getTime());
    }
    try {
      task.contactId = Integer.parseInt(crmActivity.targetId);
    } catch (NumberFormatException e) {
      env.logJobWarn("Failed to parse Integer from String '{}'!", task.contactId);
    }
    task.ownerEmail = crmActivity.assignTo;

    return task;
  }

  @Override
  public double getDonationsTotal(String filter) throws Exception {
    VirtuousClient.QueryCondition queryCondition = new VirtuousClient.QueryCondition();
    // TODO: Assuming 'Is' is the only operator...
    String[] split = filter.split(" Is ");
    queryCondition.parameter = split[0];
    queryCondition.operator = "Is";
    queryCondition.value = split[1];
    VirtuousClient.QueryConditionGroup group = new VirtuousClient.QueryConditionGroup();
    group.conditions.add(queryCondition);
    VirtuousClient.Query query = new VirtuousClient.Query();
    query.groups = List.of(group);

    List<VirtuousClient.Gift> campaignGifts = virtuousClient.queryGifts(query, false);

    if (CollectionUtils.isEmpty(campaignGifts)) {
      return 0.0;
    }
    return campaignGifts.stream()
        .mapToDouble(gift -> Double.parseDouble(gift.amount.replace("$", "").replace(",", "")))
        .sum();
  }

  @Override
  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
    // TODO
  }

  @Override
  public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
    return null;
  }

  private VirtuousClient.QueryCondition queryCondition(String parameter, String operator, String value) {
    VirtuousClient.QueryCondition queryCondition = new VirtuousClient.QueryCondition();
    queryCondition.parameter = parameter;
    queryCondition.operator = operator;
    queryCondition.value = value;
    return queryCondition;
  }

  private CrmContact asCrmContact(VirtuousClient.Contact contact) {
    if (contact == null) {
      return null;
    }
    CrmContact crmContact = new CrmContact();
    crmContact.id = contact.id + "";
    //crmContact.accountId = // ?
    VirtuousClient.ContactIndividual contactIndividual = getPrimaryContactIndividual(contact);
    crmContact.firstName = contactIndividual.firstName;
    crmContact.lastName = contactIndividual.lastName;

    Optional<VirtuousClient.ContactMethod> emailContactMethodOptional = getContactMethod(contactIndividual, "Home Email");
    if (emailContactMethodOptional.isPresent()) {
      crmContact.email = emailContactMethodOptional.get().value;
      crmContact.emailOptIn = emailContactMethodOptional.get().isOptedIn;
    }

    crmContact.homePhone = getContactMethodValue(contactIndividual, "Home Phone");
    crmContact.mobilePhone = getContactMethodValue(contactIndividual, "Mobile Phone");
    crmContact.workPhone = getContactMethodValue(contactIndividual, "Work Phone");
    //crmContact.preferredPhone = CrmContact.PreferredPhone.MOBILE // ?

    crmContact.mailingAddress = getCrmAddress(contact.address);

    //crmContact.emailOptIn;
    //crmContact.emailOptOut;
    //crmContact.smsOptIn;
    //crmContact.smsOptOut;
    //crmContact.ownerId;
    //crmContact.ownerName;
    //crmContact.totalDonationAmount = contact.lifeToDateGiving; // Parse double
    // crmContact.numDonations;
    //crmContact.firstDonationDate;
    crmContact.lastDonationDate = getDate(contact.lastGiftDate);
    crmContact.notes = contact.description;
    //  public List<String> emailGroups;
    //  public String contactLanguage;

    crmContact.crmRawObject = contact;

    return crmContact;
  }

  private VirtuousClient.ContactIndividual getPrimaryContactIndividual(VirtuousClient.Contact contact) {
    return contact.contactIndividuals.stream()
        .filter(contactIndividual -> Boolean.TRUE == contactIndividual.isPrimary)
        .findFirst().orElse(null);
  }

  private Optional<VirtuousClient.ContactMethod> getContactMethod(VirtuousClient.ContactIndividual contactIndividual, String contactMethodType) {
    return contactIndividual.contactMethods.stream()
        .filter(contactMethod -> contactMethodType.equals(contactMethod.type))
        .findFirst();
  }

  private String getContactMethodValue(VirtuousClient.ContactIndividual contactIndividual, String contactMethodType) {
    return contactIndividual.contactMethods.stream()
        .filter(contactMethod -> contactMethodType.equals(contactMethod.type))
        .findFirst()
        .map(contactMethod -> contactMethod.value).orElse(null);
  }

  private CrmAddress getCrmAddress(VirtuousClient.Address address) {
    CrmAddress crmAddress = new CrmAddress();

    if (address != null) {
      crmAddress.country = address.country;
      crmAddress.state = address.state;
      crmAddress.city = address.city;
      crmAddress.postalCode = address.postal;
      crmAddress.street = address.address1;
    }

    return crmAddress;
  }

  private ZonedDateTime getDateTime(String dateTimeString) {
    DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT);
    LocalDateTime localDateTime = LocalDateTime.parse(dateTimeString, dateTimeFormatter);
    return localDateTime.atZone(ZoneId.of(env.getConfig().timezoneId));
  }

  private Calendar getDate(String dateString) {
    if (!"unavailable".equalsIgnoreCase(dateString)) {
      try {
        Date date = new SimpleDateFormat(DATE_FORMAT).parse(dateString);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar;
      } catch (ParseException e) {
        env.logJobError("Failed to parse date from string {}!", dateString);
      }
    }
    return null;
  }

  private VirtuousClient.Contact asContact(CrmContact crmContact) {
    if (crmContact == null) {
      return null;
    }
    VirtuousClient.Contact contact = new VirtuousClient.Contact();
    if (!Strings.isNullOrEmpty(crmContact.id)) {
      contact.id = Integer.parseInt(crmContact.id);
    }
    contact.name = crmContact.getFullName();
    contact.isPrivate = false;
    contact.contactType =
        "Household"; // Foundation/Organization/Household ?

    contact.contactAddresses.add(asAddress(crmContact.mailingAddress));

    VirtuousClient.ContactIndividual contactIndividual = new VirtuousClient.ContactIndividual();
    contactIndividual.contactId = contact.id;
    contactIndividual.firstName = crmContact.firstName;
    contactIndividual.lastName = crmContact.lastName;
    contactIndividual.isPrimary = true;
    contactIndividual.isSecondary = false;
    contactIndividual.isDeceased = false;
    contactIndividual.contactMethods = Stream.of(
        contactMethod("Home Email", crmContact.email, true, Boolean.TRUE == crmContact.emailOptIn),
        contactMethod("Home Phone", crmContact.homePhone, crmContact.preferredPhone == CrmContact.PreferredPhone.HOME, false),
        contactMethod("Mobile Phone", crmContact.mobilePhone, crmContact.preferredPhone == CrmContact.PreferredPhone.MOBILE, false),
        contactMethod("Work Phone", crmContact.workPhone, crmContact.preferredPhone == CrmContact.PreferredPhone.WORK, false)
    ).filter(Objects::nonNull).collect(Collectors.toList());

    contact.contactIndividuals = List.of(contactIndividual);

    return contact;
  }

  private VirtuousClient.Address asAddress(CrmAddress crmAddress) {
    if (crmAddress == null) {
      return null;
    }
    VirtuousClient.Address address = new VirtuousClient.Address();
    address.country = crmAddress.country;
    address.state = crmAddress.state;
    address.city = crmAddress.city;
    address.postal = crmAddress.postalCode;
    address.address1 = crmAddress.street;
    address.isPrimary = true; // ?
    return address;
  }

  private VirtuousClient.ContactMethod contactMethod(String type, String value, boolean isPrimary, boolean isOptedIn) {
    if (Strings.isNullOrEmpty(value)) {
      return null;
    }
    VirtuousClient.ContactMethod contactMethod = new VirtuousClient.ContactMethod();
    contactMethod.type = type;
    contactMethod.value = value;
    contactMethod.isPrimary = isPrimary;
    contactMethod.isOptedIn = isOptedIn;
    return contactMethod;
  }

  private CrmDonation asCrmDonation(VirtuousClient.Gift gift) {
    if (gift == null) {
      return null;
    }
    CrmDonation crmDonation = new CrmDonation();
    crmDonation.id = gift.id + "";
    crmDonation.name = gift.transactionSource + "/" + gift.transactionId; //?
    crmDonation.amount = Double.parseDouble(gift.amount.replace("$", ""));
    crmDonation.gatewayName = gift.transactionSource; // ?
    // TODO: Need this so that DonationService doesn't flag it as a "non-posted state". But it doesn't look like
    //  Virtuous actually has a status to even have a failed state?
    crmDonation.status = CrmDonation.Status.SUCCESSFUL;
    crmDonation.closeDate = getDateTime(gift.giftDate);
    crmDonation.crmUrl = gift.giftUrl;
    crmDonation.crmRawObject = gift;
    return crmDonation;
  }

  private VirtuousClient.Gift asGift(CrmDonation crmDonation) {
    if (crmDonation == null) {
      return null;
    }
    VirtuousClient.Gift gift = new VirtuousClient.Gift();

    gift.contactId = crmDonation.contact.id;
    gift.giftType = "Credit";
    gift.giftDate = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT).format(crmDonation.closeDate);
    gift.amount = crmDonation.amount + "";
    gift.transactionSource = crmDonation.gatewayName;
    gift.transactionId = crmDonation.transactionId;
    gift.isPrivate = false;
    gift.isTaxDeductible = true;

    // assumed to be the unique code that the UI gives for a *segment*
    String segmentCode = crmDonation.getMetadataValue(env.getConfig().metadataKeys.campaign);
    if (!Strings.isNullOrEmpty(segmentCode)) {
      VirtuousClient.Segment segment = virtuousClient.getSegmentByCode(segmentCode);
      if (segment != null) {
        gift.segmentId = segment.id;
      }
    }

    return gift;
  }

  private Optional<CrmRecurringDonation> asCrmRecurringDonation(Optional<VirtuousClient.RecurringGift> recurringGift) {
    return recurringGift.map(this::asCrmRecurringDonation);
  }

  private CrmRecurringDonation asCrmRecurringDonation(VirtuousClient.RecurringGift recurringGift) {
    if (recurringGift == null) {
      return null;
    }

    CrmContact crmContact = new CrmContact(recurringGift.contactId + "");

    ZonedDateTime anticipatedEndDate = null;
    if (!Strings.isNullOrEmpty(recurringGift.anticipatedEndDate)) {
      anticipatedEndDate = getDateTime(recurringGift.anticipatedEndDate);
    }

    return new CrmRecurringDonation(
        recurringGift.id + "",
        null, // CrmAccount account,
        crmContact,
        !"Cancelled".equalsIgnoreCase(recurringGift.status),
        recurringGift.amount,
        null, // String customerId,
        null, // String description,
        null, // String donationName,
        CrmRecurringDonation.Frequency.fromName(recurringGift.frequency), // TODO: same values?
        recurringGift.paymentGatewayName(env),
        null, // String ownerId,
        recurringGift.status,
        null, // String subscriptionCurrency,
        recurringGift.paymentGatewaySubscriptionId(env),
        anticipatedEndDate,
        getDateTime(recurringGift.nextExpectedPaymentDate),
        getDateTime(recurringGift.startDate),
        recurringGift,
        "https://app.virtuoussoftware.com/Generosity/Contact/Gifts/" + recurringGift.contactId
    );
  }

  private VirtuousClient.RecurringGift asRecurringGift(CrmRecurringDonation recurringDonation) throws Exception {
    VirtuousClient.RecurringGift recurringGift = new VirtuousClient.RecurringGift();
    Optional<CrmContact> contact = getContactById(recurringDonation.contact.id);
    contact.ifPresent(c -> recurringGift.contactId = asContact(c).id);
    recurringGift.amount = recurringDonation.amount;
    if (recurringDonation.frequency == CrmRecurringDonation.Frequency.YEARLY) {
      recurringGift.frequency = "ANNUALLY";
    } else if (recurringDonation.frequency == CrmRecurringDonation.Frequency.BIANNUALLY) {
      recurringGift.frequency = "BIENNUALLY";
    } else {
      recurringGift.frequency = recurringDonation.frequency.toString();
    }
    recurringGift.startDate = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(recurringDonation.subscriptionStartDate);
    recurringGift.nextExpectedPaymentDate = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(recurringDonation.subscriptionStartDate);
    recurringGift.automatedPayments = true;
    recurringGift.trackPayments = true;
    recurringGift.isPrivate = false;

    EnvironmentConfig.VirtuousPlatform virtuousPlatform = env.getConfig().virtuous;
    if (!Strings.isNullOrEmpty(virtuousPlatform.fieldDefinitions.paymentGatewayName)) {
      VirtuousClient.CustomField gatewayName = new VirtuousClient.CustomField();
      gatewayName.name = virtuousPlatform.fieldDefinitions.paymentGatewayName;
      gatewayName.dataType = "Text";
      gatewayName.value = recurringDonation.gatewayName;
      recurringGift.customFields.add(gatewayName);
    }
    if (!Strings.isNullOrEmpty(virtuousPlatform.fieldDefinitions.paymentGatewaySubscriptionId)) {
      VirtuousClient.CustomField subscriptionId = new VirtuousClient.CustomField();
      subscriptionId.name = virtuousPlatform.fieldDefinitions.paymentGatewaySubscriptionId;
      subscriptionId.dataType = "Text";
      subscriptionId.value = recurringDonation.subscriptionId;
      recurringGift.customFields.add(subscriptionId);
    }

    return recurringGift;
  }
}
