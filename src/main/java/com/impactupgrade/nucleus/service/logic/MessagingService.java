package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.MessagingWebhookEvent;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class MessagingService {

  private static final Logger log = LogManager.getLogger(MessagingService.class);

  private final CrmService crmService;

  public MessagingService(Environment env) {
    crmService = env.crmService();
  }

  public void signup(MessagingWebhookEvent event) throws Exception {
    // First, look for an existing contact based off the phone number. Important to use the PN since the
    // Twilio Studio flow has some flavors that assume the contact is already in the CRM, so only the PN (From) will be
    // provided. Other flows allow email to be optional.
    Optional<CrmContact> crmContact = crmService.getContactByPhone(event.getPhone());
    String contactId;
    if (crmContact.isEmpty()) {
      // Didn't exist, so attempt to create it.
      contactId = crmService.insertContact(event);
    } else {
      // Existed, so use it
      contactId = crmContact.get().id();
      log.info("contact already existed in HubSpot: {}", contactId);
    }

    // TODO: update logic from old TwilioController -- needs broken down into CrmService updates
//    boolean update = false;
//    ContactBuilder contactBuilder = new ContactBuilder();
//    if ((contact.getProperties().getFirstname() == null || Strings.isNullOrEmpty(contact.getProperties().getFirstname().getValue()))
//        && !Strings.isNullOrEmpty(firstName)) {
//      log.info("contact {} missing firstName; updating it...", contact.getVid());
//      update = true;
//      contactBuilder.firstName(firstName);
//    }
//    if ((contact.getProperties().getLastname() == null || Strings.isNullOrEmpty(contact.getProperties().getLastname().getValue()))
//        && !Strings.isNullOrEmpty(lastName)) {
//      log.info("contact {} missing lastName; updating it...", contact.getVid());
//      update = true;
//      contactBuilder.lastName(lastName);
//    }
//    if ((contact.getProperties().getEmail() == null || Strings.isNullOrEmpty(contact.getProperties().getEmail().getValue()))
//        && !Strings.isNullOrEmpty(email)) {
//      log.info("contact {} missing email; updating it...", contact.getVid());
//      update = true;
//      contactBuilder.email(email);
//    }
//    if (update) {
//      HubSpotClientFactory.client().contacts().updateById(contactBuilder, contact.getVid() + "");
//    }

    if (!Strings.isNullOrEmpty(contactId)) {
      event.setCrmContactId(contactId);
      crmService.smsSignup(event);
    }
  }
}
