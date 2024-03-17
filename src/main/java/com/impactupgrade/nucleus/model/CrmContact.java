/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public class CrmContact extends CrmRecord {

  public enum PreferredEmail {
    HOME(List.of("home", "household")),
    WORK(List.of("work")),
    OTHER(List.of("other"));

    private final List<String> names;

    PreferredEmail(List<String> names) {
      this.names = names;
    }

    public static PreferredEmail fromName(String name) {
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
        // default to home
        return HOME;
      }
    }
  }

  public enum PreferredPhone {
    HOME(List.of("home", "household")),
    MOBILE(List.of("mobile")),
    WORK(List.of("work"));

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
      } else {
        // default to mobile
        return MOBILE;
      }
    }
  }

  public CrmAccount account = new CrmAccount();

  public String description;
//  public String email; // TODO: search for uses and replace
  public List<String> emailGroups = new ArrayList<>();
  public Boolean emailOptIn;
  public Boolean emailOptOut;
  public Boolean emailBounced;
  public Calendar firstDonationDate;
  public String firstName;
  public String homeEmail;
  public String homePhone;
  public String language;
  public Double largestDonationAmount;
  public Calendar lastDonationDate;
  public String lastName;
  public CrmAddress mailingAddress = new CrmAddress();
  public String mobilePhone;
  public String notes;
  public Integer numDonations;
  public Integer numDonationsYtd;
  public String otherEmail;
  public String ownerId;
  public String ownerName;
  public PreferredEmail preferredEmail = PreferredEmail.HOME;
  public PreferredPhone preferredPhone = PreferredPhone.MOBILE;
  public String salutation;
  public Boolean smsOptIn;
  public Boolean smsOptOut;
  public String title;
  public Double totalDonationAmount;
  public Double totalDonationAmountYtd;
  public String workEmail;
  public String workPhone;

  protected String fullNameOverride;

  public CrmContact() {}

  // A few cases where we only care about existence and require only the id.
  public CrmContact(String id) {
    super(id);
  }

  // Keep this up to date! Creates a contract with all required fields, helpful for mapping.
  public CrmContact(
      String id,
      CrmAccount account,
      String description,
      String email,
      List<String> emailGroups,
      Boolean emailBounced,
      Boolean emailOptIn,
      Boolean emailOptOut,
      Calendar firstDonationDate,
      String firstName,
      String homePhone,
      Double largestDonationAmount,
      Calendar lastDonationDate,
      String lastName,
      String language,
      CrmAddress mailingAddress,
      String mobilePhone,
      Integer numDonations,
      Integer numDonationsYtd,
      String ownerId,
      String ownerName,
      PreferredPhone preferredPhone,
      String salutation,
      Boolean smsOptIn,
      Boolean smsOptOut,
      String title,
      Double totalDonationAmount,
      Double totalDonationAmountYtd,
      String workPhone,
      Object crmRawObject,
      String crmUrl,
      Function<String, Object> fieldFetcher
  ) {
    super(id, crmRawObject, crmUrl, fieldFetcher);

    if (account != null) this.account = account;

    this.description = description;
    this.email = email;
    if (emailGroups != null) this.emailGroups = emailGroups;
    this.emailBounced = emailBounced;
    this.emailOptIn = emailOptIn;
    this.emailOptOut = emailOptOut;
    this.firstDonationDate = firstDonationDate;
    this.firstName = firstName;
    this.homePhone = homePhone;
    this.language = language;
    this.largestDonationAmount = largestDonationAmount;
    this.lastDonationDate = lastDonationDate;
    this.lastName = lastName;
    this.mailingAddress = mailingAddress;
    this.mobilePhone = mobilePhone;
    this.numDonations = numDonations;
    this.numDonationsYtd = numDonationsYtd;
    this.ownerId = ownerId;
    this.ownerName = ownerName;
    if (preferredPhone != null) this.preferredPhone = preferredPhone;
    this.salutation = salutation;
    this.smsOptIn = smsOptIn;
    this.smsOptOut = smsOptOut;
    this.title = title;
    this.totalDonationAmount = totalDonationAmount;
    this.totalDonationAmountYtd = totalDonationAmountYtd;
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
    if (emailOptOut != null && emailOptOut) {
      return false;
    }
    if (emailBounced != null && emailBounced) {
      return false;
    }
    if (emailOptIn != null) {
      return emailOptIn;
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

  public String email() {
    String email = switch (preferredEmail) {
      case WORK -> workEmail;
      case OTHER -> otherEmail;
      default -> homeEmail;
    };

    if (Strings.isNullOrEmpty(email)) {
      email = homeEmail;
    }
    if (Strings.isNullOrEmpty(email)) {
      email = workEmail;
    }
    if (Strings.isNullOrEmpty(email)) {
      email = otherEmail;
    }

    return email;
  }

  public String phone() {
    String phone = switch (preferredPhone) {
      case WORK -> workPhone;
      case HOME -> homePhone;
      default -> mobilePhone;
    };

    if (Strings.isNullOrEmpty(phone)) {
      phone = mobilePhone;
    }
    if (Strings.isNullOrEmpty(phone)) {
      phone = homePhone;
    }
    if (Strings.isNullOrEmpty(phone)) {
      phone = workPhone;
    }

    return phone;
  }

  public String phoneNumberForSMS() {
    String pn = mobilePhone;
    if (Strings.isNullOrEmpty(pn)) {
      pn = homePhone;
    }
    if (Strings.isNullOrEmpty(pn)) {
      return null;
    }

    pn = pn.replaceAll("[^0-9+]", "");
    // obviously US specific
    if (pn.length() == 10) {
      pn = "+1" + pn;
    } else if (pn.length() == 11) {
      pn = "+" + pn;
    }
    return pn;
  }
}
