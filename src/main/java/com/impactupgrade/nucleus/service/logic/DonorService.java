package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;
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
    crmService = env.crmService();
  }

  public void processAccount(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    // attempt to find a Contact using the email

    if (Strings.isNullOrEmpty(paymentGatewayEvent.getEmail())
        && Strings.isNullOrEmpty(paymentGatewayEvent.getPrimaryCrmAccountId())) {
      log.warn("payment gateway event {} had no email address or CRM ID; skipping processing", paymentGatewayEvent.getTransactionId());
      // TODO: email support@?
      return;
    }

    if (!Strings.isNullOrEmpty(paymentGatewayEvent.getPrimaryCrmAccountId())) {
      log.info("found CRM account {} and contact {}",
          paymentGatewayEvent.getPrimaryCrmAccountId(), paymentGatewayEvent.getPrimaryCrmContactId());
      paymentGatewayEvent.setPrimaryCrmAccountId(paymentGatewayEvent.getPrimaryCrmAccountId());
      paymentGatewayEvent.setPrimaryCrmContactId(paymentGatewayEvent.getPrimaryCrmContactId());
      return;
    }

    // attempt to find a Contact using the email
    Optional<CrmContact> existingContact = crmService.getContactByEmail(paymentGatewayEvent.getEmail());
    if (existingContact.isPresent()) {
      log.info("found CRM contact {} and account {} using email {}",
          existingContact.get().id(), existingContact.get().accountId(), paymentGatewayEvent.getEmail());
      paymentGatewayEvent.setPrimaryCrmAccountId(existingContact.get().accountId());
      paymentGatewayEvent.setPrimaryCrmContactId(existingContact.get().id());
      return;
    }

    log.info("unable to find CRM contact using email {}; creating a new account and contact",
        paymentGatewayEvent.getEmail());

    // create new Household Account
    String accountId = crmService.insertAccount(paymentGatewayEvent);
    paymentGatewayEvent.setPrimaryCrmAccountId(accountId);
    // create new Contact
    String contactId = crmService.insertContact(paymentGatewayEvent);
    paymentGatewayEvent.setPrimaryCrmContactId(contactId);
  }
}
