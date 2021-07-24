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
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmCampaign;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.OpportunityEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.util.Utils;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class SfdcCrmService implements CrmService {

  private static final Logger log = LogManager.getLogger(SfdcCrmService.class);

  protected Environment env;
  protected SfdcClient sfdcClient;

  @Override
  public String name() { return "salesforce"; }

  @Override
  public void init(Environment env) {
    this.env = env;
    sfdcClient = env.sfdcClient();
  }

  @Override
  public Optional<CrmAccount> getAccountById(String id) throws Exception {
    // TODO
    return Optional.empty();
  }

  @Override
  public Optional<CrmContact> getContactById(String id) throws Exception {
    // TODO
    return Optional.empty();
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
  public List<CrmDonation> getLastMonthDonationsByAccountId(String accountId) throws Exception {
    // TODO
    return null;
  }

  @Override
  public List<CrmDonation> getDonationsByAccountId(String accountId) throws Exception {
    // TODO
    return null;
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    // TODO
    return Optional.empty();
  }

  @Override
  public List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception {
    // TODO
    return null;
  }

  @Override
  public Optional<CrmUser> getUserById(String id) throws Exception {
    // TODO
    return Optional.empty();
  }

  @Override
  public Optional<CrmDonation> getDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return toCrmDonation(sfdcClient.getDonationByTransactionId(paymentGatewayEvent.getTransactionId()));
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return toCrmRecurringDonation(sfdcClient.getRecurringDonationBySubscriptionId(paymentGatewayEvent.getSubscriptionId()));
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

  @Override
  public String insertAccount(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    SObject account = new SObject("Account");
    setAccountFields(account, paymentGatewayEvent.getCrmAccount());
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
    contact.setField("MobilePhone", crmContact.mobilePhone);

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
      PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    SObject opportunity = new SObject("Opportunity");

    opportunity.setField("AccountId", paymentGatewayEvent.getCrmAccount().id);
    // TODO: Shouldn't this be doing ContactId?
    opportunity.setField("Npe03__Recurring_Donation__c", recurringDonationId);

    setOpportunityFields(opportunity, campaign, paymentGatewayEvent);

    return sfdcClient.insert(opportunity).getId();
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
      opportunity.setField(env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDate, paymentGatewayEvent.getRefundDate());
    }
    // TODO: LJI/TER/DR specific? They all have it, but I can't remember if we explicitly added it.
    opportunity.setField("StageName", "Refunded");
  }

  @Override
  public void insertDonationDeposit(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
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

    // TODO: Assign to contact if available? Can only do one or the other -- see DR.
    recurringDonation.setField("Npe03__Organization__c", paymentGatewayEvent.getCrmAccount().id);
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
    // 701 is the Campaign ID prefix
    if (listId.startsWith("701")) {
      return toCrmContact(sfdcClient.getContactsByCampaignId(listId));
    }
    // otherwise, assume it's an explicit Opportunity name
    else {
      return toCrmContact(sfdcClient.getContactsByOpportunityName(listId));
    }
  }

  @Override
  public void removeContactFromList(CrmContact crmContact, String listId) throws Exception {
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

  @Override
  public void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception {
    List<SObject> contactUpdates = new ArrayList<>();
    List<SObject> accountUpdates = new ArrayList<>();
    List<SObject> oppUpdates = new ArrayList<>();

    for (int i = 0; i < updateEvents.size(); i++) {
      CrmUpdateEvent updateEvent = updateEvents.get(i);

      log.info("processing row {} of {}: {}", i + 2, updateEvents.size() + 1, updateEvent);

      if (!Strings.isNullOrEmpty(updateEvent.getContactId())) {
        Optional<SObject> existingContact = sfdcClient.getContactById(updateEvent.getContactId());
        log.info("found contact for id {}: {}", updateEvent.getContactId(), existingContact.isPresent());

        if (existingContact.isPresent()) {
          SObject contact = new SObject("Contact");
          contact.setId(updateEvent.getContactId());
          setBulkUpdateContactFields(contact, updateEvent);
          contactUpdates.add(contact);
        }
      }

      if (!Strings.isNullOrEmpty(updateEvent.getAccountId())) {
        Optional<SObject> existingAccount = sfdcClient.getAccountById(updateEvent.getAccountId());
        log.info("found account for id {}: {}", updateEvent.getAccountId(), existingAccount.isPresent());

        if (existingAccount.isPresent()) {
          SObject account = new SObject("Account");
          account.setId(updateEvent.getAccountId());
          setBulkUpdateAccountFields(account, updateEvent);
          accountUpdates.add(account);
        }
      }

      if (!Strings.isNullOrEmpty(updateEvent.getOpportunityId())) {
        Optional<SObject> existingOpp = sfdcClient.getDonationById(updateEvent.getOpportunityId());
        log.info("found opp for id {}: {}", updateEvent.getOpportunityId(), existingOpp.isPresent());

        if (existingOpp.isPresent()) {
          SObject opp = new SObject("Opportunity");
          opp.setId(updateEvent.getOpportunityId());
          setBulkUpdateOpportunityFields(opp, updateEvent);
          oppUpdates.add(opp);
        }
      }
    }

    sfdcClient.batchUpdate(contactUpdates.toArray());
    sfdcClient.batchUpdate(accountUpdates.toArray());
    sfdcClient.batchUpdate(oppUpdates.toArray());
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

  protected void setBulkUpdateContactFields(SObject contact, CrmUpdateEvent updateEvent) {
    setField(contact, "OwnerId", updateEvent.getOwnerId());
    setField(contact, "FirstName", updateEvent.getFirstName());
    setField(contact, "LastName", updateEvent.getLastName());
    setField(contact, "MailingStreet", updateEvent.getMailingStreet());
    setField(contact, "MailingCity", updateEvent.getMailingCity());
    setField(contact, "MailingState", updateEvent.getMailingState());
    setField(contact, "MailingPostalCode", updateEvent.getMailingZip());
    setField(contact, "MailingCountry", updateEvent.getMailingCountry());
    setField(contact, "HomePhone", updateEvent.getHomePhone());
    setField(contact, "MobilePhone", updateEvent.getMobilePhone());
    setField(contact, "Email", updateEvent.getEmail());
  }

  protected void setBulkUpdateAccountFields(SObject account, CrmUpdateEvent updateEvent) {
    setField(account, "OwnerId", updateEvent.getOwnerId());
    setField(account, "BillingStreet", updateEvent.getBillingStreet());
    setField(account, "BillingCity", updateEvent.getBillingCity());
    setField(account, "BillingState", updateEvent.getBillingState());
    setField(account, "BillingPostalCode", updateEvent.getBillingZip());
    setField(account, "BillingCountry", updateEvent.getBillingCountry());
  }

  protected void setBulkImportOpportunityFields(SObject opportunity, SObject contact,
      LoadingCache<String, Optional<SObject>> campaignCache, CrmImportEvent importEvent) throws ConnectionException, InterruptedException, ExecutionException {
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

  protected void setBulkUpdateOpportunityFields(SObject opportunity, CrmUpdateEvent updateEvent)
      throws ConnectionException, InterruptedException, ParseException, ExecutionException {
    setField(opportunity, "AccountId", updateEvent.getAccountId());
    setField(opportunity, "ContactId", updateEvent.getContactId());

    if (!Strings.isNullOrEmpty(updateEvent.getOpportunityRecordTypeId())) {
      setField(opportunity, "RecordTypeId", updateEvent.getOpportunityRecordTypeId());
    } else if (!Strings.isNullOrEmpty(updateEvent.getOpportunityRecordTypeName())) {
      SObject salesRecordType = sfdcClient.getRecordTypeByName(updateEvent.getOpportunityRecordTypeName()).get();
      setField(opportunity, "RecordTypeId", salesRecordType.getId());
    }
    setField(opportunity, "Name", updateEvent.getOpportunityName());
    setField(opportunity, "Description", updateEvent.getOpportunityDescription());
    setField(opportunity, "Amount", updateEvent.getOpportunityAmount().doubleValue());
    setField(opportunity, "StageName", updateEvent.getOpportunityStageName());
    setField(opportunity, "CloseDate", updateEvent.getOpportunityDate());
    setField(opportunity, "OwnerId", updateEvent.getOpportunityOwnerId());

    if (!Strings.isNullOrEmpty(updateEvent.getOpportunityCampaignId())) {
      setField(opportunity, "CampaignId", updateEvent.getOpportunityCampaignId());
    } else if (!Strings.isNullOrEmpty(updateEvent.getOpportunityCampaignExternalRef())) {
      Optional<SObject> campaign = sfdcClient.getCampaignById(updateEvent.getOpportunityCampaignExternalRef());
      campaign.ifPresent(c -> setField(opportunity, "CampaignId", c.getId()));
    }
  }

  protected Object getField(SObject sObject, String name) {
    // Optional field names may not be configured in env.json, so ensure we actually have a name first...
    if (Strings.isNullOrEmpty(name)) {
      return null;
    }

    return sObject.getField(name);
  }

  protected void setField(SObject sObject, String name, Object value) {
    // Optional field names may not be configured in env.json, so ensure we actually have a name first...
    // Likewise, don't set a null or empty value.
    if (!Strings.isNullOrEmpty(name) && value != null && !Strings.isNullOrEmpty(value.toString())) {
      sObject.setField(name, value);
    }
  }

  protected Optional<SObject> getCampaignOrDefault(PaymentGatewayEvent paymentGatewayEvent) throws ConnectionException, InterruptedException {
    String campaignId = paymentGatewayEvent.getMetadataValue(env.getConfig().metadataKeys.campaign);
    
    Optional<SObject> campaign = Strings.isNullOrEmpty(campaignId)
        ? Optional.empty() : sfdcClient.getCampaignById(campaignId);

    if (campaign.isEmpty()) {
      String defaultCampaignId = env.getConfig().salesforce.defaultCampaignId;
      if (Strings.isNullOrEmpty(defaultCampaignId)) {
        log.info("campaign {} not found, but no default provided", campaignId);
      } else {
        log.info("campaign {} not found; using default: {}", campaignId, defaultCampaignId);
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
    CrmAddress crmAddress = new CrmAddress(
        (String) sObject.getField("BillingStreet"),
        (String) sObject.getField("BillingCity"),
        (String) sObject.getField("BillingState"),
        (String) sObject.getField("BillingPostalCode"),
        (String) sObject.getField("BillingCountry")
    );

    CrmContact.PreferredPhone preferredPhone = null;
    if (sObject.getField("npe01__preferredphone__c") != null) {
      String s = (String) sObject.getField("npe01__preferredphone__c");
      preferredPhone = CrmContact.PreferredPhone.valueOf(s.toUpperCase(Locale.ROOT));
    }

    return new CrmContact(
        sObject.getId(),
        (String) sObject.getField("AccountId"),
        (String) sObject.getField("FirstName"),
        (String) sObject.getField("LastName"),
        (String) sObject.getField("Email"),
        (String) sObject.getField("HomePhone"),
        (String) sObject.getField("MobilePhone"),
        (String) sObject.getField("npe01__workphone__c"),
        preferredPhone,
        crmAddress,
        (Boolean) getField(sObject, env.getConfig().salesforce.fieldDefinitions.emailOptIn),
        (Boolean) getField(sObject, env.getConfig().salesforce.fieldDefinitions.emailOptOut),
        (Boolean) getField(sObject, env.getConfig().salesforce.fieldDefinitions.smsOptIn),
        (Boolean) getField(sObject, env.getConfig().salesforce.fieldDefinitions.smsOptOut),
        (String) sObject.getField("OwnerId")
    );
  }

  protected Optional<CrmContact> toCrmContact(Optional<SObject> sObject) {
    return sObject.map(this::toCrmContact);
  }

  protected List<CrmContact> toCrmContact(List<SObject> sObjects) {
    return sObjects.stream()
        .map(this::toCrmContact)
        .collect(Collectors.toList());
  }

  protected CrmDonation toCrmDonation(SObject sObject) {
    String id = sObject.getId();
    String paymentGatewayName = (String) getField(sObject, env.getConfig().salesforce.fieldDefinitions.paymentGatewayName);
    Double amount = Double.valueOf(sObject.getField("Amount").toString());
    Calendar closeDate = null;
    try {
      closeDate = Utils.getCalendarFromDateString(sObject.getField("CloseDate").toString());
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
    } else {
      status = CrmDonation.Status.PENDING;
    }

    return new CrmDonation(id, (String) sObject.getField("Name"), amount,
        paymentGatewayName, status, closeDate);
  }

  protected Optional<CrmDonation> toCrmDonation(Optional<SObject> sObject) {
    return sObject.map(this::toCrmDonation);
  }

  protected CrmRecurringDonation toCrmRecurringDonation(SObject sObject) {
    String id = sObject.getId();
    String subscriptionId = (String) getField(sObject, env.getConfig().salesforce.fieldDefinitions.paymentGatewaySubscriptionId);
    String customerId = (String) getField(sObject, env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId);
    String paymentGatewayName = (String) getField(sObject, env.getConfig().salesforce.fieldDefinitions.paymentGatewayName);
    Double amount = Double.parseDouble(sObject.getField("npe03__Amount__c").toString());
    boolean active = "Open".equalsIgnoreCase(sObject.getField("npe03__Open_Ended_Status__c").toString());
    CrmRecurringDonation.Frequency frequency = CrmRecurringDonation.Frequency.fromName(sObject.getField("npe03__Installment_Period__c").toString());
    return new CrmRecurringDonation(id, subscriptionId, customerId, amount, paymentGatewayName, active, frequency);
  }

  protected Optional<CrmRecurringDonation> toCrmRecurringDonation(Optional<SObject> sObject) {
    return sObject.map(this::toCrmRecurringDonation);
  }
}
