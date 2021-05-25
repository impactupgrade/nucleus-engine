package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.util.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MessagingService {

  private static final Logger log = LogManager.getLogger(MessagingService.class);

  private final CrmService crmService;

  public MessagingService(Environment env) {
    crmService = env.crmService();
  }

  public CrmContact processContact(
      String phone,
      String firstName,
      String lastName,
      String email,
      String __emailOptIn,
      String __smsOptIn
  ) throws Exception {
    // Hubspot doesn't seem to support country codes when phone numbers are used to search. Strip it off.
    phone = phone.replace("+1", "");

    // They'll send "no", etc. for email if they don't want to opt-in. Simply look for @, to be flexible.
    if (email != null && !email.contains("@")) {
      email = null;
      __emailOptIn = null;
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

    // if the flow didn't include an explicit email opt-in process, safe to assume it's fine
    boolean emailOptIn;
    if (Strings.isNullOrEmpty(__emailOptIn)) {
      emailOptIn = true;
    } else {
      emailOptIn = Utils.checkboxToBool(__emailOptIn);
    }
    // if the flow didn't include an explicit sms opt-in process, NOT safe to assume
    boolean smsOptIn;
    if (Strings.isNullOrEmpty(__smsOptIn)) {
      smsOptIn = false;
    } else {
      smsOptIn = Utils.checkboxToBool(__smsOptIn);
    }

    String contactId;
    if (crmContact == null) {
      // Didn't exist, so attempt to create it.
      CrmContact newCrmContact = new CrmContact();
      newCrmContact.phone = phone;
      newCrmContact.firstName = firstName;
      newCrmContact.lastName = lastName;
      newCrmContact.email = email;

      newCrmContact.emailOptIn = emailOptIn;
      newCrmContact.smsOptIn = smsOptIn;

      contactId = crmService.insertContact(newCrmContact);
      newCrmContact.id = contactId;
    } else {
      // Existed, so use it
      contactId = crmContact.id;
      log.info("contact already existed in CRM: {}", contactId);

      CrmContact updateCrmContact = new CrmContact(crmContact.id);

      if (Strings.isNullOrEmpty(crmContact.firstName) && !Strings.isNullOrEmpty(firstName)) {
        log.info("contact {} missing firstName; updating it...", crmContact.id);
        updateCrmContact.firstName = firstName;
      }
      if (Strings.isNullOrEmpty(crmContact.lastName) && !Strings.isNullOrEmpty(lastName)) {
        log.info("contact {} missing lastName; updating it...", crmContact.id);
        updateCrmContact.lastName = lastName;
      }
      if (Strings.isNullOrEmpty(crmContact.email) && !Strings.isNullOrEmpty(email)) {
        log.info("contact {} missing email; updating it...", crmContact.id);
        updateCrmContact.email = email;
      }

      updateCrmContact.emailOptIn = emailOptIn;
      updateCrmContact.smsOptIn = smsOptIn;

      crmService.updateContact(updateCrmContact);
    }

    return crmContact;
  }
}
