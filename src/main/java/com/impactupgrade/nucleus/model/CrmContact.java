/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public class CrmContact extends CrmRecord {

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

  public CrmAccount account = new CrmAccount();

  public CrmAddress mailingAddress = new CrmAddress();
  public String language;
  public String email;
  public List<String> emailGroups = new ArrayList<>();
  public Boolean emailOptIn;
  public Boolean emailOptOut;
  public Calendar firstDonationDate;
  public String firstName;
  public String homePhone;
  public Calendar lastDonationDate;
  public String lastName;
  public String mobilePhone;
  public String notes;
  public Integer numDonations;
  public String ownerId;
  public String ownerName;
  public PreferredPhone preferredPhone = PreferredPhone.MOBILE;
  public Boolean smsOptIn;
  public Boolean smsOptOut;
  public Double totalDonationAmount;
  public String workPhone;

  protected String fullNameOverride;

  // Using FP, allow this object to retrieve fields from its rawObject. Calls to the constructor provide a
  // CRM-specific function.
  @JsonIgnore
  public Function<String, Object> fieldFetcher;

  public CrmContact() {}

  // A few cases where we only care about existence and require only the id.
  public CrmContact(String id) {
    super(id);
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmContact(
      String id,
      CrmAccount account,
      CrmAddress mailingAddress,
      String email,
      List<String> emailGroups,
      Boolean emailOptIn,
      Boolean emailOptOut,
      Calendar firstDonationDate,
      String firstName,
      String homePhone,
      Calendar lastDonationDate,
      String lastName,
      String language,
      String mobilePhone,
      Integer numDonations,
      String ownerId,
      String ownerName,
      PreferredPhone preferredPhone,
      Boolean smsOptIn,
      Boolean smsOptOut,
      Double totalDonationAmount,
      String workPhone,
      Object crmRawObject,
      String crmUrl,
      Function<String, Object> fieldFetcher
  ) {
    super(id, crmRawObject, crmUrl);

    if (account != null) this.account = account;

    this.mailingAddress = mailingAddress;
    this.email = email;
    if (emailGroups != null) this.emailGroups = emailGroups;
    this.emailOptIn = emailOptIn;
    this.emailOptOut = emailOptOut;
    this.firstDonationDate = firstDonationDate;
    this.firstName = firstName;
    this.homePhone = homePhone;
    this.language = language;
    this.lastDonationDate = lastDonationDate;
    this.lastName = lastName;
    this.mobilePhone = mobilePhone;
    this.numDonations = numDonations;
    this.ownerId = ownerId;
    this.ownerName = ownerName;
    if (preferredPhone != null) this.preferredPhone = preferredPhone;
    this.smsOptIn = smsOptIn;
    this.smsOptOut = smsOptOut;
    this.totalDonationAmount = totalDonationAmount;
    this.workPhone = workPhone;

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

  public void setFullNameOverride(String fullNameOverride) {
    this.fullNameOverride = fullNameOverride;
  }

  public String getFullName() {
    if (!Strings.isNullOrEmpty(fullNameOverride)) {
      return fullNameOverride;
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
