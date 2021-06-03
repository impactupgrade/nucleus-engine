/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import java.util.Objects;

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
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    CrmAddress that = (CrmAddress) o;
    return Objects.equals(street, that.street) && Objects.equals(city, that.city) && Objects.equals(state, that.state) && Objects.equals(postalCode, that.postalCode) && Objects.equals(country, that.country);
  }

  @Override
  public int hashCode() {
    return Objects.hash(street, city, state, postalCode, country);
  }
}
