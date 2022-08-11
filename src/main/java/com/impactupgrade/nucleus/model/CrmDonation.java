/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Calendar;

public class CrmDonation extends HasId {

  public String name;
  public Double amount;
  public String paymentGatewayName;
  public String paymentGatewayCustomerId;
  public String paymentGatewayTransactionId;
  public Status status;
  public Calendar closeDate;
  public CrmAccount account;
  public CrmContact contact;

  @JsonIgnore
  public Object rawObject;
  @JsonIgnore
  public String crmUrl;

  public enum Status {
    PENDING, SUCCESSFUL, FAILED, REFUNDED
  }

  public CrmDonation() {}

  // A few cases where we only care about existence and require only the id.
  public CrmDonation(String id) {
    this.id = id;
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmDonation(String id, String name, Double amount, String paymentGatewayName, String paymentGatewayCustomerId, String paymentGatewayTransactionId, Status status,
      Calendar closeDate, CrmAccount account, CrmContact contact, Object rawObject, String crmUrl) {
    this.id = id;
    this.name = name;
    this.amount = amount;
    this.paymentGatewayName = paymentGatewayName;
    this.paymentGatewayCustomerId = paymentGatewayCustomerId;
    this.paymentGatewayTransactionId = paymentGatewayTransactionId;
    this.status = status;
    this.closeDate = closeDate;
    this.rawObject = rawObject;
    this.crmUrl = crmUrl;
    this.account = account;
    this.contact = contact;
  }
}
