/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model.crm;

public class CrmAccount {
  public String id;
  public String name;

  public CrmAddress address = new CrmAddress();

  public CrmAccount() {}

  public CrmAccount(String id) {
    this.id = id;
  }
}
