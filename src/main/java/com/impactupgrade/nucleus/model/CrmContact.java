/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import java.util.List;
import java.util.Map;

public class CrmContact {

  public enum PreferredPhone {
    HOME, MOBILE, WORK
  }

  public String id;
  public String accountId;
  public String firstName;
  public String lastName;
  public String email;
  public String homePhone;
  public String mobilePhone;
  public String workPhone;
  public PreferredPhone preferredPhone;
  public CrmAddress address;
  public Boolean emailOptIn;
  public Boolean emailOptOut;
  public Boolean smsOptIn;
  public Boolean smsOptOut;
  public String ownerId;
  public String notes;
  public List<String> groups;
  public Object rawObject;

  public CrmContact() {}

  // A few cases where we only care about existence and require only the id.
  public CrmContact(String id) {
    this.id = id;
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmContact(String id, String accountId, String firstName, String lastName, String email, String homePhone,
      String mobilePhone, String workPhone, PreferredPhone preferredPhone, CrmAddress address,
      Boolean emailOptIn, Boolean emailOptOut, Boolean smsOptIn, Boolean smsOptOut, String ownerId, List<String> groups,
      Object rawObject) {
    this.id = id;
    this.accountId = accountId;
    this.firstName = firstName;
    this.lastName = lastName;
    this.email = email;
    this.homePhone = homePhone;
    this.mobilePhone = mobilePhone;
    this.workPhone = workPhone;
    this.preferredPhone = preferredPhone;
    this.address = address;
    this.emailOptIn = emailOptIn;
    this.emailOptOut = emailOptOut;
    this.smsOptIn = smsOptIn;
    this.smsOptOut = smsOptOut;
    this.ownerId = ownerId;
    this.groups = groups;
    this.rawObject = rawObject;
  }
}
