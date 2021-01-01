package com.impactupgrade.common.environment;

import com.impactupgrade.common.exception.NotImplementedException;
import com.impactupgrade.common.paymentgateway.PaymentGatewayEvent;
import com.sforce.soap.partner.sobject.SObject;
import com.stripe.net.RequestOptions;
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

  public PaymentGatewayEvent buildPaymentGatewayEvent() {
    return new PaymentGatewayEvent(this);
  }

  public String getCurrency() {
    return "usd";
  }

  public RequestOptions buildStripeRequestOptions() {
    return RequestOptions.builder().setApiKey(System.getenv("STRIPE_KEY")).build();
  }

  // TODO: the paymentGatewayEvent process methods may be able to pull in some common code...

  public void transactionDeposited(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "transactionDeposited");
  }

  public void transactionSucceeded(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "transactionSucceeded");
  }

  public void transactionFailed(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "transactionFailed");
  }

  public void transactionRefunded(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "transactionRefunded");
  }

  public void subscriptionTrialing(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "subscriptionTrialing");
  }

  public void subscriptionClosed(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "subscriptionClosed");
  }

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
