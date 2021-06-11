/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmCampaign;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.OpportunityEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.ParseException;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

public class SfdcCrmService implements CrmService {

  private static final Logger log = LogManager.getLogger(SfdcCrmService.class);

  protected final Environment env;
  protected final SfdcClient sfdcClient;

  public SfdcCrmService(Environment env) {
    this.env = env;
    sfdcClient = env.sfdcClient();
  }

  @Override
  public Optional<CrmContact> getContactByEmail(String email) throws Exception {
    return toCrmContact(sfdcClient.getContactByEmail(email));
  }

  @Override
  public Optional<CrmContact> getContactByPhone(String phone) throws Exception {
    List<SObject> contacts = sfdcClient.getContactsByPhone(phone);
    if (contacts.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(toCrmContact(contacts.get(0)));
  }

  @Override
  public Optional<CrmDonation> getDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    return toCrmDonation(sfdcClient.getDonationByTransactionId(paymentGatewayEvent.getTransactionId()));
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    return toCrmRecurringDonation(sfdcClient.getRecurringDonationBySubscriptionId(paymentGatewayEvent.getSubscriptionId()));
  }

  public Optional<CrmRecurringDonation> getRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    return toCrmRecurringDonation(sfdcClient.getRecurringDonationById(manageDonationEvent.getDonationId()));
  }

  @Override
  public String getSubscriptionId(ManageDonationEvent manageDonationEvent) throws ConnectionException, InterruptedException {
    return sfdcClient.getSubscriptionId(manageDonationEvent.getDonationId());
  }

  @Override
  public void insertDonationReattempt(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    CrmDonation existingDonation = getDonation(paymentGatewayEvent).get();

    SObject sObject = new SObject("Opportunity");
    sObject.setId(existingDonation.getId());

    // TODO: duplicates setOpportunityFields -- may need to rethink the breakdown
    if (existingDonation.isPosted()) {
      sObject.setField("StageName", "Posted");
    } else {
      sObject.setField("StageName", "Failed Attempt");
    }

    sfdcClient.update(sObject);
  }

  @Override
  public String insertAccount(PaymentGatewayWebhookEvent paymentGatewayWebhookEvent) throws Exception {
    SObject account = new SObject("Account");
    setAccountFields(account, paymentGatewayWebhookEvent.getCrmAccount());
    return sfdcClient.insert(account).getId();
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
  public String insertContact(PaymentGatewayWebhookEvent paymentGatewayWebhookEvent) throws Exception {
    SObject contact = new SObject("Contact");
    setContactFields(contact, paymentGatewayWebhookEvent.getCrmContact());
    return sfdcClient.insert(contact).getId();
  }

  @Override
  public String insertContact(OpportunityEvent opportunityEvent) throws Exception {
    SObject contact = new SObject("Contact");
    setContactFields(contact, opportunityEvent.getCrmContact());
    return sfdcClient.insert(contact).getId();
  }

  @Override
  public void updateContact(OpportunityEvent opportunityEvent) throws Exception {
    SObject contact = new SObject("Contact");
    contact.setId(opportunityEvent.getCrmContact().id);
    setContactFields(contact, opportunityEvent.getCrmContact());
    sfdcClient.update(contact);
  }

  @Override
  public void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception {
    SObject campaignMember = new SObject("CampaignMember");
    campaignMember.setField("ContactId", crmContact.id);
    campaignMember.setField("CampaignId", campaignId);
    // TODO: Necessary to set the contact's name and address? Hopefully SFDC does that automatically.
    sfdcClient.insert(campaignMember);
  }

  protected void setContactFields(SObject contact, CrmContact crmContact) {
    contact.setField("AccountId", crmContact.accountId);
    contact.setField("FirstName", crmContact.firstName);
    contact.setField("LastName", crmContact.lastName);
    contact.setField("Email", crmContact.email);
    contact.setField("MobilePhone", crmContact.phone);

    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.emailOptIn) && crmContact.emailOptIn != null && crmContact.emailOptIn) {
      contact.setField(env.getConfig().salesforce.fieldDefinitions.emailOptIn, crmContact.emailOptIn);
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.smsOptIn) && crmContact.smsOptIn != null && crmContact.smsOptIn) {
      contact.setField(env.getConfig().salesforce.fieldDefinitions.smsOptIn, crmContact.smsOptIn);
    }
  }

  @Override
  public String insertDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
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
      String recurringDonationId, PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    log.info("found SFDC pledged opportunity {} in recurring donation {}",
        pledgedOpportunity.getId(), recurringDonationId);

    // check to see if the recurring donation was a failed attempt or successful
    if (paymentGatewayEvent.isTransactionSuccess()) {
      // update existing pledged donation to "Posted"
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
      PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    SObject opportunity = new SObject("Opportunity");

    opportunity.setField("AccountId", paymentGatewayEvent.getCrmAccountId());
    // TODO: Shouldn't this be doing ContactId?
    opportunity.setField("Npe03__Recurring_Donation__c", recurringDonationId);

    setOpportunityFields(opportunity, campaign, paymentGatewayEvent);

    return sfdcClient.insert(opportunity).getId();
  }

  protected void setOpportunityFields(SObject opportunity, Optional<SObject> campaign, PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayName)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayName, paymentGatewayEvent.getGatewayName());
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayTransactionId)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayTransactionId, paymentGatewayEvent.getTransactionId());
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId, paymentGatewayEvent.getCustomerId());
    }

    // check to see if this was a failed payment attempt and set the StageName accordingly
    if (paymentGatewayEvent.isTransactionSuccess()) {
      // TODO: If LJI/TER end up being the only ones using this, default it to Closed Won
      opportunity.setField("StageName", "Posted");
    } else {
      opportunity.setField("StageName", "Failed Attempt");
    }

    opportunity.setField("Amount", paymentGatewayEvent.getTransactionAmountInDollars());
    opportunity.setField("CampaignId", campaign.map(SObject::getId).orElse(null));
    opportunity.setField("CloseDate", paymentGatewayEvent.getTransactionDate());
    opportunity.setField("Description", paymentGatewayEvent.getTransactionDescription());

    // purely a default, but we generally expect this to be overridden
    opportunity.setField("Name", paymentGatewayEvent.getCrmAccount().name + " Donation");
  }

  @Override
  public void refundDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> donation = getDonation(paymentGatewayEvent);

    if (donation.isEmpty()) {
      log.warn("unable to find SFDC donation using transaction {}", paymentGatewayEvent.getTransactionId());
      return;
    }

    SObject opportunity = new SObject("Opportunity");
    opportunity.setId(donation.get().getId());
    setOpportunityRefundFields(opportunity, paymentGatewayEvent);

    sfdcClient.update(opportunity);
  }

  protected void setOpportunityRefundFields(SObject opportunity, PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundId)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundId, paymentGatewayEvent.getRefundId());
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDate)) {
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDate, paymentGatewayEvent.getRefundDate());
    }
    // TODO: LJI/TER/DR specific? They all have it, but I can't remember if we explicitly added it.
    opportunity.setField("StageName", "Refunded");
  }

  @Override
  public void insertDonationDeposit(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    // TODO: Might be helpful to do something like this further upstream, preventing unnecessary processing
    Optional<SObject> opportunity = sfdcClient.getDonationByTransactionId(paymentGatewayEvent.getTransactionId());
    if (opportunity.isPresent()) {
      // Only do this if the field definitions are given in env.json, otherwise assume this method will be overridden.
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositId)
          && opportunity.get().getField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositId) == null) {
        SObject opportunityUpdate = new SObject("Opportunity");
        opportunityUpdate.setId(opportunity.get().getId());
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositDate, paymentGatewayEvent.getDepositDate());
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositId, paymentGatewayEvent.getDepositId());
        opportunityUpdate.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositNetAmount, paymentGatewayEvent.getTransactionNetAmountInDollars());
        sfdcClient.update(opportunityUpdate);
      } else {
        log.info("skipping {}; already marked with deposit info", opportunity.get().getId());
      }
    }
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    SObject recurringDonation = new SObject("Npe03__Recurring_Donation__c");
    setRecurringDonationFields(recurringDonation, getCampaignOrDefault(paymentGatewayEvent), paymentGatewayEvent);
    return sfdcClient.insert(recurringDonation).getId();
  }

  /**
   * Set any necessary fields on an RD before it's inserted.
   */
  protected void setRecurringDonationFields(SObject recurringDonation, Optional<SObject> campaign, PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayName)) {
      recurringDonation.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayName, paymentGatewayEvent.getGatewayName());
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewaySubscriptionId)) {
      recurringDonation.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewaySubscriptionId, paymentGatewayEvent.getSubscriptionId());
    }
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId)) {
      recurringDonation.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId, paymentGatewayEvent.getCustomerId());
    }

    // TODO: Assign to contact if available? Can only do one or the other -- see DR.
    recurringDonation.setField("Npe03__Organization__c", paymentGatewayEvent.getCrmAccountId());
    recurringDonation.setField("Npe03__Amount__c", paymentGatewayEvent.getSubscriptionAmountInDollars());
    recurringDonation.setField("Npe03__Open_Ended_Status__c", "Open");
    recurringDonation.setField("Npe03__Schedule_Type__c", "Multiply By");
//    recurringDonation.setDonation_Method__c(paymentGatewayEvent.getDonationMethod());
    recurringDonation.setField("Npe03__Installment_Period__c", paymentGatewayEvent.getSubscriptionInterval());
    recurringDonation.setField("Npe03__Date_Established__c", paymentGatewayEvent.getSubscriptionStartDate());
    recurringDonation.setField("Npe03__Next_Payment_Date__c", paymentGatewayEvent.getSubscriptionNextDate());
    recurringDonation.setField("Npe03__Recurring_Donation_Campaign__c", getCampaignOrDefault(paymentGatewayEvent).map(SObject::getId).orElse(null));

    // Purely a default, but we expect this to be generally overridden.
    recurringDonation.setField("Name", paymentGatewayEvent.getCrmAccount().name + " Recurring Donation");
  }

  @Override
  public void closeRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = getRecurringDonation(paymentGatewayEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find SFDC recurring donation using subscriptionId {}",
          paymentGatewayEvent.getSubscriptionId());
      return;
    }

    SObject toUpdate = new SObject("Npe03__Recurring_Donation__c");
    toUpdate.setId(recurringDonation.get().id());
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
    toUpdate.setId(recurringDonation.get().id());
    toUpdate.setField("Npe03__Open_Ended_Status__c", "Closed");
    setRecurringDonationFieldsForClose(toUpdate, manageDonationEvent);
    sfdcClient.update(toUpdate);
  }

  // Give orgs an opportunity to clear anything else out that's unique to them, prior to the update
  protected void setRecurringDonationFieldsForClose(SObject recurringDonation,
      PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
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
    return sfdcClient.insert(opportunity).getId();
  }

  @Override
  public void processImport(List<CrmImportEvent> importEvents) throws Exception {
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

    for (int i = 0; i < importEvents.size(); i++) {
      CrmImportEvent importEvent = importEvents.get(i);

      log.info("processing row {} of {}: {}", i + 2, importEvents.size() + 1, importEvent);

      String email = importEvent.getEmail();
      String firstName = importEvent.getFirstName();
      String lastName = importEvent.getLastName();

      // First try to get contact by email
      Optional<SObject> existingContact = sfdcClient.getContactByEmail(email);
      log.info("found contact for email {}: {}", email, existingContact.isPresent());

      // If none by email, get contact by name
      if (existingContact.isEmpty()) {
        List<SObject> existingContacts = sfdcClient.getContactsByName(firstName, lastName, importEvent.getRaw());

        log.info("number of contacts for name {} {}: {}", firstName, lastName, existingContacts.size());

        if (existingContacts.size() > 1) {
          // To be safe, let's skip this row for now and deal with it manually...
          log.warn("SKIPPING row due to multiple contacts found!");
          break;
        } else {
          existingContact = existingContacts.stream().findFirst();
        }
      }

      SObject contact;
      if (existingContact.isEmpty()) {
        // If still can't find contact, create new one
        contact = new SObject("Contact");
        setBulkImportContactFields(contact, importEvent);
        String contactId = sfdcClient.insert(contact).getId();
        // retrieve the contact again, fetching the accountId (and contactId) that was auto created
        contact = sfdcClient.getContactById(contactId).get();
      } else {
        contact = existingContact.get();
      }

      SObject opportunity = new SObject("Opportunity");
      setBulkImportOpportunityFields(opportunity, contact, campaignCache, importEvent);
      sfdcClient.insert(opportunity).getId();
    }
  }

  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = getRecurringDonation(manageDonationEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find SFDC recurring donation using donationId {}", manageDonationEvent.getDonationId());
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

  protected void setBulkImportContactFields(SObject contact, CrmImportEvent importEvent) {
    contact.setField("OwnerId", importEvent.getOwnerId());
    contact.setField("FirstName", importEvent.getFirstName());
    contact.setField("LastName", importEvent.getLastName());
    contact.setField("MailingStreet", importEvent.getStreet());
    contact.setField("MailingCity", importEvent.getCity());
    contact.setField("MailingState", importEvent.getState());
    contact.setField("MailingPostalCode", importEvent.getZip());
    contact.setField("MailingCountry", importEvent.getCountry());
    contact.setField("HomePhone", importEvent.getHomePhone());
    contact.setField("MobilePhone", importEvent.getMobilePhone());
    contact.setField("Email", importEvent.getEmail());
  }

  protected void setBulkImportOpportunityFields(SObject opportunity, SObject contact,
      LoadingCache<String, Optional<SObject>> campaignCache, CrmImportEvent importEvent) throws ConnectionException, InterruptedException, ParseException, ExecutionException {
    opportunity.setField("AccountId", contact.getField("AccountId"));
    opportunity.setField("ContactId", contact.getId());
    if (!Strings.isNullOrEmpty(importEvent.getOpportunityRecordTypeId())) {
      opportunity.setField("RecordTypeId", importEvent.getOpportunityRecordTypeId());
    } else if (!Strings.isNullOrEmpty(importEvent.getOpportunityRecordTypeName())) {
      SObject salesRecordType = sfdcClient.getRecordTypeByName(importEvent.getOpportunityRecordTypeName()).get();
      opportunity.setField("RecordTypeId", salesRecordType.getId());
    }
    opportunity.setField("Name", importEvent.getOpportunityName());
    opportunity.setField("Description", importEvent.getOpportunityDescription());
    opportunity.setField("Amount", importEvent.getOpportunityAmount().doubleValue());
    opportunity.setField("StageName", importEvent.getOpportunityStageName());
    opportunity.setField("CloseDate", importEvent.getOpportunityDate());

    if (!Strings.isNullOrEmpty(importEvent.getOpportunityCampaignId())) {
      opportunity.setField("CampaignId", importEvent.getOpportunityCampaignId());
    } else if (!Strings.isNullOrEmpty(importEvent.getOpportunityCampaignExternalRef())) {
      Optional<SObject> campaign = sfdcClient.getCampaignById(importEvent.getOpportunityCampaignExternalRef());
      campaign.ifPresent(c -> opportunity.setField("CampaignId", c.getId()));
    }

    // use the campaign owner if present, but fall back to the sheet otherwise
    String campaignId = opportunity.getField("CampaignId") == null ? null : opportunity.getField("CampaignId").toString();
    if (!Strings.isNullOrEmpty(campaignId)) {
      Optional<SObject> campaign = campaignCache.get(campaignId);
      campaign.ifPresent(c -> opportunity.setField("OwnerId", c.getField("OwnerId").toString()));
    }
    String ownerId = opportunity.getField("OwnerId") == null ? null : opportunity.getField("OwnerId").toString();
    if (Strings.isNullOrEmpty(ownerId)) {
      opportunity.setField("OwnerId", importEvent.getOpportunityOwnerId());
    }
  }

  protected Optional<SObject> getCampaignOrDefault(PaymentGatewayWebhookEvent paymentGatewayEvent) throws ConnectionException, InterruptedException {
    Optional<SObject> campaign = Strings.isNullOrEmpty(paymentGatewayEvent.getCampaignId())
        ? Optional.empty() : sfdcClient.getCampaignById(paymentGatewayEvent.getCampaignId());

    if (campaign.isEmpty()) {
      String defaultCampaignId = env.getConfig().salesforce.defaultCampaignId;
      if (Strings.isNullOrEmpty(defaultCampaignId)) {
        log.info("campaign {} not found, but no default provided", paymentGatewayEvent.getCampaignId());
      } else {
        log.info("campaign {} not found; using default: {}", paymentGatewayEvent.getCampaignId(), defaultCampaignId);
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

  protected CrmContact toCrmContact(SObject sObject) {
    // TODO: likely enough, but may need the rest of the fields
    CrmContact crmContact = new CrmContact();
    crmContact.id = sObject.getId();
    crmContact.accountId = sObject.getField("AccountId").toString();
    if (sObject.getField("FirstName") != null) {
      crmContact.firstName = sObject.getField("FirstName").toString();
    }
    if (sObject.getField("LastName") != null) {
      crmContact.lastName = sObject.getField("LastName").toString();
    }
    if (sObject.getField("Email") != null) {
      crmContact.email = sObject.getField("Email").toString();
    }
    return crmContact;
  }

  protected Optional<CrmContact> toCrmContact(Optional<SObject> sObject) {
    return sObject.map(this::toCrmContact);
  }

  protected CrmDonation toCrmDonation(SObject sObject) {
    String id = sObject.getId();

    // TODO: yuck -- allow subclasses to more easily define custom mappers?
    Object statusO = sObject.getField("StageName");
    String status = statusO == null ? null : statusO.toString();
    boolean successful = "Posted".equalsIgnoreCase(status) || "Closed Won".equalsIgnoreCase(status);

    return new CrmDonation(id, successful);
  }

  protected Optional<CrmDonation> toCrmDonation(Optional<SObject> sObject) {
    return sObject.map(this::toCrmDonation);
  }

  protected CrmRecurringDonation toCrmRecurringDonation(SObject sObject) {
    String id = sObject.getId();
    String accountId = (String) sObject.getField("npe03__Organization__c");
    String subscriptionId = (String) sObject.getField(env.getConfig().salesforce.fieldDefinitions.paymentGatewaySubscriptionId);
    Double amount = Double.parseDouble(sObject.getField("npe03__Amount__c").toString());
    return new CrmRecurringDonation(id, accountId, subscriptionId, amount);
  }

  protected Optional<CrmRecurringDonation> toCrmRecurringDonation(Optional<SObject> sObject) {
    return sObject.map(this::toCrmRecurringDonation);
  }
}
