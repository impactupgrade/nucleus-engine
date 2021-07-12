/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

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
  public CrmAddress address = new CrmAddress();

  public Boolean emailOptIn;
  public Boolean emailOptOut;
  public Boolean smsOptIn;
  public Boolean smsOptOut;

  public String ownerId;

  public CrmContact() {}

  public CrmContact(String id) {
    this.id = id;
  }
}
