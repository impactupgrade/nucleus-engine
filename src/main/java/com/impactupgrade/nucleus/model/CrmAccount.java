/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class CrmAccount {

  public enum Type {
    HOUSEHOLD, ORGANIZATION
  }

  public String id;
  public String name;
  public String email;
  public CrmAddress address = new CrmAddress();
  public Type type;

  @JsonIgnore
  public Object rawObject;

  public CrmAccount() {}

  // A few cases where we only care about existence and require only the id.
  public CrmAccount(String id) {
    this.id = id;
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmAccount(String id, String name, String email, CrmAddress address, Type type, Object rawObject) {
    this.id = id;
    this.name = name;
    this.email = email;
    this.address = address;
    this.type = type;
    this.rawObject = rawObject;
  }
}
