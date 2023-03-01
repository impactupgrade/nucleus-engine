/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.impactupgrade.nucleus.environment.EnvironmentConfig;

public class CrmAccount extends CrmRecord {

  public CrmAddress billingAddress = new CrmAddress();
  public CrmAddress mailingAddress = new CrmAddress();
  public String name;
  public EnvironmentConfig.AccountType type = EnvironmentConfig.AccountType.HOUSEHOLD;
  // sometimes there is a deeper breakdown
  public String typeName;

  public CrmAccount() {}

  // A few cases where we only care about existence and require only the id.
  public CrmAccount(String id) {
    super(id);
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmAccount(
      String id,
      CrmAddress billingAddress,
      CrmAddress mailingAddress,
      String name,
      EnvironmentConfig.AccountType type,
      String typeName,
      Object crmRawObject,
      String crmUrl
  ) {
    super(id, crmRawObject, crmUrl);

    this.billingAddress = billingAddress;
    this.mailingAddress = mailingAddress;
    this.name = name;
    if (type != null) this.type = type;
    this.typeName = typeName;
  }
}
