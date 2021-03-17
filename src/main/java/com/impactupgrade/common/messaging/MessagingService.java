package com.impactupgrade.common.messaging;

import com.google.common.base.Strings;
import com.impactupgrade.common.crm.AggregateCrmDestinationService;
import com.impactupgrade.common.crm.CrmSourceService;
import com.impactupgrade.common.crm.model.CrmContact;
import com.impactupgrade.common.environment.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class MessagingService {

  private static final Logger log = LogManager.getLogger(MessagingService.class);

  private final CrmSourceService crmSMSSourceService;
  private final AggregateCrmDestinationService crmSMSDestinationServices;

  public MessagingService(Environment env) {
    crmSMSSourceService = env.crmSMSSourceService();
    crmSMSDestinationServices = env.crmSMSDestinationServices();
  }

  public void signup(MessagingWebhookEvent event) throws Exception {
    // First, look for an existing contact based off the phone number. Important to use the PN since the
    // Twilio Studio flow has some flavors that assume the contact is already in the CRM, so only the PN (From) will be
    // provided. Other flows allow email to be optional.
    Optional<CrmContact> crmContact = crmSMSSourceService.getContactByPhone(event.getPhone());
    String contactId;
    if (crmContact.isEmpty()) {
      // Didn't exist, so attempt to create it.
      contactId = crmSMSDestinationServices.insertContact(event);
    } else {
      // Existed, so use it
      contactId = crmContact.get().id();
      log.info("contact already existed in HubSpot: {}", contactId);
    }

    if (!Strings.isNullOrEmpty(contactId)) {
      event.setCrmContactId(contactId);
      crmSMSDestinationServices.smsSignup(event);
    }
  }
}
