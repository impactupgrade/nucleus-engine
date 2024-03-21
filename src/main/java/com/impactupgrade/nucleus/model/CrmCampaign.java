/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import java.time.ZonedDateTime;

public class CrmCampaign extends CrmRecord {

  public String name;
  public String externalReference;
  public ZonedDateTime startDate;
  public ZonedDateTime endDate;
  public String recordTypeId;
  public String recordTypeName;

  public CrmCampaign(String id, String name) {
    this.id = id;
    this.name = name;
  }

  public CrmCampaign(
      String id,
      String name,
      String externalReference,
      ZonedDateTime startDate,
      ZonedDateTime endDate,
      String recordTypeId,
      String recordTypeName,
      Object crmRawObject,
      String crmUrl
  ) {
    super(id, crmRawObject, crmUrl);

    this.name = name;
    this.externalReference = externalReference;
    this.startDate = startDate;
    this.endDate = endDate;
    this.recordTypeId = recordTypeId;
    this.recordTypeName = recordTypeName;
  }
}
