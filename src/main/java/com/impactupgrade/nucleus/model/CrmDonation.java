/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import java.util.Calendar;

public class CrmDonation {

  public String id;
  public String name;
  public Double amount;
  public String paymentGatewayName;
  public Status status;
  public Calendar closeDate;
  public Object rawObject;

  public enum Status {
    PENDING, SUCCESSFUL, FAILED
  }

  public CrmDonation() {}

  // A few cases where we only care about existence and require only the id.
  public CrmDonation(String id) {
    this.id = id;
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmDonation(String id, String name, Double amount, String paymentGatewayName, Status status,
      Calendar closeDate, Object rawObject) {
    this.id = id;
    this.name = name;
    this.amount = amount;
    this.paymentGatewayName = paymentGatewayName;
    this.status = status;
    this.closeDate = closeDate;
    this.rawObject = rawObject;
  }
}
