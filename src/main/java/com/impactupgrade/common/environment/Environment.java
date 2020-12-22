package com.impactupgrade.common.environment;

import com.impactupgrade.common.exception.NotImplementedException;
import com.sforce.soap.partner.sobject.SObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class allows the app to provide all the custom data and flows we need that are super-specific to the individual
 * org's environment.
 */
public class Environment {

  private static final Logger log = LogManager.getLogger(Environment.class);

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // UNIQUE FIELDS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

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
  // CUSTOM LOGIC WRAPPED IN BUILDER PATTERNS OR FP
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // TODO: I'm assuming something super unique may be needed in a subclass, but it's possible we've done this well.
  public CampaignRetriever getCampaignRetriever() {
    return new CampaignRetriever(getCampaignMetadataKeys());
  }
  // TODO: IE, this might be the only unique bit...
  protected String[] getCampaignMetadataKeys() {
    return new String[]{"sf_campaign"};
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CUSTOM LOGIC
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

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
