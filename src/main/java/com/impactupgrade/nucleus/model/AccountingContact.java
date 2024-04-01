package com.impactupgrade.nucleus.model;

// TODO: Rename? "Contact" in Xero, but something else in QB?
public class AccountingContact {

  public String contactId;
  public String crmContactId;
  public String fullName;

  public AccountingContact(String contactId, String crmContactId, String fullName) {
    this.contactId = contactId;
    this.crmContactId = crmContactId;
    this.fullName = fullName;
  }
}
