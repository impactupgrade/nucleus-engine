/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

public class CrmCampaign {

  public CrmCampaign(String id, String name, String externalReference) {
    this.id = id;
    this.name = name;
    this.externalReference = externalReference;
  }

  public CrmCampaign(String id, String name) {
    this(id, name, null);
  }

  public String id;
  public String name;
  public String externalReference;

}
