/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

public class CrmRecurringDonation extends CrmRecord {

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

    public String primaryName() {
      return names.get(0);
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

  public CrmAccount account = new CrmAccount();
  public CrmContact contact = new CrmContact();

  public Boolean active;
  public Double amount;
  public String campaignId;
  public String customerId;
  public String description;
  public String donationName;
  public Frequency frequency = Frequency.MONTHLY;
  public String gatewayName;
  public String subscriptionCurrency;
  public String subscriptionId;
  public ZonedDateTime subscriptionNextDate;
  public ZonedDateTime subscriptionStartDate;

  public CrmRecurringDonation() {}

  // A few cases where we only care about existence and require only the id.
  public CrmRecurringDonation(String id) {
    super(id);
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmRecurringDonation(
      String id,
      CrmAccount account,
      CrmContact contact,
      Boolean active,
      Double amount,
      String campaignId,
      String customerId,
      String description,
      String donationName,
      Frequency frequency,
      String gatewayName,
      String subscriptionCurrency,
      String subscriptionId,
      ZonedDateTime subscriptionNextDate,
      ZonedDateTime subscriptionStartDate,
      Object crmRawObject,
      String crmUrl
  ) {
    super(id, crmRawObject, crmUrl);

    if (account != null) this.account = account;
    if (contact != null) this.contact = contact;

    this.active = active;
    this.amount = amount;
    this.campaignId = campaignId;
    this.customerId = customerId;
    this.description = description;
    this.donationName = donationName;
    if (frequency != null) this.frequency = frequency;
    this.gatewayName = gatewayName;
    this.subscriptionCurrency = subscriptionCurrency;
    this.subscriptionId = subscriptionId;
    this.subscriptionNextDate = subscriptionNextDate;
    this.subscriptionStartDate = subscriptionStartDate;
  }
}
