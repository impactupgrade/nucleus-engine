package com.impactupgrade.common.crm.sfdc;

import com.google.common.base.Strings;
import com.impactupgrade.common.crm.CrmDestinationService;
import com.impactupgrade.common.crm.CrmSourceService;
import com.impactupgrade.common.crm.model.CrmCampaign;
import com.impactupgrade.common.crm.model.CrmContact;
import com.impactupgrade.common.crm.model.CrmDonation;
import com.impactupgrade.common.crm.model.CrmRecurringDonation;
import com.impactupgrade.common.environment.Environment;
import com.impactupgrade.common.paymentgateway.model.PaymentGatewayEvent;
import com.neovisionaries.i18n.CountryCode;
import com.sforce.soap.partner.sobject.SObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class SfdcCrmService implements CrmSourceService, CrmDestinationService {

  private static final Logger log = LogManager.getLogger(SfdcCrmService.class);

  // TODO
  private static final String RECORD_TYPE_ACCOUNT_HOUSEHOLD = "012f4000000ZcEiAAK";
  private static final String RECORD_TYPE_CONTACT_STANDARD = "012f4000000a6v0AAA";

  private final Environment env;
  private final SfdcClient sfdcClient;

  public SfdcCrmService(Environment env) {
    this.env = env;
    sfdcClient = new SfdcClient(env);
  }

  @Override
  public Optional<CrmContact> getContactByEmail(String email) throws Exception {
    return toCrmContact(sfdcClient.getContactByEmail(email));
  }

  @Override
  public String insertAccount(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    SObject account = new SObject("Account");
    account.setField("RecordTypeId", RECORD_TYPE_ACCOUNT_HOUSEHOLD);

    account.setField("Name", paymentGatewayEvent.getFullName());

    // NOTE: TER SF has country/state picklists enabled, but the state picklists are sparsely populated (and often
    // incorrect) for non-US countries. TER does not currently mail anything to international donors, so we're
    // opting to fill out the state only for stateside gifts. However, we'll fill out the account description
    // with the raw state, in case we need it later

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
    contact.setField("AccountId", paymentGatewayEvent.getCrmAccountId());
    contact.setField("FirstName", paymentGatewayEvent.getFirstName());
    contact.setField("LastName", paymentGatewayEvent.getLastName());
    contact.setField("Email", paymentGatewayEvent.getEmail());
    contact.setField("MobilePhone", paymentGatewayEvent.getPhone());

    return sfdcClient.insert(contact).getId();
  }

  @Override
  public Optional<CrmDonation> getDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return sfdcClient.getDonationByTransactionId(
        paymentGatewayEvent.getSfdcTransactionIdFieldName(), paymentGatewayEvent.getTransactionId());
  }

  @Override
  public String insertDonation(PaymentGatewayEvent paymentGatewayEvent, Optional<String> recurringDonationId) throws Exception {
    if (recurringDonationId.isPresent()) {
      // get the next pledged donation from the recurring donation
      Optional<SObject> pledgedOpportunityO = sfdcClient.getNextPledgedDonationByRecurringDonationId(
          recurringDonation.getId());
      if (pledgedOpportunityO.isPresent()) {
        Opportunity pledgedOpportunity = sfdcClient.toEnterprise(Opportunity.class, pledgedOpportunityO.get());
        log.info("found SFDC pledged opportunity {} in recurring donation {}",
            pledgedOpportunity.getId(), recurringDonation.getId());

        // check to see if the recurring donation was a failed attempt or successful
        if (paymentGatewayEvent.isTransactionSuccess()) {
          // update existing pledged donation to "Posted"
          Opportunity updateOpportunity = new Opportunity();
          updateOpportunity.setId(pledgedOpportunity.getId());
          setOpportunityFields(updateOpportunity, paymentGatewayEvent, campaign);
          sfdcClient.update(updateOpportunity);
        } else {
          // subscription payment failed
          // create new Opportunity and post it to the recurring donation leaving the Pledged donation there
          pledgedOpportunity.setId(null);
          pledgedOpportunity.setCloseDate(paymentGatewayEvent.getTransactionDate());
          setOpportunityFields(pledgedOpportunity, paymentGatewayEvent, campaign);
          sfdcClient.insert(pledgedOpportunity);
        }

        return;
      } else {
        log.warn("unable to find SFDC pledged donation for recurring donation {} that isn't in the future",
            recurringDonation.getId());
      }
    }

    // not a recurring donation, OR an existing pledged donation didn't exist -- create a new donation

    SObject opportunity = new SObject("Opportunity");

    setOpportunityFields(opportunity, paymentGatewayEvent, campaign);

    opportunity.setField("AccountId", paymentGatewayEvent.getCrmAccountId());
    opportunity.setField("CloseDate", paymentGatewayEvent.getTransactionDate());
    if (recurringDonation != null) {
      opportunity.setField("Npe03__Recurring_Donation__c", recurringDonation.getId());
    }

    return sfdcClient.insert(opportunity).getId();
  }

  @Override
  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    SObject opportunity = new SObject("Opportunity");
    opportunity.setId(oppRefund.get().getId());
    opportunity.setField("StageName", "refunded");
    paymentGatewayEvent.getSetSFDCRefundId().accept(opp);
    sfdcClient.update(opportunity);
  }

  @Override
  public void insertDonationDeposit(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    SObject opportunity = new SObject("Opportunity");
    opportunity.setId(__donation.get().getId());
    opportunity.setField("Deposit_Date__c", paymentGatewayEvent.getDepositDate());
    opportunity.setField("Deposit_ID__c", paymentGatewayEvent.getDepositId());
    opportunity.setField("Deposit_Net__c", paymentGatewayEvent.getTransactionNetAmountInDollars());
    sfdcClient.update(opportunity);
  }

  private void setOpportunityFields(SObject opportunity, PaymentGatewayEvent paymentGatewayEvent,
      Campaign campaign) {
    // check to see if this was a failed payment attempt and set the StageName accordingly
    if (paymentGatewayEvent.isTransactionSuccess()) {
      opportunity.setField("StageName", "Posted");
    } else {
      opportunity.setField("StageName", "Failed Attempt");
    }

    opportunity.setField("Name", campaign.getER_Integration_Donation_Name__c());
    opportunity.setField("Description", paymentGatewayEvent.getTransactionDescription());
    opportunity.setField("RecordTypeId", campaign.getER_Integration_Donation_Record_Type__c());

    opportunity.setField("Email__c", true);
    opportunity.setField("Attach_PDF_Receipt__c", false);
    opportunity.setField("Printed_Emailed__c", !campaign.getER_Integration_Donation_Send_Receipt__c());

    opportunity.setField("Payment_Type__c", paymentGatewayEvent.getPaymentMethod());
    opportunity.setField("LeadSource", paymentGatewayEvent.getGatewayName());
    paymentGatewayEvent.getSetSFDCTransactionId().accept(opportunity);
//    paymentGatewayEvent.getSetSFDCDepositTransactionId().accept(opportunity);

    // also update the campaign fields, in case they changed
    opportunity.setField("CampaignId", campaign.getId());
    opportunity.setField("Receipt_Category__c", campaign.getER_Integration_Donation_Receipt_Category__c());

    // update the amount on the Pledged donation, in case it did change
    opportunity.setField("Amount", paymentGatewayEvent.getTransactionAmountInDollars());
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return sfdcClient.getRecurringDonationBySubscriptionId(
        paymentGatewayEvent.getSfdcSubscriptionIdFieldName(),
        paymentGatewayEvent.getSubscriptionId()
    );
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    SObject recurringDonation = new SObject("Npe03__Recurring_Donation__c");

    recurringDonation.setField("Npe03__Organization__c", paymentGatewayEvent.getSfdcAccountId());
    recurringDonation.setField("Npe03__Amount__c", paymentGatewayEvent.getSubscriptionAmountInDollars());
    recurringDonation.setField("Npe03__Open_Ended_Status__c", "Open");
    recurringDonation.setField("Npe03__Schedule_Type__c", "Multiply By");
//    recurringDonation.setDonation_Method__c(paymentGatewayEvent.getDonationMethod());
    recurringDonation.setField("Npe03__Installment_Period__c", paymentGatewayEvent.getSubscriptionInterval());
    recurringDonation.setField("Npe03__Date_Established__c", paymentGatewayEvent.getSubscriptionStartDate());
    recurringDonation.setField("Npe03__Next_Payment_Date__c", paymentGatewayEvent.getSubscriptionNextDate());
    // TODO: unique to LJI/TER
    recurringDonation.setField("Recurring_Payment_Source__c", "Stripe");
    recurringDonation.setField("Recurring_Payment_Type__c", "Credit Card");

    paymentGatewayEvent.getSetSFDCSubscriptionId().accept(recurringDonation);
    paymentGatewayEvent.getSetSFDCCustomerId().accept(recurringDonation);

    Campaign campaign = sfdcClient.toEnterprise(Campaign.class,
        sfdcClient.getCampaignByIdOrDefault(paymentGatewayEvent.getCampaignId()).get());
    // TODO: unique to LJI/TER
    recurringDonation.setField("Type__c", campaign.getER_Integration_Donation_Recur_Type__c());
    recurringDonation.setField("Npe03__Recurring_Donation_Campaign__c", campaign.getId());

    // TODO: include, but not currently with currency conversion (look at some example)
    String name;
    if ("usd".equalsIgnoreCase(paymentGatewayEvent.getSubscriptionCurrency())) {
      name = campaign.getER_Integration_Donation_Name__c() + " ($" + paymentGatewayEvent.getSubscriptionAmountInDollars() + " " + paymentGatewayEvent.getSubscriptionInterval() + ")";
    } else {
      name = campaign.getER_Integration_Donation_Name__c() + " (" + paymentGatewayEvent.getSubscriptionAmountInDollars() + paymentGatewayEvent.getSubscriptionCurrency() + " " + paymentGatewayEvent.getSubscriptionInterval() + ")";
    }
    recurringDonation.setField("Name", name);

    return sfdcClient.insert(recurringDonation).getId();
  }

  @Override
  public String closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    SObject toUpdate = new SObject("Npe03__Recurring_Donation__c");
    toUpdate.setId(recurringDonation.getId());
    toUpdate.setField("Npe03__Open_Ended_Status__c", "Closed");
    // just in case this had been "reopened", clear it out
//      toUpdate.setPaused_status__c("");
    sfdcClient.update(toUpdate);
  }

  @Override
  public Optional<CrmCampaign> getCampaignByIdOrDefault(String campaignId) throws Exception {
    return sfdcClient.getCampaignByIdOrDefault(campaignId);
  }

  private static CrmContact toCrmContact(SObject sObject) {
    String id = sObject.getId();
    String accountId = sObject.getField("AccountId").toString();
    return new CrmContact(id, accountId);
  }

  private static Optional<CrmContact> toCrmContact(Optional<SObject> sObject) {
    return sObject.map(SfdcCrmService::toCrmContact);
  }
}
