/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;

public class CrmAddress {

  public String street;
  public String city;
  public String state;
  public String postalCode;
  public String country;

  public CrmAddress() {}

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmAddress(String street, String city, String state, String postalCode, String country) {
    this.street = street;
    this.city = city;
    this.state = state;
    this.postalCode = postalCode;
    this.country = country;
  }

  @Override
  public String toString() {
    if (Strings.isNullOrEmpty(street)) {
      return "";
    } else {
      return street + ", " + city + ", " + state + " " + postalCode + ", " + country;
    }
  }
}
