/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.ProcessContext;
import com.impactupgrade.nucleus.model.crm.CrmContact;
import com.impactupgrade.nucleus.model.event.PaymentGatewayWebhookEvent;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class DonorService {

  private static final Logger log = LogManager.getLogger(DonorService.class);

  protected final ProcessContext processContext;
  protected final CrmService crmService;

  public DonorService(ProcessContext processContext) {
    this.processContext = processContext;
    crmService = processContext.crmService();
  }

  public void processAccount(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    // attempt to find a Contact using the email

    if (Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().email)
        && Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccountId())) {
      log.warn("payment gateway event {} had no email address or CRM ID; skipping processing", paymentGatewayEvent.getTransactionId());
      // TODO: email support@?
      return;
    }

    if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccountId())) {
      log.info("found CRM account {} and contact {}",
          paymentGatewayEvent.getCrmAccountId(), paymentGatewayEvent.getCrmContactId());
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
