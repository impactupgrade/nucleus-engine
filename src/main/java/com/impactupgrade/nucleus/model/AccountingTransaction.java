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

  public AccountingCustomer accountingCustomer;

  public Double amountInDollars;
  public ZonedDateTime date;
  public String description;
  public EnvironmentConfig.TransactionType transactionType;
  public String paymentGatewayName;
  public String paymentGatewayTransactionId;
  public Boolean recurring;

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
    this.accountingCustomer = new AccountingCustomer(contactId, crmContactId, null);

    this.amountInDollars = amountInDollars;
    this.date = date;
    this.description = description;
    this.transactionType = transactionType;
    this.paymentGatewayName = paymentGatewayName;
    this.paymentGatewayTransactionId = paymentGatewayTransactionId;
    this.recurring = recurring;
  }
}
