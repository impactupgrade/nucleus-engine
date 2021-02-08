package com.impactupgrade.common.crm.sfdc;

import com.google.common.base.Strings;
import com.impactupgrade.common.crm.CrmDestinationService;
import com.impactupgrade.common.crm.CrmSourceService;
import com.impactupgrade.common.crm.model.CrmCampaign;
import com.impactupgrade.common.crm.model.CrmContact;
import com.impactupgrade.common.crm.model.CrmDonation;
import com.impactupgrade.common.crm.model.CrmRecurringDonation;
import com.impactupgrade.common.environment.Environment;
import com.impactupgrade.common.exception.NotImplementedException;
import com.impactupgrade.common.paymentgateway.model.PaymentGatewayEvent;
import com.neovisionaries.i18n.CountryCode;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class SfdcCrmService implements CrmSourceService, CrmDestinationService {

  private static final Logger log = LogManager.getLogger(SfdcCrmService.class);

  // TODO
  private static final String RECORD_TYPE_ACCOUNT_HOUSEHOLD = "012f4000000ZcEiAAK";
  private static final String RECORD_TYPE_CONTACT_STANDARD = "012f4000000a6v0AAA";

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
  public Optional<CrmDonation> getDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return toCrmDonation(sfdcClient.getDonationByTransactionId(paymentGatewayEvent.getTransactionId()));
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(String recurringDonationId) throws Exception {
    return toCrmRecurringDonation(sfdcClient.getRecurringDonationById(recurringDonationId));
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return toCrmRecurringDonation(sfdcClient.getRecurringDonationBySubscriptionId(paymentGatewayEvent.getSubscriptionId()));
  }

  @Override
  public String insertAccount(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    SObject account = new SObject("Account");
    account.setField("RecordTypeId", RECORD_TYPE_ACCOUNT_HOUSEHOLD);

    account.setField("Name", paymentGatewayEvent.getFullName());

    // NOTE: Some orgs have country/state picklists enabled, but the state picklists are sparsely populated (and often
    // incorrect) for non-US countries. TER does not currently mail anything to international donors, so we're
    // opting to fill out the state only for stateside gifts. However, we'll fill out the account description
    // with the raw state, in case we need it later
    // TODO: Need to allow orgs to enable the picklist assumptions?

    if ("US".equalsIgnoreCase(paymentGatewayEvent.getCountry())
        || "USA".equalsIgnoreCase(paymentGatewayEvent.getCountry())
        || "United States".equalsIgnoreCase(paymentGatewayEvent.getCountry())
        || "United States of America".equalsIgnoreCase(paymentGatewayEvent.getCountry())
        || "America".equalsIgnoreCase(paymentGatewayEvent.getCountry())
        || Strings.isNullOrEmpty(paymentGatewayEvent.getCountry())) {

      account.setField("BillingStreet", paymentGatewayEvent.getStreet());
      account.setField("BillingCity", paymentGatewayEvent.getCity());
      account.setField("BillingPostalCode", paymentGatewayEvent.getZip());

      boolean isStateCode = false;
      if (!Strings.isNullOrEmpty(paymentGatewayEvent.getState())) {
        if (paymentGatewayEvent.getState().length() == 2) {
          isStateCode = true;
          account.setField("BillingStateCode", paymentGatewayEvent.getState());
        } else {
          account.setField("BillingState", paymentGatewayEvent.getState());
        }
      }

      // If we're using a state code, we *must* also use the country code!

      if (isStateCode) {
        account.setField("BillingCountryCode", "US");
      } else {
        account.setField("BillingCountry", "United States");
      }
    } else {
      account.setField("BillingStreet", paymentGatewayEvent.getStreet());
      account.setField("BillingCity", paymentGatewayEvent.getCity());
      account.setField("BillingPostalCode", paymentGatewayEvent.getZip());
      if (paymentGatewayEvent.getCountry().length() == 2) {
        // was a 2-char country code to begin with; use verbatim
        account.setField("BillingCountryCode", paymentGatewayEvent.getCountry());
      } else if (paymentGatewayEvent.getCountry().length() == 3) {
        // was a 3-char country code, but Salesforce is currently tripping on them -- convert to 2-char
        account.setField("BillingCountryCode", CountryCode.getByCode(paymentGatewayEvent.getCountry()).getAlpha2());
      } else {
        // not a code, so use the open-ended flavor
        account.setField("BillingCountry", paymentGatewayEvent.getCountry());
      }
      account.setField("Description", "Non-US State: " + paymentGatewayEvent.getState());
    }

    return sfdcClient.insert(account).getId();
  }

  @Override
  public String insertContact(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    SObject contact = new SObject("Contact");
    contact.setField("RecordTypeId", RECORD_TYPE_CONTACT_STANDARD);
    contact.setField("AccountId", paymentGatewayEvent.getPrimaryCrmAccountId());
    contact.setField("FirstName", paymentGatewayEvent.getFirstName());
    contact.setField("LastName", paymentGatewayEvent.getLastName());
    contact.setField("Email", paymentGatewayEvent.getEmail());
    contact.setField("MobilePhone", paymentGatewayEvent.getPhone());

    return sfdcClient.insert(contact).getId();
  }

  @Override
  public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<SObject> campaign = getCampaignOrDefault(paymentGatewayEvent);
    String recurringDonationId = paymentGatewayEvent.getPrimaryCrmRecurringDonationId();

    if (!Strings.isNullOrEmpty(recurringDonationId)) {
      // get the next pledged donation from the recurring donation
      Optional<SObject> pledgedOpportunityO = sfdcClient.getNextPledgedDonationByRecurringDonationId(recurringDonationId);
      if (pledgedOpportunityO.isPresent()) {
        SObject pledgedOpportunity = pledgedOpportunityO.get();
        log.info("found SFDC pledged opportunity {} in recurring donation {}",
            pledgedOpportunity.getId(), recurringDonationId);

        // check to see if amount changed
        Double amount = Double.valueOf(pledgedOpportunity.getField("Amount").toString());
        boolean amountChanged = !amount.equals(paymentGatewayEvent.getTransactionAmountInDollars());
        // check to see if campaign changed
        String currentCampaignId = pledgedOpportunity.getField("CampaignId") == null ? null : pledgedOpportunity.getField("CampaignId").toString();
        boolean campaignChanged = campaign.isPresent() && !campaign.get().getId().equals(currentCampaignId);
        // check to see if the amount or campaign changed and update the Recurring Donation object accordingly
        // note that this changes future pledged donations, etc. -- useful for subscription or exchange rate changes
        if (amountChanged || campaignChanged) {
          log.info("updating recurring donation due to the following on the pledged donation: amountChanged={} campaignChanged={}",
              amountChanged, campaignChanged);
          SObject recurringDonation = new SObject("Npe03__Recurring_Donation__c");
          recurringDonation.setId(recurringDonationId);
          recurringDonation.setField("Npe03__Amount__c", paymentGatewayEvent.getTransactionAmountInDollars());
          setRecurringDonationFieldsFromCampaignChange(recurringDonation, campaign, paymentGatewayEvent);
          sfdcClient.update(recurringDonation);
        }

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
          pledgedOpportunity.setId(null);
          setOpportunityFields(pledgedOpportunity, campaign, paymentGatewayEvent);
          return sfdcClient.insert(pledgedOpportunity).getId();
        }
      } else {
        log.warn("unable to find SFDC pledged donation for recurring donation {} that isn't in the future",
            recurringDonationId);
      }
    }

    // not a recurring donation, OR an existing pledged donation didn't exist -- create a new donation

    SObject opportunity = new SObject("Opportunity");

    opportunity.setField("AccountId", paymentGatewayEvent.getPrimaryCrmAccountId());
    // TODO: Shouldn't this be doing ContactId?
    opportunity.setField("Npe03__Recurring_Donation__c", recurringDonationId);

    setOpportunityFields(opportunity, campaign, paymentGatewayEvent);

    return sfdcClient.insert(opportunity).getId();
  }

  protected void setOpportunityFields(SObject opportunity, Optional<SObject> campaign, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // check to see if this was a failed payment attempt and set the StageName accordingly
    if (paymentGatewayEvent.isTransactionSuccess()) {
      // TODO: If LJI/TER end up being the only ones using this, default it to Closed Won
      opportunity.setField("StageName", "Posted");
    } else {
      opportunity.setField("StageName", "Failed Attempt");
    }

    opportunity.setField("Amount", paymentGatewayEvent.getTransactionAmountInDollars());
    opportunity.setField("CampaignId", getCampaignOrDefault(paymentGatewayEvent).map(SObject::getId).orElse(null));
    opportunity.setField("CloseDate", paymentGatewayEvent.getTransactionDate());
    opportunity.setField("Description", paymentGatewayEvent.getTransactionDescription());
    opportunity.setField("CurrencyIsoCode", paymentGatewayEvent.getTransactionOriginalCurrency());

    // purely a default, but we generally expect this to be overridden
    opportunity.setField("Name", paymentGatewayEvent.getFullName() + " Donation");
  }

  @Override
  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> donation = getDonation(paymentGatewayEvent);

    if (donation.isEmpty()) {
      log.warn("unable to find SFDC donation using transaction {}", paymentGatewayEvent.getTransactionId());
      return;
    }

    SObject opportunity = new SObject("Opportunity");
    opportunity.setId(donation.get().id());
    setOpportunityRefundFields(opportunity, paymentGatewayEvent);

    sfdcClient.update(opportunity);
  }

  protected void setOpportunityRefundFields(SObject opportunity, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: LJI/TER/DR specific? They all have it, but I can't remember if we explicitly added it.
    opportunity.setField("StageName", "Refunded");
  }

  @Override
  public void insertDonationDeposit(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: This one tends to be super org-specific, but maybe there's a sensible default...
    throw new NotImplementedException(getClass().getSimpleName() + "." + "insertDonationDeposit");
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
    recurringDonation.setField("CurrencyIsoCode", paymentGatewayEvent.getSubscriptionCurrency());

    // Purely a default, but we expect this to be generally overridden.
    recurringDonation.setField("Name", paymentGatewayEvent.getFullName() + " Recurring Donation");
  }

  /**
   * If an RD's campaign is changed (typically due to new metadata on a payment gateway subscription), give an
   * opportunity to change specific fields before updating the RD.
   */
  protected void setRecurringDonationFieldsFromCampaignChange(SObject recurringDonation, Optional<SObject> campaign,
      PaymentGatewayEvent paymentGatewayEvent) throws Exception {

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
    toUpdate.setId(recurringDonation.get().id());
    toUpdate.setField("Npe03__Open_Ended_Status__c", "Closed");
    sfdcClient.update(toUpdate);
  }

  protected Optional<SObject> getCampaignOrDefault(PaymentGatewayEvent paymentGatewayEvent) throws ConnectionException, InterruptedException {
    Optional<SObject> campaign = sfdcClient.getCampaignById(paymentGatewayEvent.getCampaignId());

    if (campaign.isEmpty()) {
      String defaultCampaignId = getDefaultCampaignId();
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
    return new CrmDonation(id);
  }

  protected Optional<CrmDonation> toCrmDonation(Optional<SObject> sObject) {
    return sObject.map(this::toCrmDonation);
  }

  protected CrmRecurringDonation toCrmRecurringDonation(SObject sObject) {
    String id = sObject.getId();
    return new CrmRecurringDonation(id);
  }

  protected Optional<CrmRecurringDonation> toCrmRecurringDonation(Optional<SObject> sObject) {
    return sObject.map(this::toCrmRecurringDonation);
  }

  protected String getDefaultCampaignId() {
    return null;
  }
}
