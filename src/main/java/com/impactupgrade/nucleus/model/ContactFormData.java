/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import javax.ws.rs.FormParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ContactFormData {

  @FormParam("fname") String firstName;
  @FormParam("lname") String lastName;
  @FormParam("email") String email;
  @FormParam("phone") Optional<String> phone;
  @FormParam("email-opt-in") Boolean emailOptIn;
  @FormParam("sms-opt-in") Boolean smsOptIn;
  @FormParam("message") Optional<String> message;
  @FormParam("record_id") Optional<String> recordId;
  @FormParam("record_title") Optional<String> recordTitle;

  public CrmContact toCrmContact() {
    CrmContact crmContact = new CrmContact();
    crmContact.firstName = this.firstName;
    crmContact.lastName = this.lastName;
    crmContact.email = this.email;
    crmContact.emailOptIn = this.emailOptIn;
    crmContact.emailOptOut = !this.emailOptIn;

    if (this.phone.isPresent()) {
      crmContact.mobilePhone = this.phone.get();
      crmContact.preferredPhone = CrmContact.PreferredPhone.MOBILE;
      // only set if a phone number is provided
      crmContact.smsOptIn = this.smsOptIn;
      crmContact.smsOptOut = !this.smsOptIn;
    }

    List<String> notesList = new ArrayList<>();
    if (this.message.isPresent()) {
      notesList.add(this.message.get());
    }
    if (this.recordTitle.isPresent()) {
      notesList.add(this.recordId.get());
    }
    if (this.recordId.isPresent()) {
      notesList.add(this.recordTitle.get());
    }
    crmContact.notes = String.join(" / ", notesList);

    return crmContact;
  }

  @Override
  public String toString() {
    return "ContactFormData{" +
            "firstName='" + firstName + '\'' +
            ", lastName='" + lastName + '\'' +
            ", email='" + email + '\'' +
            ", phone='" + phone + '\'' +
            ", emailOptIn=" + emailOptIn +
            ", smsOptIn=" + smsOptIn +
            ", message=" + message +
            '}';
  }
}
