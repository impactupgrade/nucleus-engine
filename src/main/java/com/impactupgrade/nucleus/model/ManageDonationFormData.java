package com.impactupgrade.nucleus.model;

import java.util.Optional;
import javax.ws.rs.FormParam;

public class ManageDonationFormData {

  @FormParam("rd-id") String recurringDonationId;
  @FormParam("amount") Optional<Double> amount;
  @FormParam("next-payment-date") Optional<String> nextPaymentDate;
  @FormParam("pause-donation") Boolean pauseDonation;
  @FormParam("pause-donation-until") Optional<String> pauseDonationUntilDate;

  @Override
  public String toString() {
    return "ManageDonationFormData{" +
        ",\n recurringDonationId='" + recurringDonationId +
        ",\n amount=" + amount +
        ",\n nextPaymentDate=" + nextPaymentDate +
        ",\n pauseDonation=" + pauseDonation +
        ",\n pauseDonationUntilDate=" + pauseDonationUntilDate +
        ",\n}";
  }
}
