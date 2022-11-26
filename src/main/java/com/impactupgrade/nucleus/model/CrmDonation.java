/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import java.util.Calendar;

public class CrmDonation extends CrmOpportunity {

  public Double amount;
  public String paymentGatewayName;
  public String paymentGatewayTransactionId;
  public Status status;

  public enum Status {
    PENDING, SUCCESSFUL, FAILED, REFUNDED
  }

  public CrmDonation() {}

  // A few cases where we only care about existence and require only the id.
  public CrmDonation(String id) {
    super(id);
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmDonation(String id, String name, Double amount, String paymentGatewayName, String paymentGatewayTransactionId, Status status,
      Calendar closeDate, String notes, String campaignId, String ownerId,
      String recordTypeId, CrmAccount account, CrmContact contact, Object rawObject, String crmUrl) {
    super(id, name, closeDate, notes, campaignId, ownerId, recordTypeId, account, contact, rawObject, crmUrl);
    this.amount = amount;
    this.paymentGatewayName = paymentGatewayName;
    this.paymentGatewayTransactionId = paymentGatewayTransactionId;
    this.status = status;
  }
}
