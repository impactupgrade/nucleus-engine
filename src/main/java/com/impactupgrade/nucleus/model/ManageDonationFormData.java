package com.impactupgrade.nucleus.model;

import java.util.Optional;
import javax.ws.rs.FormParam;

public class ManageDonationFormData {

  @FormParam("rd-id") String recurringDonationId;
  @FormParam("amount") Optional<Double> amount;
  @FormParam("pause-donation") Optional<Boolean> pauseDonation;
  @FormParam("pause-donation-until") Optional<String> pauseDonationUntilDate;
  @FormParam("resume-donation") Optional<Boolean> resumeDonation;
  @FormParam("resume-donation-on") Optional<String> resumeDonationOnDate;
  @FormParam("next-payment-date") Optional<String> nextPaymentDate;
  @FormParam("stripe-token") Optional<String> stripeToken;

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
        ",\n stripeToken=" + stripeToken +
        ",\n}";
  }
}
