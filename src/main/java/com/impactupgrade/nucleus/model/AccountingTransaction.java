/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.impactupgrade.nucleus.environment.EnvironmentConfig;

import java.time.ZonedDateTime;

/**
 * AccountingService has some rather complex logic to tie together deposits, transactions, contacts/accounts, etc.
 * Rather than complicating the methods and arguments, combine processing into this model.
 */
public class AccountingTransaction {

  // TODO: Rename? "Contact" in Xero, but something else in QB?
  public String contactId;
  public String crmContactId;

  public Double amountInDollars;
  public ZonedDateTime date;
  public String description;
  public EnvironmentConfig.TransactionType transactionType;
  public String paymentGatewayName;
  public String paymentGatewayTransactionId;
  public Boolean recurring;
  //TODO: find a way of passing crm object custom fields to accounting layer
  // (configurable list of custom fields' names? extract into a map to use instead of entire crmDonation object?)
  public CrmDonation crmDonation;

  public AccountingTransaction(
      String contactId,
      String crmContactId,
      Double amountInDollars,
      ZonedDateTime date,
      String description,
      EnvironmentConfig.TransactionType transactionType,
      String paymentGatewayName,
      String paymentGatewayTransactionId,
      Boolean recurring
  ) {
    this.contactId = contactId;
    this.crmContactId = crmContactId;

    this.amountInDollars = amountInDollars;
    this.date = date;
    this.description = description;
    this.transactionType = transactionType;
    this.paymentGatewayName = paymentGatewayName;
    this.paymentGatewayTransactionId = paymentGatewayTransactionId;
    this.recurring = recurring;
  }
}
