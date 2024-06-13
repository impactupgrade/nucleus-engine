package com.impactupgrade.nucleus.model;

public class AccountingCustomer {

  public String contactId;
  public String crmContactId;
  public String fullName;

  public AccountingCustomer(String contactId, String crmContactId, String fullName) {
    this.contactId = contactId;
    this.crmContactId = crmContactId;
    this.fullName = fullName;
  }
}
