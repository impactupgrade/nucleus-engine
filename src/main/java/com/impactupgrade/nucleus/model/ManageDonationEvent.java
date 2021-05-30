/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

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
  protected Calendar pauseDonationUntilDate;
  protected Boolean pauseDonation;
  protected Calendar resumeDonationOnDate;
  protected Boolean resumeDonation;
  protected Calendar nextPaymentDate;
  protected Boolean cancelDonation;
  protected String stripeToken;

  public ManageDonationEvent(Environment.RequestEnvironment requestEnv) {
    this.requestEnv = requestEnv;
  }

  public ManageDonationEvent(Environment.RequestEnvironment requestEnv, ManageDonationFormData formData) throws ParseException {
    this.requestEnv = requestEnv;
    this.setDonationId(formData.recurringDonationId);
    formData.stripeToken.ifPresent(s -> this.stripeToken = s);

    if (!Strings.isNullOrEmpty(formData.amount.get())) this.amount = Double.parseDouble(formData.amount.get());
    if (formData.pauseDonationUntilDate.isPresent()) this.pauseDonationUntilDate = Utils.getCalendarFromDateString(formData.pauseDonationUntilDate.get());
    if (formData.resumeDonationOnDate.isPresent()) this.resumeDonationOnDate = Utils.getCalendarFromDateString(formData.resumeDonationOnDate.get());
    if (!Strings.isNullOrEmpty(formData.nextPaymentDate.get())) this.nextPaymentDate = Utils.getCalendarFromDateString(formData.nextPaymentDate.get());

    this.pauseDonation = formData.pauseDonation.isPresent() && formData.pauseDonation.get();
    this.resumeDonation = formData.resumeDonation.isPresent() && formData.resumeDonation.get();
    this.cancelDonation = formData.cancelDonation.isPresent() && formData.cancelDonation.get();
  }

  // ACCESSORS

  public Environment.RequestEnvironment getRequestEnv() { return this.requestEnv; }

  public String getDonationId() { return this.donationId; }

  public void setDonationId(String donationId) { this.donationId = donationId; }

  public Double getAmount() { return this.amount; }

  public void setAmount(Double amount) { this.amount = amount; }

  public String getSubscriptionId() { return this.subscriptionId; }

  public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

  public Boolean getPauseDonation() { return this.pauseDonation; }

  public void setPauseDonation(Boolean pauseDonation) { this.pauseDonation = pauseDonation; }

  public Calendar getPauseDonationUntilDate() { return this.pauseDonationUntilDate; }

  public void setPauseDonationUntilDate(Calendar pauseDonationUntilDate) { this.pauseDonationUntilDate = pauseDonationUntilDate; }

  public Boolean getResumeDonation() { return this.resumeDonation; }

  public void setResumeDonation(Boolean resumeDonation) { this.resumeDonation = resumeDonation; }

  public Calendar getResumeDonationOnDate() { return this.resumeDonationOnDate; }

  public void setResumeDonationOnDate(Calendar resumeDonationOnDate) { this.resumeDonationOnDate = resumeDonationOnDate; }

  public Calendar getNextPaymentDate() { return this.nextPaymentDate; }

  public void setNextPaymentDate(Calendar nextPaymentDate) { this.nextPaymentDate = nextPaymentDate; }

  public Boolean getCancelDonation() { return this.cancelDonation; }

  public void setCancelDonation(Boolean cancelDonation) { this.cancelDonation = cancelDonation; }

  public String getStripeToken() { return this.stripeToken; }

  public void setStripeToken(String stripeToken) { this.stripeToken = stripeToken; }

  public String toString() {
    return "ManageDonationEvent {" +
        ",\n donationId: " + this.donationId +
        ",\n amount: " + this.amount +
        ",\n subscriptionId: " + this.subscriptionId +
        ",\n pauseDonation: " + this.pauseDonation +
        ",\n pauseDonationUntilDate: " + this.pauseDonationUntilDate +
        ",\n resumeDonation: " + this.resumeDonation +
        ",\n resumeDonationOnDate: " + this.resumeDonationOnDate +
        ",\n nextPaymentDate: " + this.nextPaymentDate +
        ",\n cancelDonation: " + this.cancelDonation +
        ",\n stripeToken: " + this.stripeToken +
        ",\n }";
  }
}
