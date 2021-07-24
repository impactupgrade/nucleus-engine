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

  protected final Environment env;

  protected String donationId;
  // TODO: donationName is likely DR-specific (their unique, incrementing identifiers) -- pull to dr-nucleus?
  protected String donationName;
  protected String subscriptionId;
  protected Double amount;
  protected Calendar pauseDonationUntilDate;
  protected Boolean pauseDonation;
  protected Calendar resumeDonationOnDate;
  protected Boolean resumeDonation;
  protected Calendar nextPaymentDate;
  protected Boolean cancelDonation;
  protected String stripeToken;

  public ManageDonationEvent(Environment env) {
    this.env = env;
  }

  public ManageDonationEvent(ManageDonationFormData formData, Environment env) throws ParseException {
    this.env = env;

    if (formData.recurringDonationId.isPresent() && !Strings.isNullOrEmpty(formData.recurringDonationId.get())) this.donationId = formData.recurringDonationId.get();
    if (formData.recurringDonationName.isPresent() && !Strings.isNullOrEmpty(formData.recurringDonationName.get())) this.donationName = formData.recurringDonationName.get();

    formData.stripeToken.ifPresent(s -> this.stripeToken = s);

    if (formData.amount != null && formData.amount.isPresent()) this.amount = formData.amount.get();

    if (formData.pauseDonationUntilDate != null && formData.pauseDonationUntilDate.isPresent()) this.pauseDonationUntilDate = Utils.getCalendarFromDateString(formData.pauseDonationUntilDate.get());
    if (formData.resumeDonationOnDate != null && formData.resumeDonationOnDate.isPresent()) this.resumeDonationOnDate = Utils.getCalendarFromDateString(formData.resumeDonationOnDate.get());
    if (formData.nextPaymentDate != null && formData.nextPaymentDate.isPresent()) this.nextPaymentDate = Utils.getCalendarFromDateString(formData.nextPaymentDate.get());

    this.pauseDonation = formData.pauseDonation.isPresent() && formData.pauseDonation.get();
    this.resumeDonation = formData.resumeDonation.isPresent() && formData.resumeDonation.get();
    this.cancelDonation = formData.cancelDonation.isPresent() && formData.cancelDonation.get();
  }

  // ACCESSORS

  public String getDonationId() { return this.donationId; }

  public void setDonationId(String donationId) { this.donationId = donationId; }

  public String getDonationName() { return this.donationName; }

  public void setDonationName(String donationName) { this.donationName = donationName; }

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
        ",\n donationName: " + this.donationName +
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
