package com.impactupgrade.common.paymentgateway;

import com.google.common.base.Strings;
import com.impactupgrade.common.crm.AggregateCrmDestinationService;
import com.impactupgrade.common.crm.CrmSourceService;
import com.impactupgrade.common.crm.model.CrmContact;
import com.impactupgrade.common.environment.Environment;
import com.impactupgrade.common.paymentgateway.model.PaymentGatewayEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class DonorService {

  private static final Logger log = LogManager.getLogger(DonorService.class);

  private final Environment env;
  private final CrmSourceService crmSource;
  private final AggregateCrmDestinationService crmDestinations;

  public DonorService(Environment env) {
    this.env = env;
    crmSource = env.crmSourceService();
    crmDestinations = env.crmDonationDestinationServices();
  }

  public void processAccount(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // attempt to find a Contact using the email

    if (Strings.isNullOrEmpty(paymentGatewayEvent.getEmail())) {
      log.warn("payment gateway event {} had no email address; skipping processing", paymentGatewayEvent.getTransactionId());
      // TODO: email support@?
      return;
    }

    // attempt to find a Contact using the email
    Optional<CrmContact> existingContact = crmSource.getContactByEmail(paymentGatewayEvent.getEmail());
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
    String accountId = crmDestinations.insertAccount(paymentGatewayEvent);
    paymentGatewayEvent.setPrimaryCrmAccountId(accountId);
    // create new Contact
    String contactId = crmDestinations.insertContact(paymentGatewayEvent);
    paymentGatewayEvent.setPrimaryCrmContactId(contactId);
  }
}
