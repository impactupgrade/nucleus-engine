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
        && Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().id)
        && Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().id)) {
      log.warn("payment gateway event {} had no email address or CRM IDs; skipping processing", paymentGatewayEvent.getCrmDonation().transactionId);
      return;
    }

    Optional<CrmAccount> existingAccount = Optional.empty();
    Optional<CrmContact> existingContact = Optional.empty();

    if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().id)) {
      existingAccount = crmService.getAccountById(paymentGatewayEvent.getCrmAccount().id);
      if (existingAccount.isPresent()) {
        log.info("found CRM account {}", existingAccount.get().id);
      } else {
        log.info("event included CRM account {}, but the account didn't exist; trying through the contact...",
            paymentGatewayEvent.getCrmAccount().id);
      }
    }

    if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().id)) {
      existingContact = crmService.getContactById(paymentGatewayEvent.getCrmContact().id);
      if (existingContact.isPresent()) {
        log.info("found CRM contact {}", existingContact.get().id);

        if (existingAccount.isEmpty() && !Strings.isNullOrEmpty(existingContact.get().account.id)) {
          existingAccount = crmService.getAccountById(existingContact.get().account.id);
          if (existingAccount.isPresent()) {
            log.info("found CRM account {}", existingContact.get().account.id);
          }
        }
      } else {
        log.info("event included CRM contact {}, but the contact didn't exist; trying through the contact email...",
            paymentGatewayEvent.getCrmContact().id);
      }
    }

    // IMPORTANT: Skip this step if an existingAccount was found! A Stripe customer has sf_account defined but not
    // sf_contact, the email address might still be a match here. We assume that sf_account without the presence of
    // sf_contact is a business gift!
    if (existingAccount.isEmpty() && existingContact.isEmpty() && !Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().email)) {
      existingContact = crmService.searchContacts(ContactSearch.byEmail(paymentGatewayEvent.getCrmContact().email)).getSingleResult();
      if (existingContact.isPresent()) {
        log.info("found CRM contact {}", existingContact.get().id);

        if (existingAccount.isEmpty() && !Strings.isNullOrEmpty(existingContact.get().account.id)) {
          existingAccount = crmService.getAccountById(existingContact.get().account.id);
          if (existingAccount.isPresent()) {
            log.info("found CRM account {}", existingContact.get().account.id);
          }
        }
      }
    }

    if (existingAccount.isPresent() || existingContact.isPresent()){
      backfillMissingData(paymentGatewayEvent, existingAccount, existingContact);

      existingAccount.ifPresent(a -> paymentGatewayEvent.setCrmAccountId(a.id));
      existingContact.ifPresent(c -> paymentGatewayEvent.setCrmContactId(c.id));

      return;
    }

    log.info("unable to find CRM records; creating a new account and contact");

    // create new Household Account
    String accountId = crmService.insertAccount(paymentGatewayEvent.getCrmAccount());
    paymentGatewayEvent.setCrmAccountId(accountId);

    try {
      // create new Contact
      String contactId = crmService.insertContact(paymentGatewayEvent.getCrmContact());
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

    backfillMissingData(paymentGatewayEvent, existingAccount, existingContact);
  }

  public void backfillMissingData(PaymentGatewayEvent paymentGatewayEvent,
      Optional<CrmAccount> existingAccount, Optional<CrmContact> existingContact) throws Exception {
    if (existingAccount.isPresent()) {
      if (Strings.isNullOrEmpty(existingAccount.get().billingAddress.street) && !Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().billingAddress.street)) {
        log.info("existing CRM account does not have a street, but the new payment did -- overwrite the whole address");
        existingAccount.get().billingAddress = paymentGatewayEvent.getCrmAccount().billingAddress;
        crmService.updateAccount(existingAccount.get());
      }
    }

    if (existingContact.isPresent()) {
      if (Strings.isNullOrEmpty(existingContact.get().mailingAddress.street) && !Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().mailingAddress.street)) {
        log.info("existing CRM contact does not have a street, but the new payment did -- overwriting the whole address");
        existingContact.get().mailingAddress = paymentGatewayEvent.getCrmContact().mailingAddress;
        crmService.updateContact(existingContact.get());
      }

      if ((Strings.isNullOrEmpty(existingContact.get().firstName) || "Anonymous".equalsIgnoreCase(existingContact.get().firstName)) && !Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().firstName)) {
        log.info("existing CRM contact does not have a firstName, but the new payment did -- overwriting it");
        existingContact.get().firstName = paymentGatewayEvent.getCrmContact().firstName;
        crmService.updateContact(existingContact.get());
      }
      if ((Strings.isNullOrEmpty(existingContact.get().lastName) || "Anonymous".equalsIgnoreCase(existingContact.get().lastName)) && !Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().lastName)) {
        log.info("existing CRM contact does not have a lastName, but the new payment did -- overwriting it");
        existingContact.get().lastName = paymentGatewayEvent.getCrmContact().lastName;
        crmService.updateContact(existingContact.get());
      }

      if (Strings.isNullOrEmpty(existingContact.get().mobilePhone) && !Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().mobilePhone)) {
        log.info("existing CRM contact does not have a mobilePhone, but the new payment did -- overwriting it");
        existingContact.get().mobilePhone = paymentGatewayEvent.getCrmContact().mobilePhone;
        crmService.updateContact(existingContact.get());
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
      log.info("found existing CRM account {} and contact {} using email {}", crmContact.get().account.id, crmContact.get().id, formCrmContact.email);
    }
  }
}
