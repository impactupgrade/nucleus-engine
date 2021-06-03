/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class DonorService {

  private static final Logger log = LogManager.getLogger(DonorService.class);

  private final Environment env;
  private final CrmService crmService;

  public DonorService(Environment env) {
    this.env = env;
    crmService = env.donationsCrmService();
  }

  public void processAccount(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // attempt to find a Contact using the email

    if (Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().email)
        && Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().id)) {
      log.warn("payment gateway event {} had no email address or CRM ID; skipping processing", paymentGatewayEvent.getTransactionId());
      // TODO: email support@?
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

  public void updateAccountAddress(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    CrmContact crmContact = paymentGatewayEvent.getCrmContact();
    CrmAccount crmAccount = paymentGatewayEvent.getCrmAccount();

    // for contact and then account:
    // check if the contact exists AND if the new crmContact actually contains changes to the existing address
    // then, check if each individual field is provided by the new crmContact AND it is different than the existing field value

    // attempt to update existing CRM contact
    Optional<CrmContact> existingContact = crmService.getContactById(crmContact.id);
    if (existingContact.isPresent()) {
      if (!existingContact.get().address.equals(crmContact.address)) {
        processCrmAddress(existingContact.get().address, crmContact.address);
        crmService.updateContact(existingContact.get());
      } else {
        log.info("CRM contact {} found, but no changes detected", crmContact.id);
      }
    } else {
      log.info("unable to find CRM contact {}", crmContact.id);
    }

    // attempt to update existing CRM account
    Optional<CrmAccount> existingAccount = crmService.getAccountById(crmContact.accountId);
    if (existingAccount.isPresent()) {
      if (!existingAccount.get().address.equals(crmAccount.address)) {
        processCrmAddress(existingAccount.get().address, crmAccount.address);
        crmService.updateAccount(existingAccount.get());
      } else {
        log.info("CRM account {} found, but no changes detected", crmContact.accountId);
      }
    } else {
      log.info("unable to find CRM account {}", crmContact.accountId);
    }
  }

  public void processCrmAddress(CrmAddress currAdd, CrmAddress newAdd) {
    if (Strings.isNullOrEmpty(newAdd.street) && !newAdd.street.equalsIgnoreCase(currAdd.street)) {
      currAdd.street = newAdd.street;
    }
    if (Strings.isNullOrEmpty(newAdd.city) && !newAdd.city.equalsIgnoreCase(currAdd.city)) {
      currAdd.city = newAdd.city;
    }
    if (Strings.isNullOrEmpty(newAdd.state) && !newAdd.state.equalsIgnoreCase(currAdd.state)) {
      currAdd.state = newAdd.state;
    }
    if (Strings.isNullOrEmpty(newAdd.postalCode) && !newAdd.postalCode.equalsIgnoreCase(currAdd.postalCode)) {
      currAdd.postalCode = newAdd.postalCode;
    }
    if (Strings.isNullOrEmpty(newAdd.country) && !newAdd.country.equalsIgnoreCase(currAdd.country)) {
      currAdd.country = newAdd.country;
    }
  }

}
