/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DonorService {

  private static final Logger log = LogManager.getLogger(DonorService.class);

  protected final Environment env;
  protected final CrmService crmService;

  public DonorService(Environment env, @Qualifier("donations") CrmService crmService) {
    this.env = env;
    this.crmService = crmService;
  }

  public void processAccount(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
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
}
