/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.TwilioClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.util.Utils;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Message;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessagingService {

  private final Environment env;
  private final TwilioClient twilioClient;
  private final CrmService crmService;

  public MessagingService(Environment env) {
    this.env = env;
    twilioClient = env.twilioClient();
    crmService = env.messagingCrmService();
  }

  public void sendMessage(String message, String attachmentUrl, CrmContact crmContact, String sender) {
    try {
      String pn = crmContact.phoneNumberForSMS();
      pn = pn.replaceAll("[^0-9\\+]", "");

      if (!Strings.isNullOrEmpty(pn)) {
        String personalizedMessage = personalizeMessage(message, crmContact);

        Message twilioMessage = twilioClient.sendMessage(pn, sender, personalizedMessage, attachmentUrl, null);

        env.logJobInfo("sent messageSid {} to {}; status={} errorCode={} errorMessage={}", twilioMessage.getSid(), pn, twilioMessage.getStatus(), twilioMessage.getErrorCode(), twilioMessage.getErrorMessage());
      }
    } catch (ApiException e1) {
      if (e1.getCode() == 21610) {
        env.logJobInfo("message to {} failed due to blacklist; updating contact in CRM", crmContact.phoneNumberForSMS());
        try {
          optOut(crmContact);
        } catch (Exception e2) {
          env.logJobError("CRM contact update failed", e2);
        }
      } else if (e1.getCode() == 21408 || e1.getCode() == 21211) {
        env.logJobInfo("invalid phone number: {}; updating contact in CRM", crmContact.phoneNumberForSMS());
        try {
          optOut(crmContact);
        } catch (Exception e2) {
          env.logJobError("CRM contact update failed", e2);
        }
      } else {
        env.logJobWarn("message to {} failed: {}", crmContact.phoneNumberForSMS(), e1.getCode(), e1);
      }
    } catch (Exception e) {
      env.logJobWarn("message to {} failed", crmContact.phoneNumberForSMS(), e);
    }
  }

  protected String personalizeMessage(String message, CrmContact crmContact) {
    if (Strings.isNullOrEmpty(message) || Objects.isNull(crmContact)) {
      return message;
    }

    Pattern p = Pattern.compile("(\\{\\{[^\\}\\}]+\\}\\})+");
    Matcher m = p.matcher(message);
    while (m.find()) {
      String fieldName = m.group(0).replaceAll("\\{\\{", "").replaceAll("\\}\\}", "");

      Object value;
      // directly support a few of the common ones
      if ("FirstName".equalsIgnoreCase(fieldName)) {
        value = crmContact.firstName;
      } else if ("LastName".equalsIgnoreCase(fieldName)) {
        value = crmContact.lastName;
      } else if ("ContactId".equalsIgnoreCase(fieldName)) {
        value = crmContact.id;
      } else if ("AccountId".equalsIgnoreCase(fieldName)) {
        value = crmContact.account.id;
      } else {
        value = crmContact.fieldFetcher.apply(fieldName);
      }
      // TODO: will probably need additional formatting for numerics, dates, times, etc.
      String valueString = value == null ? "" : value.toString();
      message = message.replaceAll("\\{\\{" + fieldName + "\\}\\}", valueString);
    }

    return message;
  }

  public CrmContact processSignup(
      String phone,
      String firstName,
      String lastName,
      String email,
      String __emailOptIn,
      String __smsOptIn,
      String language,
      String campaignId,
      String listId,
      Map<String, Object> customResponses
  ) throws Exception {
    // They'll send "no", etc. for email if they don't want to opt-in. Simply look for @, to be flexible.
    if (email != null && !email.contains("@")) {
      email = null;
    }

    // First, look for an existing contact. Important to fall back on the PN since the
    // Twilio Studio flow has some flavors that assume the contact is already in the CRM, so only the PN (From) will be
    // provided. Other flows allow email to be optional.
    CrmContact crmContact = null;
    if (!Strings.isNullOrEmpty(email) && !"no".equalsIgnoreCase(email)) {
      crmContact = crmService.searchContacts(ContactSearch.byEmail(email)).getSingleResult().orElse(null);
    }
    if (crmContact == null) {
      crmContact = crmService.searchContacts(ContactSearch.byPhone(phone)).getSingleResult().orElse(null);
    }

    // if the flow didn't include an explicit email opt-in process, safe to assume it's fine if email is present
    boolean emailOptIn;
    if (Strings.isNullOrEmpty(__emailOptIn)) {
      emailOptIn = email != null && email.contains("@");
    } else {
      emailOptIn = Utils.checkboxToBool(__emailOptIn);
    }
    // if the flow didn't include an explicit sms opt-in process, we assume this was a general-purpose signup process and opt-in was a given
    boolean smsOptIn;
    if (Strings.isNullOrEmpty(__smsOptIn)) {
      smsOptIn = true;
    } else {
      smsOptIn = Utils.checkboxToBool(__smsOptIn);
    }

    if (crmContact == null) {
      // Didn't exist, so attempt to create it.
      crmContact = new CrmContact();
      crmContact.mobilePhone = phone;
      crmContact.firstName = firstName;
      if (!Strings.isNullOrEmpty(lastName)) {
        crmContact.lastName = lastName;
      } else {
        // required field, so use the phone number if we have nothing else
        crmContact.lastName = phone;
      }
      crmContact.email = email;
      crmContact.emailOptIn = emailOptIn;
      crmContact.smsOptIn = smsOptIn;
      crmContact.language = language;
      crmContact.crmRawFieldsToSet = customResponses;
      crmContact.id = crmService.insertContact(crmContact);
    } else {
      // Existed, so use it
      env.logJobInfo("contact already existed in CRM: {}", crmContact.id);

      boolean update = emailOptIn || smsOptIn;

      crmContact.emailOptIn = emailOptIn;
      crmContact.smsOptIn = smsOptIn;

      if (Strings.isNullOrEmpty(crmContact.firstName) && !Strings.isNullOrEmpty(firstName)) {
        env.logJobInfo("contact {} missing firstName; updating it...", crmContact.id);
        crmContact.firstName = firstName;
        update = true;
      }
      if (Strings.isNullOrEmpty(crmContact.lastName) && !Strings.isNullOrEmpty(lastName)) {
        env.logJobInfo("contact {} missing lastName; updating it...", crmContact.id);
        crmContact.lastName = lastName;
        update = true;
      }
      if (Strings.isNullOrEmpty(crmContact.email) && !Strings.isNullOrEmpty(email)) {
        env.logJobInfo("contact {} missing email; updating it...", crmContact.id);
        crmContact.email = email;
        update = true;
      }
      if (Strings.isNullOrEmpty(crmContact.mobilePhone) && !Strings.isNullOrEmpty(phone)) {
        env.logJobInfo("contact {} missing mobilePhone; updating it...", crmContact.id);
        crmContact.mobilePhone = phone;
        update = true;
      }

      if (!customResponses.equals(Collections.emptyMap())){
        env.logJobInfo("Updating custom response fields for contact {}", crmContact.id);
        crmContact.crmRawFieldsToSet = customResponses;
        update = true;
      }

      if (update) {
        crmService.updateContact(crmContact);
      }
    }

    if (!Strings.isNullOrEmpty(campaignId)) {
      crmService.addContactToCampaign(crmContact, campaignId, null);
    }

    if (!Strings.isNullOrEmpty(listId)) {
      crmService.addContactToList(crmContact, listId);
    }

    return crmContact;
  }

  public void optIn(String phone) throws Exception {
    // First, look for an existing contact with the PN
    CrmContact crmContact = crmService.searchContacts(ContactSearch.byPhone(phone)).getSingleResult().orElse(null);
    if (crmContact != null) {
      env.logJobInfo("opting {} ({}) into sms...", crmContact.id, phone);
      crmContact.smsOptIn = true;
      crmContact.smsOptOut = false;
      crmService.updateContact(crmContact);
    } else {
      // TODO: There MIGHT be value in processing this as a signup and inserting the Contact...
      env.logJobInfo("unable to find a CRM contact with phone number {}", phone);
    }
  }

  public void optOut(String phone) throws Exception {
    // First, look for an existing contact with the PN
    CrmContact crmContact = crmService.searchContacts(ContactSearch.byPhone(phone)).getSingleResult().orElse(null);
    if (crmContact != null) {
      optOut(crmContact);
    } else {
      env.logJobInfo("unable to find a CRM contact with phone number {}", phone);
    }
  }

  public void optOut(CrmContact crmContact) throws Exception {
    env.logJobInfo("opting {} ({}) out of sms...", crmContact.id, crmContact.mobilePhone);
    crmContact.smsOptIn = false;
    crmContact.smsOptOut = true;
    crmService.updateContact(crmContact);
  }
}
