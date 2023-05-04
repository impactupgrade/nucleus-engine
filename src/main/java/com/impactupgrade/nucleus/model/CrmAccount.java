/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.impactupgrade.nucleus.environment.EnvironmentConfig;

public class CrmAccount extends CrmRecord {

  public CrmAddress billingAddress = new CrmAddress();
  public String description;
  public CrmAddress mailingAddress = new CrmAddress();
  public String name;
  public String ownerId;
  public String phone;
  public EnvironmentConfig.AccountType recordType = EnvironmentConfig.AccountType.HOUSEHOLD;
  // sometimes there is a deeper breakdown
  public String recordTypeId;
  public String recordTypeName;
  public String type;
  public String website;

  public CrmAccount() {}

  // A few cases where we only care about existence and require only the id.
  public CrmAccount(String id) {
    super(id);
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmAccount(
      String id,
      CrmAddress billingAddress,
      String description,
      CrmAddress mailingAddress,
      String name,
      String ownerId,
      String phone,
      EnvironmentConfig.AccountType recordType,
      String recordTypeId,
      String recordTypeName,
      String type,
      String website,
      Object crmRawObject,
      String crmUrl
  ) {
    super(id, crmRawObject, crmUrl);

    this.billingAddress = billingAddress;
    this.description = description;
    this.mailingAddress = mailingAddress;
    this.name = name;
    this.ownerId = ownerId;
    this.phone = phone;
    if (recordType != null) this.recordType = recordType;
    this.recordTypeId = recordTypeId;
    this.recordTypeName = recordTypeName;
    this.type = type;
    this.website = website;
  }
}
