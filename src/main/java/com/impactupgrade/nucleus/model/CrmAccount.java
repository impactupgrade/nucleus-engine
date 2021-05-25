package com.impactupgrade.nucleus.model;

public class CrmAccount {
  public String id;
  public String name;

  public CrmAddress address = new CrmAddress();

  public CrmAccount() {}

  public CrmAccount(String id) {
    this.id = id;
  }
}
