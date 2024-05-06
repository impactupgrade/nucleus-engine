/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public class CrmDonation extends CrmOpportunity {

  public enum Status {
    PENDING, SUCCESSFUL, FAILED, REFUNDED
  }

  public CrmRecurringDonation recurringDonation = new CrmRecurringDonation();

  // primary/secondary event management
  public CrmDonation parent = null;
  public List<CrmDonation> children = new ArrayList<>();

  public Double amount;
  public String customerId;
  public ZonedDateTime depositDate;
  public String depositId;
  public String depositTransactionId;
  public String gatewayName;
  public EnvironmentConfig.TransactionType transactionType = EnvironmentConfig.TransactionType.DONATION;
  public String paymentMethod;
  // TODO: Ex: If the payment involved a Stripe invoice, capture the product ID for each line item. We eventually may
  //  need to refactor this to provide additional info, but let's see how it goes.
  public List<String> products = new ArrayList<>();
  public String refundId;
  public ZonedDateTime refundDate;
  public Status status = Status.SUCCESSFUL;
  public String failureReason;
  public boolean currencyConverted;
  public Double exchangeRate;
  public Double feeInDollars;
  public String transactionId;
  public Double netAmountInDollars;
  public Double originalAmountInDollars;
  public String originalCurrency;
  public String secondaryId; // ex: Stripe Charge ID if this was the Payment Intent API
  public String url;

  public String application;

  public boolean isRecurring() {
    return !Strings.isNullOrEmpty(recurringDonation.id) || !Strings.isNullOrEmpty(recurringDonation.subscriptionId);
  }

  public CrmDonation() {}

  // A few cases where we only care about existence and require only the id.
  public CrmDonation(String id) {
    super(id);
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmDonation(
      String id,
      CrmAccount account,
      CrmContact contact,
      CrmRecurringDonation recurringDonation,
      Double amount,
      String customerId,
      ZonedDateTime depositDate,
      String depositId,
      String depositTransactionId,
      String gatewayName,
      EnvironmentConfig.TransactionType transactionType,
      String paymentMethod,
      String refundId,
      ZonedDateTime refundDate,
      Status status,
      String failureReason,
      boolean currencyConverted,
      Double exchangeRate,
      Double feeInDollars,
      String transactionId,
      Double netAmountInDollars,
      Double originalAmountInDollars,
      String originalCurrency,
      String secondaryId,
      String url,
      String campaignId,
      ZonedDateTime closeDate,
      String description,
      String name,
      String ownerId,
      String recordTypeId,
      Object crmRawObject,
      String crmUrl
  ) {
    super(id, account, contact, campaignId, closeDate, description, name, ownerId, recordTypeId, crmRawObject, crmUrl);
    this.amount = amount;
    this.customerId = customerId;
    this.depositDate = depositDate;
    this.depositId = depositId;
    this.depositTransactionId = depositTransactionId;
    this.gatewayName = gatewayName;
    if (transactionType != null) this.transactionType = transactionType;
    this.paymentMethod = paymentMethod;
    this.recurringDonation = recurringDonation;
    this.refundId = refundId;
    this.refundDate = refundDate;
    if (status != null) this.status = status;
    this.failureReason = failureReason;
    this.currencyConverted = currencyConverted;
    this.exchangeRate = exchangeRate;
    this.feeInDollars = feeInDollars;
    this.transactionId = transactionId;
    this.netAmountInDollars = netAmountInDollars;
    this.originalAmountInDollars = originalAmountInDollars;
    this.originalCurrency = originalCurrency;
    this.secondaryId = secondaryId;
    this.url = url;
  }

  public List<String> getTransactionIds() {
    List<String> transactionIds = new ArrayList<>();

    // SOME orgs create separate Opportunities for refunds, then use the Refund IDs in the standard Charge ID field.
    if (!Strings.isNullOrEmpty(refundId)) {
      transactionIds.add(refundId);
    }
    if (!Strings.isNullOrEmpty(transactionId)) {
      transactionIds.add(transactionId);
    }
    if (!Strings.isNullOrEmpty(secondaryId)) {
      transactionIds.add(secondaryId);
    }

    return transactionIds;
  }
}
