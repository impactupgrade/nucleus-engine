/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;

import javax.ws.rs.FormParam;
import java.util.ArrayList;
import java.util.List;

public class ContactFormData {

  @FormParam("fname") String firstName;
  @FormParam("lname") String lastName;
  @FormParam("email") String email;
  @FormParam("phone") String phone;
  @FormParam("email-opt-in") Boolean emailOptIn;
  @FormParam("sms-opt-in") Boolean smsOptIn;
  @FormParam("message") String message;

  @FormParam("context-id") String contextId;
  @FormParam("context-name") String contextName;

  public CrmContact toCrmContact() {
    CrmContact crmContact = new CrmContact();
    crmContact.firstName = firstName;
    crmContact.lastName = lastName;
    crmContact.email = email;
    crmContact.emailOptIn = emailOptIn;

    if (!Strings.isNullOrEmpty(phone)) {
      crmContact.mobilePhone = phone;
      crmContact.preferredPhone = CrmContact.PreferredPhone.MOBILE;
      // only set if a phone number is provided
      crmContact.smsOptIn = smsOptIn;
    }

    List<String> notesList = new ArrayList<>();
    if (!Strings.isNullOrEmpty(message)) {
      notesList.add(message);
    }
    if (!Strings.isNullOrEmpty(contextId)) {
      notesList.add(contextId);
    }
    if (!Strings.isNullOrEmpty(contextName)) {
      notesList.add(contextName);
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
        ", message='" + message + '\'' +
        ", contextId='" + contextId + '\'' +
        ", contextName='" + contextName + '\'' +
        '}';
  }
}
