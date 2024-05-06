/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import javax.ws.rs.FormParam;
import java.util.Optional;

public class ManageDonationFormData {

  @FormParam("rd_id") String recurringDonationId;
  @FormParam("amount") Optional<Double> amount;
  @FormParam("pause_donation") Optional<Boolean> pauseDonation;
  @FormParam("pause_donation_until") Optional<String> pauseDonationUntilDate;
  @FormParam("resume_donation") Optional<Boolean> resumeDonation;
  @FormParam("resume_donation_on") Optional<String> resumeDonationOnDate;
  @FormParam("next_payment_date") Optional<String> nextPaymentDate;
  @FormParam("cancel_donation") Optional<Boolean> cancelDonation;
  @FormParam("stripe_token") Optional<String> stripeToken;

  @Override
  public String toString() {
    return "ManageDonationFormData{" +
        ",\n recurringDonationId='" + recurringDonationId +
        ",\n amount=" + amount +
        ",\n pauseDonation=" + pauseDonation +
        ",\n pauseDonationUntilDate=" + pauseDonationUntilDate +
        ",\n resumeDonation=" + resumeDonation +
        ",\n resumeDonationOnDate=" + resumeDonationOnDate +
        ",\n nextPaymentDate=" + nextPaymentDate +
        ",\n cancelDonation=" + cancelDonation +
        ",\n stripeToken=" + stripeToken +
        ",\n}";
  }
}
