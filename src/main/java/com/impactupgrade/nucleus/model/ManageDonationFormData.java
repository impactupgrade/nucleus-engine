/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import javax.ws.rs.FormParam;
import java.util.Optional;

public class ManageDonationFormData {

  @FormParam("rd_id") public String recurringDonationId;
  @FormParam("amount") public Optional<String> amount;
  @FormParam("pause_donation") public Optional<Boolean> pauseDonation;
  @FormParam("pause_donation_until") public Optional<String> pauseDonationUntilDate;
  @FormParam("resume_donation") public Optional<Boolean> resumeDonation;
  @FormParam("resume_donation_on") public Optional<String> resumeDonationOnDate;
  @FormParam("next_payment_date") public Optional<String> nextPaymentDate;
  @FormParam("cancel_donation") public Optional<Boolean> cancelDonation;
  @FormParam("stripe_token") public Optional<String> stripeToken;

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
