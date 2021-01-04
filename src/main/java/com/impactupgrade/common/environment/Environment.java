package com.impactupgrade.common.environment;

import com.impactupgrade.common.backup.BackupController;
import com.impactupgrade.common.crm.AggregateCrmDestinationService;
import com.impactupgrade.common.crm.CrmSourceService;
import com.impactupgrade.common.crm.sfdc.SfdcController;
import com.impactupgrade.common.exception.NotImplementedException;
import com.impactupgrade.common.paymentgateway.DonationService;
import com.impactupgrade.common.paymentgateway.DonorService;
import com.impactupgrade.common.paymentgateway.PaymentGatewayController;
import com.impactupgrade.common.paymentgateway.paymentspring.PaymentSpringController;
import com.impactupgrade.common.paymentgateway.stripe.StripeController;
import com.impactupgrade.common.twilio.TwilioController;
import com.sforce.soap.partner.sobject.SObject;
import com.stripe.net.RequestOptions;

/**
 * This class allows the app to provide all the custom data and flows we need that are super-specific to the individual
 * org's environment.
 *
 * TODO: This is quickly going to get out of control and will need broken down into more
 * flexible concepts or by inheriting and overriding specific service methods.
 * But for now, isolating them customization requirements to one spot...
 */
public class Environment {
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // REGISTRY
  // Provides a simple registry of controllers, services, etc. to allow subprojects to override concepts as needed!
  // Yes, we could use Spring/ServiceRegistry/OSGi. But holding off on frameworks until we absolutely need them...
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public BackupController backupController() { return new BackupController(); }
  public PaymentGatewayController paymentGatewayController() { return new PaymentGatewayController(this); }
  public PaymentSpringController paymentSpringController() { return new PaymentSpringController(this); }
  public SfdcController sfdcController() { return new SfdcController(this); }
  public StripeController stripeController() { return new StripeController(this); }
  public TwilioController twilioController() { return new TwilioController(); }

  public DonationService donationService() { return new DonationService(this); }
  public DonorService donorService() { return new DonorService(this); }

  /**
   * Define a single CRM as the end-all source-of-truth for retrievals and queries.
   */
  public CrmSourceService crmSourceService() {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "crmSource");
  }

  /**
   * Define one or more CRMs as the destinations for donation data.
   */
  public AggregateCrmDestinationService crmDonationDestinationServices() {
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

  public String sfdcFieldOpportunityDepositDate() {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "sfdcFieldOpportunityDepositDate");
  }
  public String sfdcFieldOpportunityDepositId() {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "sfdcFieldOpportunityDepositId");
  }
  public String sfdcFieldOpportunityDepositNet() {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "sfdcFieldOpportunityDepositNet");
  }
  public String sfdcFieldOpportunityTransactionId() {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "sfdcFieldOpportunityTransactionId");
  }
  public String sfdcFieldRecurringDonationSubscriptionId() {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "sfdcFieldRecurringDonationSubscriptionId");
  }

  public String[] campaignMetadataKeys() {
    return new String[]{"sf_campaign"};
  }

  public String defaultSFDCCampaignId() { return ""; }

  public String defaultCurrency() {
    return "usd";
  }
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CUSTOM LOGIC
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public RequestOptions buildStripeRequestOptions() {
    return RequestOptions.builder().setApiKey(System.getenv("STRIPE_KEY")).build();
  }

  // TODO: Need to be refactored so they're not assuming SFDC

  public String stripeCustomerIdFromRecurringDonation(SObject recurringDonation) {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "getStripeCustomerIdFromRecurringDonation");
  }

  public String stripeSubscriptionIdFromRecurringDonation(SObject recurringDonation) {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "getStripeSubscriptionIdFromRecurringDonation");
  }

  public String paymentSpringCustomerIdFromRecurringDonation(SObject recurringDonation) {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "getPaymentSpringCustomerIdFromRecurringDonation");
  }

  public String paymentSpringSubscriptionIdFromRecurringDonation(SObject recurringDonation) {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "getPaymentSpringSubscriptionIdFromRecurringDonation");
  }
}
