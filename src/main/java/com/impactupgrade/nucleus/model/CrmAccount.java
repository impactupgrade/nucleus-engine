/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import java.util.Calendar;

public class CrmAccount {

  public String id;
  public String name;
  public CrmAddress address;

  public Integer donationCount;
  public Double donationTotal;
  public Calendar firstDonationDate;

  public CrmAccount() {}

  // A few cases where we only care about existence and require only the id.
  public CrmAccount(String id) {
    this.id = id;
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmAccount(String id, String name, CrmAddress address, Integer donationCount, Double donationTotal,
      Calendar firstDonationDate) {
    this.id = id;
    this.name = name;
    this.address = address;
    this.donationCount = donationCount;
    this.donationTotal = donationTotal;
    this.firstDonationDate = firstDonationDate;
  }
}
