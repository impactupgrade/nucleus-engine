/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import java.time.ZonedDateTime;

public class CrmOpportunity extends CrmRecord {
  public CrmAccount account = new CrmAccount();
  public CrmContact contact = new CrmContact();

  public String campaignId;
  public ZonedDateTime closeDate;
  public String description;
  public String name;
  public String ownerId;
  public String recordTypeId;

  public CrmOpportunity() {}

  // A few cases where we only care about existence and require only the id.
  public CrmOpportunity(String id) {
    super(id);
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmOpportunity(
      String id,
      CrmAccount account,
      CrmContact contact,
      String campaignId,
      ZonedDateTime closeDate,
      String description,
      String name,
      String ownerId,
      String recordTypeId,
      Object crmRawObject,
      String crmUrl
  ) {
    super(id, crmRawObject, crmUrl);

    if (account != null) this.account = account;
    if (contact != null) this.contact = contact;

    this.campaignId = campaignId;
    this.closeDate = closeDate;
    this.description = description;
    this.name = name;
    this.ownerId = ownerId;
    this.recordTypeId = recordTypeId;
  }
}
