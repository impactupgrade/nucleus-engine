package com.impactupgrade.nucleus.model;

import java.time.ZonedDateTime;

/**
 * AccountingService has some rather complex logic to tie together deposits, transactions, contacts/accounts, etc.
 * Rather than complicating the methods and arguments, combine processing into this model.
 */
public class AccountingTransaction {

  public Double amountInDollars;
  public ZonedDateTime date;
  public String description;
  public Boolean recurring;

  public String paymentGatewayName;
  public String paymentGatewayTransactionId;

  // TODO: Rename? "Contact" in Xero, but something else in QB?
  public String contactId;
  public String crmContactId;

  public AccountingTransaction(Double amountInDollars, ZonedDateTime date, String description, Boolean recurring, String paymentGatewayName, String paymentGatewayTransactionId, String contactId, String crmContactId) {
    this.amountInDollars = amountInDollars;
    this.date = date;
    this.description = description;
    this.recurring = recurring;
    this.paymentGatewayName = paymentGatewayName;
    this.paymentGatewayTransactionId = paymentGatewayTransactionId;
    this.contactId = contactId;
    this.crmContactId = crmContactId;
  }
}
