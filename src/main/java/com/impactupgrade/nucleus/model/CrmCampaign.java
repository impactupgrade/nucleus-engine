/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

public class CrmCampaign extends CrmRecord {

  public String name;
  public String externalReference;

  public CrmCampaign() {
  }

  public CrmCampaign(String id) {
    super(id);
  }

  public CrmCampaign(String id, String name) {
    this.id = id;
    this.name = name;
  }

  public CrmCampaign(
      String id,
      String name,
      String externalReference,
      Object crmRawObject,
      String crmUrl
  ) {
    super(id, crmRawObject, crmUrl);

    this.name = name;
    this.externalReference = externalReference;
  }
}
