/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.OpportunityEvent;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.util.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MessagingService {

  private static final Logger log = LogManager.getLogger(MessagingService.class);

  private final CrmService crmService;

  public MessagingService(Environment env) {
    crmService = env.messagingCrmService();
  }

  public void processSignup(
      OpportunityEvent opportunityEvent,
      String phone,
      String firstName,
      String lastName,
      String email,
      String __emailOptIn,
      String __smsOptIn,
      String campaignId,
      String listId
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
      crmContact = crmService.getContactByEmail(email).orElse(null);
    }
    if (crmContact == null) {
      crmContact = crmService.getContactByPhone(phone).orElse(null);
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
      crmContact.lastName = lastName;
      crmContact.email = email;

      crmContact.emailOptIn = emailOptIn;
      crmContact.smsOptIn = smsOptIn;

      opportunityEvent.setCrmContact(crmContact);

      crmContact.id = crmService.insertContact(opportunityEvent);
    } else {
      // Existed, so use it
      log.info("contact already existed in CRM: {}", crmContact.id);

      opportunityEvent.setCrmContact(crmContact);

      boolean update = emailOptIn || smsOptIn;

      opportunityEvent.getCrmContact().emailOptIn = emailOptIn;
      opportunityEvent.getCrmContact().smsOptIn = smsOptIn;

      if (Strings.isNullOrEmpty(crmContact.firstName) && !Strings.isNullOrEmpty(firstName)) {
        log.info("contact {} missing firstName; updating it...", crmContact.id);
        opportunityEvent.getCrmContact().firstName = firstName;
        update = true;
      }
      if (Strings.isNullOrEmpty(crmContact.lastName) && !Strings.isNullOrEmpty(lastName)) {
        log.info("contact {} missing lastName; updating it...", crmContact.id);
        opportunityEvent.getCrmContact().lastName = lastName;
        update = true;
      }
      if (Strings.isNullOrEmpty(crmContact.email) && !Strings.isNullOrEmpty(email)) {
        log.info("contact {} missing email; updating it...", crmContact.id);
        opportunityEvent.getCrmContact().email = email;
        update = true;
      }
      if (Strings.isNullOrEmpty(crmContact.mobilePhone) && !Strings.isNullOrEmpty(phone)) {
        log.info("contact {} missing mobilePhone; updating it...", crmContact.id);
        opportunityEvent.getCrmContact().mobilePhone = phone;
        update = true;
      }

      if (update) {
        crmService.updateContact(opportunityEvent);
      }
    }

    if (!Strings.isNullOrEmpty(campaignId)) {
      crmService.addContactToCampaign(crmContact, campaignId);
    }

    if (!Strings.isNullOrEmpty(listId)) {
      crmService.addContactToList(crmContact, listId);
    }
  }

  public void optIn(String phone) throws Exception {
    // First, look for an existing contact with the PN
    CrmContact crmContact = crmService.getContactByPhone(phone).orElse(null);
    if (crmContact != null) {
      log.info("opting {} ({}) into sms...", crmContact.id, phone);
      crmContact.smsOptIn = true;
      crmContact.smsOptOut = false;
      crmService.updateContact(crmContact);
    } else {
      // TODO: There MIGHT be value in processing this as a signup and inserting the Contact...
      log.info("unable to find a CRM contact with phone number {}", phone);
    }
  }

  public void optOut(String phone) throws Exception {
    // First, look for an existing contact with the PN
    CrmContact crmContact = crmService.getContactByPhone(phone).orElse(null);
    if (crmContact != null) {
      optOut(crmContact);
    } else {
      log.info("unable to find a CRM contact with phone number {}", phone);
    }
  }

  public void optOut(CrmContact crmContact) throws Exception {
    log.info("opting {} ({}) out of sms...", crmContact.id, crmContact.mobilePhone);
    crmContact.smsOptIn = false;
    crmContact.smsOptOut = true;
    crmService.updateContact(crmContact);
  }
}
