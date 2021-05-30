/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

public class OpportunityEvent {
  private CrmContact crmContact;
  private String name;
  private String recordTypeId;
  private String ownerId;
  private String campaignId;

  public CrmContact getCrmContact() {
    return crmContact;
  }

  public void setCrmContact(CrmContact crmContact) {
    this.crmContact = crmContact;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getRecordTypeId() {
    return recordTypeId;
  }

  public void setRecordTypeId(String recordTypeId) {
    this.recordTypeId = recordTypeId;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(String ownerId) {
    this.ownerId = ownerId;
  }

  public String getCampaignId() {
    return campaignId;
  }

  public void setCampaignId(String campaignId) {
    this.campaignId = campaignId;
  }
}
