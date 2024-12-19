/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.impactupgrade.nucleus.environment.EnvironmentConfig;

public class CrmAccount extends CrmRecord {

  public CrmAddress billingAddress = new CrmAddress();
  public String description;
  public String email;
  public Boolean emailOptIn;
  public Boolean emailOptOut;
  public Boolean emailBounced;
  public CrmAddress mailingAddress = new CrmAddress();
  public String name;
  public String ownerId;
  public String phone;
  public EnvironmentConfig.AccountType recordType = EnvironmentConfig.AccountType.HOUSEHOLD;
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
      String email,
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
    super(id, recordTypeId, recordTypeName, crmRawObject, crmUrl);

    this.billingAddress = billingAddress;
    this.description = description;
    this.email = email;
    this.mailingAddress = mailingAddress;
    this.name = name;
    this.ownerId = ownerId;
    this.phone = phone;
    if (recordType != null) this.recordType = recordType;
    this.type = type;
    this.website = website;
  }

  /**
   * True if the contact may receive email, false if not. Unlike SMS, the CANSPAM act does not require explicit opt-in.
   * If something GDPR-like comes about in the US, that will change. But for now, we can default to True.
   * If the org has an emailOptIn field defined and it has a value, use that as the priority. Otherwise,
   * check emailOptOut. If neither are defined, default to True.
   */
  public boolean canReceiveEmail() {
    if (emailOptOut != null && emailOptOut) {
      return false;
    }
    if (emailBounced != null && emailBounced) {
      return false;
    }
    if (emailOptIn != null) {
      return emailOptIn;
    }
    return true;
  }
}
