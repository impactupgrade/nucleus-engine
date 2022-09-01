/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Strings;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

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
  public String fullName;
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
  public Double totalDonationAmount;
  public Integer numDonations;
  public Calendar firstDonationDate;
  public Calendar lastDonationDate;
  public String notes;
  public List<String> emailGroups;
  public String contactLanguage;

  @JsonIgnore
  public Object rawObject;
  @JsonIgnore
  public String crmUrl;
  // Using FP, allow this object to retrieve fields from its rawObject. Calls to the constructor provide a
  // CRM-specific function.
  @JsonIgnore
  public Function<String, Object> fieldFetcher;

  // Mainly to allow orgs to provide custom context at the CrmService level, available for processing back up at the
  // logic service or controller level.
  @JsonIgnore
  public Map<String, Object> additionalContext = new HashMap<>();

  public CrmContact() {}

  // A few cases where we only care about existence and require only the id.
  public CrmContact(String id) {
    this.id = id;
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmContact(String id, String accountId, String firstName, String lastName, String fullName, String email, String homePhone,
      String mobilePhone, String workPhone, String otherPhone, PreferredPhone preferredPhone, CrmAddress address,
      Boolean emailOptIn, Boolean emailOptOut, Boolean smsOptIn, Boolean smsOptOut, String ownerId, String ownerName, Double totalDonationAmount, Integer numDonations, Calendar firstDonationDate, Calendar lastDonationDate,  List<String> emailGroups, String contactLanguage,
      Object rawObject, String crmUrl, Function<String, Object> fieldFetcher) {
    this.id = id;
    this.accountId = accountId;
    this.firstName = firstName;
    this.lastName = lastName;
    this.fullName = fullName;
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
    this.firstDonationDate = firstDonationDate;
    this.lastDonationDate = lastDonationDate;
    this.emailGroups = emailGroups;
    this.contactLanguage = contactLanguage;
    this.rawObject = rawObject;
    this.crmUrl = crmUrl;
    this.fieldFetcher = fieldFetcher;
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

  public String fullName() {
    if (!Strings.isNullOrEmpty(fullName)) {
      return fullName;
    }
    return firstName + " " + lastName;
  }

  public String phoneNumberForSMS() {
    if (!Strings.isNullOrEmpty(mobilePhone)) {
      return mobilePhone;
    }
    return homePhone;
  }
}
