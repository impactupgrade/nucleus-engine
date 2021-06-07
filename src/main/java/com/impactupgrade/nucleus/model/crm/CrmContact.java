/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model.crm;

public class CrmContact {
  public String id;
  public String accountId;

  public String firstName;
  public String lastName;

  public String email;
  public String phone;

  public Boolean emailOptIn;
  public Boolean smsOptIn;

  public CrmAddress address = new CrmAddress();

  public CrmContact() {}

  public CrmContact(String id) {
    this.id = id;
  }
}
