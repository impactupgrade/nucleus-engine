package com.impactupgrade.nucleus.model;


import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.Utils;
import java.text.ParseException;
import java.util.Calendar;

public class ManageDonationEvent {
  protected final Environment.RequestEnvironment requestEnv;

  protected String donationId;
  protected Double amount;
  protected String subscriptionId;
  protected Calendar nextPaymentDate;
  protected Calendar pauseDonationUntilDate;
  protected Boolean pauseDonation;
  protected String stripeToken;

  public ManageDonationEvent(Environment.RequestEnvironment requestEnv) {
    this.requestEnv = requestEnv;
  }

  public ManageDonationEvent(Environment.RequestEnvironment requestEnv, ManageDonationFormData formData) throws ParseException {
    this.requestEnv = requestEnv;
    this.setDonationId(formData.recurringDonationId);
    if (formData.stripeToken.isPresent()) this.stripeToken = formData.stripeToken.get();

    if (formData.amount.isPresent()) this.amount = formData.amount.get();
    if (formData.nextPaymentDate.isPresent()) this.nextPaymentDate = Utils.getCalendarFromDateString(formData.nextPaymentDate.get());
    if (formData.pauseDonation.isPresent()) this.setPauseDonation(formData.pauseDonation.get() == true);
    if (formData.pauseDonationUntilDate.isPresent()) this.pauseDonationUntilDate = Utils.getCalendarFromDateString(formData.pauseDonationUntilDate.get());
  }

  // ACCESSORS

  public Environment.RequestEnvironment getRequestEnv() { return this.requestEnv; }

  public String getDonationId() { return this.donationId; }

  public void setDonationId(String donationId) { this.donationId = donationId; }

  public Double getAmount() { return this.amount; }

  public void setAmount(Double amount) { this.amount = amount; }

  public String getSubscriptionId() { return this.subscriptionId; }

  public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

  public Calendar getNextPaymentDate() { return this.nextPaymentDate; }

  public void setNextPaymentDate(Calendar nextPaymentDate) { this.nextPaymentDate = nextPaymentDate; }

  public Boolean getPauseDonation() { return this.pauseDonation; }

  public void setPauseDonation(Boolean pauseDonation) { this.pauseDonation = pauseDonation; }

  public Calendar getPauseDonationUntilDate() { return this.pauseDonationUntilDate; }

  public void setPauseDonationUntilDate(Calendar pauseDonationUntilDate) { this.pauseDonationUntilDate = pauseDonationUntilDate; }

  public String getStripeToken() { return this.stripeToken; }

  public void setStripeToken(String stripeToken) { this.stripeToken = stripeToken; }

  public String toString() {
    return "ManageDonationEvent {" +
        ",\n donationId: " + this.donationId +
        ",\n amount: " + this.amount +
        ",\n subscriptionId: " + this.subscriptionId +
        ",\n nextPaymentDate: " + this.nextPaymentDate  +
        ",\n pauseDonation: " + this.pauseDonation +
        ",\n pauseDonationUntilDate: " + this.pauseDonationUntilDate +
        ",\n stripeToken: " + this.stripeToken +
        ",\n }";
  }
}
