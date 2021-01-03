package com.impactupgrade.common.environment;

import com.impactupgrade.common.crm.AggregateCrmDestinationService;
import com.impactupgrade.common.crm.CrmSourceService;
import com.impactupgrade.common.exception.NotImplementedException;
import com.impactupgrade.common.paymentgateway.model.PaymentGatewayEvent;
import com.sforce.soap.partner.sobject.SObject;
import com.stripe.net.RequestOptions;

/**
 * This class allows the app to provide all the custom data and flows we need that are super-specific to the individual
 * org's environment.
 *
 * TODO: This is quickly going to get out of control and will need broken down into more
 * flexible concepts. But for now, isolating them customization requirements to one spot...
 */
public class Environment {
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // SERVICE PROVIDERS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Define a single CRM as the end-all source-of-truth for retrievals and queries.
   */
  public CrmSourceService crmSource() {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "crmSource");
  }

  /**
   * Define one or more CRMs as the destinations for donation data.
   */
  public AggregateCrmDestinationService crmDonationDestinations() {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "crmDonationDestinations");
  }

  // TODO: other destinations: SMS interactions, etc.
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // UNIQUE FIELDS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public String sfdcAccountFields() { return ""; }
  public String sfdcCampaignFields() { return ""; }
  public String sfdcContactFields() { return ""; }
  public String sfdcDonationFields() { return ""; }
  public String sfdcRecurringDonationFields() { return ""; }
  public String sfdcUserFields() { return ""; }

  public String sfdcFieldOppDepositDate() {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "sfdcFieldOppDepositDate");
  }
  public String sfdcFieldOppDepositID() {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "sfdcFieldOppDepositID");
  }
  public String sfdcFieldOppDepositNet() {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "sfdcFieldOppDepositNet");
  }
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CUSTOM LOGIC
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // TODO: I'm assuming something super unique may be needed in a subclass, but it's possible we've done this well enough to not need it.
  public CampaignRetriever getCampaignRetriever() {
    return new CampaignRetriever(this, getCampaignMetadataKeys());
  }

  // TODO: IE, this might be the only unique bit...
  protected String[] getCampaignMetadataKeys() {
    return new String[]{"sf_campaign"};
  }

  public String getDefaultSFDCCampaignId() { return ""; }

  public PaymentGatewayEvent buildPaymentGatewayEvent() {
    return new PaymentGatewayEvent(this);
  }

  public String getCurrency() {
    return "usd";
  }

  public RequestOptions buildStripeRequestOptions() {
    return RequestOptions.builder().setApiKey(System.getenv("STRIPE_KEY")).build();
  }

  // TODO: Need to be refactored so they're not assuming SFDC

  public String getStripeCustomerIdFromRecurringDonation(SObject recurringDonation) {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "getStripeCustomerIdFromRecurringDonation");
  }

  public String getStripeSubscriptionIdFromRecurringDonation(SObject recurringDonation) {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "getStripeSubscriptionIdFromRecurringDonation");
  }

  public String getPaymentSpringCustomerIdFromRecurringDonation(SObject recurringDonation) {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "getPaymentSpringCustomerIdFromRecurringDonation");
  }

  public String getPaymentSpringSubscriptionIdFromRecurringDonation(SObject recurringDonation) {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "getPaymentSpringSubscriptionIdFromRecurringDonation");
  }
}
