/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.ContactFormData;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class ContactService {

  private static final Logger log = LogManager.getLogger(ContactService.class);

  private final Environment env;
  private final CrmService crmService;

  public ContactService(Environment env) {
    this.env = env;
    crmService = env.donationsCrmService();
  }

  public void processDonor(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: Happens both here and DonationService, since we need both processes to halt. Refactor?
    if (Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().email)
        && Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().id)) {
      log.warn("payment gateway event {} had no email address or CRM ID; skipping processing", paymentGatewayEvent.getTransactionId());
      return;
    }

    if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().id)) {
      log.info("found CRM account {} and contact {}",
          paymentGatewayEvent.getCrmAccount().id, paymentGatewayEvent.getCrmContact().id);
      return;
    }

    // attempt to find a Contact using the email
    Optional<CrmContact> existingContact = crmService.getContactByEmail(paymentGatewayEvent.getCrmContact().email);
    if (existingContact.isPresent()) {
      log.info("found CRM contact {} and account {} using email {}",
          existingContact.get().id, existingContact.get().accountId, paymentGatewayEvent.getCrmContact().email);
      paymentGatewayEvent.setCrmAccountId(existingContact.get().accountId);
      paymentGatewayEvent.setCrmContactId(existingContact.get().id);
      return;
    }

    log.info("unable to find CRM contact using email {}; creating a new account and contact",
        paymentGatewayEvent.getCrmContact().email);

    // create new Household Account
    String accountId = crmService.insertAccount(paymentGatewayEvent);
    paymentGatewayEvent.setCrmAccountId(accountId);
    // create new Contact
    String contactId = crmService.insertContact(paymentGatewayEvent);
    paymentGatewayEvent.setCrmContactId(contactId);
  }

  public void processContactForm(ContactFormData formData) throws Exception {
    CrmContact formCrmContact = formData.toCrmContact();

    Optional<CrmContact> crmContact = crmService.getContactByEmail(formCrmContact.email);
    if (crmContact.isEmpty()) {
      log.info("unable to find CRM contact using email {}; creating new account and contact", formCrmContact.email);
      // create new contact
      log.info("inserting contact {}", formCrmContact.toString());
      crmService.insertContact(formCrmContact);
    } else {
      log.info("found existing CRM account {} and contact {} using email {}", crmContact.get().accountId, crmContact.get().id, formCrmContact.email);
    }
  }
}
