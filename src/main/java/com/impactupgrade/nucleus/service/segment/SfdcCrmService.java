package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CRMImportEvent;
import com.impactupgrade.nucleus.model.CrmCampaign;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.MessagingWebhookEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.ParseException;
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
  public void updateDonation(CrmDonation donation) throws Exception {
    SObject sObject = new SObject("Opportunity");
    sObject.setId(donation.getId());

    // TODO: duplicates setOpportunityFields -- may need to rethink the breakdown
    if (donation.isSuccessful()) {
      // TODO: If LJI/TER end up being the only ones using this, default it to Closed Won
      sObject.setField("StageName", "Posted");
    } else {
      sObject.setField("StageName", "Failed Attempt");
    }

    sfdcClient.update(sObject);
  }

  @Override
  public String insertAccount(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    SObject account = new SObject("Account");
    setAccountFields(account, paymentGatewayEvent);
    return sfdcClient.insert(account).getId();
  }

  protected void setAccountFields(SObject account, PaymentGatewayWebhookEvent paymentGatewayEvent) {
    account.setField("Name", paymentGatewayEvent.getFullName());

    account.setField("BillingStreet", paymentGatewayEvent.getStreet());
    account.setField("BillingCity", paymentGatewayEvent.getCity());
    account.setField("BillingState", paymentGatewayEvent.getState());
    account.setField("BillingPostalCode", paymentGatewayEvent.getZip());
    account.setField("BillingCountry", paymentGatewayEvent.getCountry());
  }

  @Override
  public String insertContact(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    SObject contact = new SObject("Contact");
    setContactFields(contact, paymentGatewayEvent);
    return sfdcClient.insert(contact).getId();
  }

  protected void setContactFields(SObject contact, PaymentGatewayWebhookEvent paymentGatewayEvent) {
    contact.setField("AccountId", paymentGatewayEvent.getPrimaryCrmAccountId());
    contact.setField("FirstName", paymentGatewayEvent.getFirstName());
    contact.setField("LastName", paymentGatewayEvent.getLastName());
    contact.setField("Email", paymentGatewayEvent.getEmail());
    contact.setField("MobilePhone", paymentGatewayEvent.getPhone());
  }

  @Override
  public String insertDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Optional<SObject> campaign = getCampaignOrDefault(paymentGatewayEvent);
    String recurringDonationId = paymentGatewayEvent.getPrimaryCrmRecurringDonationId();

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

    opportunity.setField("AccountId", paymentGatewayEvent.getPrimaryCrmAccountId());
    // TODO: Shouldn't this be doing ContactId?
    opportunity.setField("Npe03__Recurring_Donation__c", recurringDonationId);

    setOpportunityFields(opportunity, campaign, paymentGatewayEvent);

    return sfdcClient.insert(opportunity).getId();
  }

  protected void setOpportunityFields(SObject opportunity, Optional<SObject> campaign, PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
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
    opportunity.setField("Name", paymentGatewayEvent.getFullName() + " Donation");
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
    if (!Strings.isNullOrEmpty(env.config().salesforce.fields.paymentGatewayRefundId)) {
      opportunity.setField(env.config().salesforce.fields.paymentGatewayRefundId, paymentGatewayEvent.getRefundId());
    }
    // TODO: LJI/TER/DR specific? They all have it, but I can't remember if we explicitly added it.
    opportunity.setField("StageName", "Refunded");
  }

  @Override
  public void insertDonationDeposit(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    // TODO: Might be helpful to do something like this further upstream, preventing unnecessary processing in hub-common
    Optional<SObject> opportunity = sfdcClient.getDonationByTransactionId(paymentGatewayEvent.getTransactionId());
    if (opportunity.isPresent()) {
      if (!Strings.isNullOrEmpty(env.config().salesforce.fields.paymentGatewayDepositId)
          && opportunity.get().getField(env.config().salesforce.fields.paymentGatewayDepositId) == null) {
        SObject opportunityUpdate = new SObject("Opportunity");
        opportunityUpdate.setId(opportunity.get().getId());
        opportunityUpdate.setField(env.config().salesforce.fields.paymentGatewayDepositDate, paymentGatewayEvent.getDepositDate());
        opportunityUpdate.setField(env.config().salesforce.fields.paymentGatewayDepositId, paymentGatewayEvent.getDepositId());
        opportunityUpdate.setField(env.config().salesforce.fields.paymentGatewayDepositNetAmount, paymentGatewayEvent.getTransactionNetAmountInDollars());
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
    // TODO: Assign to contact if available? Can only do one or the other -- see DR.
    recurringDonation.setField("Npe03__Organization__c", paymentGatewayEvent.getPrimaryCrmAccountId());
    recurringDonation.setField("Npe03__Amount__c", paymentGatewayEvent.getSubscriptionAmountInDollars());
    recurringDonation.setField("Npe03__Open_Ended_Status__c", "Open");
    recurringDonation.setField("Npe03__Schedule_Type__c", "Multiply By");
//    recurringDonation.setDonation_Method__c(paymentGatewayEvent.getDonationMethod());
    recurringDonation.setField("Npe03__Installment_Period__c", paymentGatewayEvent.getSubscriptionInterval());
    recurringDonation.setField("Npe03__Date_Established__c", paymentGatewayEvent.getSubscriptionStartDate());
    recurringDonation.setField("Npe03__Next_Payment_Date__c", paymentGatewayEvent.getSubscriptionNextDate());
    recurringDonation.setField("Npe03__Recurring_Donation_Campaign__c", getCampaignOrDefault(paymentGatewayEvent).map(SObject::getId).orElse(null));

    // Purely a default, but we expect this to be generally overridden.
    recurringDonation.setField("Name", paymentGatewayEvent.getFullName() + " Recurring Donation");
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

  // Give orgs an opportunity to clear anything else out that's unique to them, prior to the update
  protected void setRecurringDonationFieldsForClose(SObject recurringDonation,
      PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
  }

  @Override
  public String insertContact(MessagingWebhookEvent messagingWebhookEvent) throws Exception {
    SObject contact = new SObject("Contact");
    contact.setField("FirstName", messagingWebhookEvent.getFirstName());
    contact.setField("LastName", messagingWebhookEvent.getLastName());
    contact.setField("Email", messagingWebhookEvent.getEmail());
    contact.setField("MobilePhone", messagingWebhookEvent.getPhone());
    return sfdcClient.insert(contact).getId();
  }

  @Override
  public void smsSignup(MessagingWebhookEvent messagingWebhookEvent) throws Exception {
    // TODO: Different for every org, so allow it to be overridden. But, we should start shifting all this to env.json
  }

  @Override
  public void processImport(List<CRMImportEvent> importEvents) throws Exception {
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
      CRMImportEvent importEvent = importEvents.get(i);

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
    }
    sfdcClient.update(toUpdate);
  }

  protected void setBulkImportContactFields(SObject contact, CRMImportEvent importEvent) {
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
      LoadingCache<String, Optional<SObject>> campaignCache, CRMImportEvent importEvent) throws ConnectionException, InterruptedException, ParseException, ExecutionException {
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
      String defaultCampaignId = env.config().salesforce.defaultCampaignId;
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

  protected CrmContact toCrmContact(SObject sObject) {
    String id = sObject.getId();
    String accountId = sObject.getField("AccountId").toString();
    return new CrmContact(id, accountId);
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
    String subscriptionId = (String) sObject.getField(env.config().salesforce.fields.paymentGatewaySubscriptionId);
    Double amount = Double.parseDouble(sObject.getField("npe03__Amount__c").toString());
    return new CrmRecurringDonation(id, accountId, subscriptionId, amount);
  }

  protected Optional<CrmRecurringDonation> toCrmRecurringDonation(Optional<SObject> sObject) {
    return sObject.map(this::toCrmRecurringDonation);
  }
}
