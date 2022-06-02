/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.ContactFormData;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
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
      Optional<CrmAccount> existingAccount = crmService.getAccountById(paymentGatewayEvent.getCrmAccount().id);
      if (existingAccount.isPresent()) {
        log.info("found CRM account {} and contact {}",
            paymentGatewayEvent.getCrmAccount().id, paymentGatewayEvent.getCrmContact().id);
        return;
      } else {
        log.info("event included CRM account {} and contact {}, but the account didn't exist; trying by-email...",
            paymentGatewayEvent.getCrmAccount().id, paymentGatewayEvent.getCrmContact().id);
      }
    }

    // attempt to find a Contact using the email
    Optional<CrmContact> existingContact = crmService.searchContacts(ContactSearch.byEmail(paymentGatewayEvent.getCrmContact().email)).getSingleResult();
    if (existingContact.isPresent()) {
      log.info("found CRM account {} and contact {} using email {}",
          existingContact.get().accountId, existingContact.get().id, paymentGatewayEvent.getCrmContact().email);
      paymentGatewayEvent.setCrmAccountId(existingContact.get().accountId);
      // Set the full Contact, which we need to populate donation names, etc.
      paymentGatewayEvent.setCrmContact(existingContact.get());
      return;
    }

    log.info("unable to find CRM contact using email {}; creating a new account and contact",
        paymentGatewayEvent.getCrmContact().email);

    // create new Household Account
    String accountId = crmService.insertAccount(paymentGatewayEvent);
    paymentGatewayEvent.setCrmAccountId(accountId);

    try {
      // create new Contact
      String contactId = crmService.insertContact(paymentGatewayEvent);
      // Don't need to set the full Contact here, since the event already has all the details.
      paymentGatewayEvent.setCrmContactId(contactId);
    } catch (Exception e) {
      // Nearly always, this happens due to an issue that will never self-resolve, like an invalid email address
      // with HubSpot's validation rules. Prevent duplicate, orphaned accounts.
      log.warn("CRM failed to create the contact, so halting the process and cleaning up the account we just created. Error: {}", e.getMessage());
      if (!Strings.isNullOrEmpty(accountId)) {
        crmService.deleteAccount(accountId);
        // also unset the ID, letting downstream know that it should also halt
        paymentGatewayEvent.setCrmAccountId(null);
      }
    }
  }

  public void processContactForm(ContactFormData formData) throws Exception {
    CrmContact formCrmContact = formData.toCrmContact();

    Optional<CrmContact> crmContact = crmService.searchContacts(ContactSearch.byEmail(formCrmContact.email)).getSingleResult();
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
