/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;


import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.Utils;

import java.text.ParseException;
import java.time.ZoneId;
import java.util.Calendar;

public class ManageDonationEvent {

  protected final Environment env;

  protected CrmRecurringDonation crmRecurringDonation = new CrmRecurringDonation();

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

    crmRecurringDonation.id = formData.recurringDonationId;

    formData.stripeToken.ifPresent(s -> this.stripeToken = s);

    if (formData.amount != null && formData.amount.isPresent())
      crmRecurringDonation.amount = formData.amount.get();

    // Since we're consuming the above Calendar variables as form fields
    // directly from Nucleus Portal, those are likely coming in as the default TZ
    if (formData.pauseDonationUntilDate != null && formData.pauseDonationUntilDate.isPresent())
      this.pauseDonationUntilDate = Utils.getCalendarFromDateString(formData.pauseDonationUntilDate.get(), ZoneId.systemDefault().getId());
    if (formData.resumeDonationOnDate != null && formData.resumeDonationOnDate.isPresent())
      this.resumeDonationOnDate = Utils.getCalendarFromDateString(formData.resumeDonationOnDate.get(), ZoneId.systemDefault().getId());
    if (formData.nextPaymentDate != null && formData.nextPaymentDate.isPresent())
      this.nextPaymentDate = Utils.getCalendarFromDateString(formData.nextPaymentDate.get(), ZoneId.systemDefault().getId());

    this.pauseDonation = formData.pauseDonation.isPresent() && formData.pauseDonation.get();
    this.resumeDonation = formData.resumeDonation.isPresent() && formData.resumeDonation.get();
    this.cancelDonation = formData.cancelDonation.isPresent() && formData.cancelDonation.get();
  }

  // ACCESSORS


  public CrmRecurringDonation getCrmRecurringDonation() {
    return crmRecurringDonation;
  }

  public void setCrmRecurringDonation(CrmRecurringDonation crmRecurringDonation) {
    this.crmRecurringDonation = crmRecurringDonation;
  }

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
        ",\n crmRecurringDonation: " + this.crmRecurringDonation +
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
