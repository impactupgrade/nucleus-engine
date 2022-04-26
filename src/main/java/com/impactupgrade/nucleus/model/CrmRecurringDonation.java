/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Strings;

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
      if (Strings.isNullOrEmpty(name)) {
        return null;
      }

      if (WEEKLY.names.contains(name.toLowerCase(Locale.ROOT))) {
        return WEEKLY;
      } else if (QUARTERLY.names.contains(name.toLowerCase(Locale.ROOT))) {
        return QUARTERLY;
      } else if (BIANNUALLY.names.contains(name.toLowerCase(Locale.ROOT))) {
        return BIANNUALLY;
      } else if (YEARLY.names.contains(name.toLowerCase(Locale.ROOT))) {
        return YEARLY;
      } else {
        // default to monthly
        return MONTHLY;
      }
    }
  }

  public String id;
  public String subscriptionId;
  public String customerId;
  public String donationName;
  public Double amount;
  public String paymentGatewayName;
  public String status;
  public Boolean active;
  public Frequency frequency;
  public CrmAccount account;
  public CrmContact contact;

  @JsonIgnore
  public Object rawObject;
  @JsonIgnore
  public String crmUrl;

  public CrmRecurringDonation() {}

  // A few cases where we only care about existence and require only the id.
  public CrmRecurringDonation(String id) {
    this.id = id;
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmRecurringDonation(String id, String subscriptionId, String customerId, Double amount,
      String paymentGatewayName, String status, Boolean active, Frequency frequency, String donationName, CrmAccount account, CrmContact contact, Object rawObject, String crmUrl) {
    this.id = id;
    this.subscriptionId = subscriptionId;
    this.customerId = customerId;
    this.amount = amount;
    this.paymentGatewayName = paymentGatewayName;
    this.status = status;
    this.active = active;
    this.frequency = frequency;
    this.donationName = donationName;
    this.contact = contact;
    this.rawObject = rawObject;
    this.account = account;
    this.crmUrl = crmUrl;
  }
}
