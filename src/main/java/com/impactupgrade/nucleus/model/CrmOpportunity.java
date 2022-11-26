package com.impactupgrade.nucleus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Calendar;

public class CrmOpportunity {
  public String id;
  public String name;
  public Calendar closeDate;
  public String notes;
  public String campaignId;
  public String ownerId;
  public String recordTypeId;

  public CrmAccount account;
  public CrmContact contact;

  @JsonIgnore
  public Object rawObject;
  @JsonIgnore
  public String crmUrl;

  public CrmOpportunity() {}

  // A few cases where we only care about existence and require only the id.
  public CrmOpportunity(String id) {
    this.id = id;
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmOpportunity(String id, String name, Calendar closeDate, String notes, String campaignId, String ownerId,
      String recordTypeId, CrmAccount account, CrmContact contact, Object rawObject, String crmUrl) {
    this.id = id;
    this.name = name;
    this.closeDate = closeDate;
    this.notes = notes;
    this.campaignId = campaignId;
    this.ownerId = ownerId;
    this.recordTypeId = recordTypeId;
    this.rawObject = rawObject;
    this.crmUrl = crmUrl;
    this.account = account;
    this.contact = contact;
  }
}
