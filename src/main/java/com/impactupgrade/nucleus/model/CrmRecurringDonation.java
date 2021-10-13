/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Locale;

public class CrmRecurringDonation {

  public enum Frequency {
    WEEKLY(List.of("weekly", "week")),
    MONTHLY(List.of("monthly", "month")),
    QUARTERLY(List.of("quarterly", "quarter")),
    YEARLY(List.of("yearly", "year")),
    BIANNUALLY(List.of("biannually", "biannual"));

    private final List<String> names;

    Frequency(List<String> names) {
      this.names = names;
    }

    public static Frequency fromName(String name) {
      if (WEEKLY.names.contains(name.toLowerCase(Locale.ROOT))) {
        return WEEKLY;
      }
      if (QUARTERLY.names.contains(name.toLowerCase(Locale.ROOT))) {
        return QUARTERLY;
      }
      if (BIANNUALLY.names.contains(name.toLowerCase(Locale.ROOT))) {
        return BIANNUALLY;
      }
      if (YEARLY.names.contains(name.toLowerCase(Locale.ROOT))) {
        return YEARLY;
      }
      // default to monthly
      return MONTHLY;
    }
  }

  public String id;
  public String subscriptionId;
  public String customerId;
  public Double amount;
  public String paymentGatewayName;
  public Boolean active;
  public Frequency frequency;

  @JsonIgnore
  public Object rawObject;

  public CrmRecurringDonation() {}

  // A few cases where we only care about existence and require only the id.
  public CrmRecurringDonation(String id) {
    this.id = id;
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmRecurringDonation(String id, String subscriptionId, String customerId, Double amount,
      String paymentGatewayName, Boolean active, Frequency frequency, Object rawObject) {
    this.id = id;
    this.subscriptionId = subscriptionId;
    this.customerId = customerId;
    this.amount = amount;
    this.paymentGatewayName = paymentGatewayName;
    this.active = active;
    this.frequency = frequency;
    this.rawObject = rawObject;
  }
}
