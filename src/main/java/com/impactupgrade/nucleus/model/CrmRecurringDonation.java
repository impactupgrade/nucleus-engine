/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

public class CrmRecurringDonation {

  public String id;
  public String subscriptionId;
  public String customerId;
  public Double amount;
  public String paymentGatewayName;

  public CrmRecurringDonation() {}

  // A few cases where we only care about existence and require only the id.
  public CrmRecurringDonation(String id) {
    this.id = id;
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmRecurringDonation(String id, String subscriptionId, String customerId, Double amount,
      String paymentGatewayName) {
    this.id = id;
    this.subscriptionId = subscriptionId;
    this.customerId = customerId;
    this.amount = amount;
    this.paymentGatewayName = paymentGatewayName;
  }
}
