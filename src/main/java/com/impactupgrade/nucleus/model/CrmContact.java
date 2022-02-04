/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Strings;

import java.util.List;
import java.util.Locale;

public class CrmContact {

  public enum PreferredPhone {
    HOME(List.of("home", "household")),
    MOBILE(List.of("mobile")),
    WORK(List.of("work")),
    OTHER(List.of("other"));

    private final List<String> names;

    PreferredPhone(List<String> names) {
      this.names = names;
    }

    public static PreferredPhone fromName(String name) {
      if (Strings.isNullOrEmpty(name)) {
        return null;
      }

      if (HOME.names.contains(name.toLowerCase(Locale.ROOT))) {
        return HOME;
      } else if (WORK.names.contains(name.toLowerCase(Locale.ROOT))) {
        return WORK;
      } else if (OTHER.names.contains(name.toLowerCase(Locale.ROOT))) {
        return OTHER;
      } else {
        // default to mobile
        return MOBILE;
      }
    }
  }

  public String id;
  public String accountId;
  public String firstName;
  public String lastName;
  public String email;
  public String homePhone;
  public String mobilePhone;
  public String workPhone;
  public String otherPhone;
  public PreferredPhone preferredPhone;
  public CrmAddress address = new CrmAddress();
  public Boolean emailOptIn;
  public Boolean emailOptOut;
  public Boolean smsOptIn;
  public Boolean smsOptOut;
  public String ownerId;
  public String ownerName;
  public String totalDonationAmount;
  public String numDonations;
  public String lastDonationDate;
  public String notes;
  public List<String> emailGroups;

  @JsonIgnore
  public Object rawObject;

  public CrmContact() {}

  // A few cases where we only care about existence and require only the id.
  public CrmContact(String id) {
    this.id = id;
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmContact(String id, String accountId, String firstName, String lastName, String email, String homePhone,
      String mobilePhone, String workPhone, String otherPhone, PreferredPhone preferredPhone, CrmAddress address,
      Boolean emailOptIn, Boolean emailOptOut, Boolean smsOptIn, Boolean smsOptOut, String ownerId, String ownerName, String totalDonationAmount, String numDonations, String lastDonationDate,  List<String> emailGroups,
      Object rawObject) {
    this.id = id;
    this.accountId = accountId;
    this.firstName = firstName;
    this.lastName = lastName;
    this.email = email;
    this.homePhone = homePhone;
    this.mobilePhone = mobilePhone;
    this.workPhone = workPhone;
    this.otherPhone = otherPhone;
    this.preferredPhone = preferredPhone;
    this.address = address;
    this.emailOptIn = emailOptIn;
    this.emailOptOut = emailOptOut;
    this.smsOptIn = smsOptIn;
    this.smsOptOut = smsOptOut;
    this.ownerId = ownerId;
    this.ownerName = ownerName;
    this.totalDonationAmount = totalDonationAmount;
    this.numDonations = numDonations;
    this.lastDonationDate = lastDonationDate;
    this.emailGroups = emailGroups;
    this.rawObject = rawObject;
  }

  /**
   * True if the contact may receive email, false if not. Unlike SMS, the CANSPAM act does not require explicit opt-in.
   * If something GDPR-like comes about in the US, that will change. But for now, we can default to True.
   * If the org has an emailOptIn field defined and it has a value, use that as the priority. Otherwise,
   * check emailOptOut. If neither are defined, default to True.
   */
  public boolean canReceiveEmail() {
    if (emailOptIn != null) {
      return emailOptIn;
    } else if (emailOptOut != null) {
      return !emailOptOut;
    }
    return true;
  }
}
