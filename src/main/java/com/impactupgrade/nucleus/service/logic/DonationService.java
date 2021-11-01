/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.service.segment.PaymentGatewayService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class DonationService {

  private static final Logger log = LogManager.getLogger(DonationService.class.getName());

  private final Environment env;
  private final CrmService crmService;
  private final NotificationService notificationservice;

  public DonationService(Environment env) {
    this.env = env;
    crmService = env.donationsCrmService();
    notificationservice = env.notificationService();
  }

  public void createDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: Happens both here and DonationService, since we need both processes to halt. Refactor?
    if (Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().email)
        && Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().id)) {
      log.warn("payment gateway event {} had no email address or CRM ID; skipping processing", paymentGatewayEvent.getTransactionId());
      return;
    }

    Optional<CrmDonation> existingDonation = crmService.getDonation(paymentGatewayEvent);

    if (existingDonation.isPresent()) {
      if (existingDonation.get().status != CrmDonation.Status.SUCCESSFUL) {
        // allow updates to non-posted transactions occur, especially to catch cases where it initially failed is reattempted and succeeds
        log.info("found existing CRM donation {} using transaction {}, but in a non-posted state; updating it with the reattempt...",
            existingDonation.get().id, paymentGatewayEvent.getTransactionId());
        crmService.insertDonationReattempt(paymentGatewayEvent);
        return;
      }
      // posted donation already exists in the CRM with the transactionId - do not process the donation
      log.info("found existing, posted CRM donation {} using transaction {}; skipping creation...",
          existingDonation.get().id, paymentGatewayEvent.getTransactionId());
      return;
    }

    if (paymentGatewayEvent.isTransactionRecurring()) {
      Optional<CrmRecurringDonation> recurringDonation = crmService.getRecurringDonation(paymentGatewayEvent);

      if (recurringDonation.isEmpty()) {
        log.info("unable to find CRM recurring donation using subscriptionId {}; creating it...",
            paymentGatewayEvent.getSubscriptionId());
        // NOTE: See the note on the customer.subscription.created event handling. We insert recurring donations
        // from subscription creation ONLY if it's in a trial period and starts in the future. Otherwise, let the
        // first donation do it in order to prevent timing issues.
        String recurringDonationId = crmService.insertRecurringDonation(paymentGatewayEvent);
        paymentGatewayEvent.setCrmRecurringDonationId(recurringDonationId);
      } else {
        String recurringDonationId = recurringDonation.get().id;
        log.info("found CRM recurring donation {} using subscriptionId {}",
            recurringDonationId, paymentGatewayEvent.getSubscriptionId());
        paymentGatewayEvent.setCrmRecurringDonationId(recurringDonationId);
      }
    }

    crmService.insertDonation(paymentGatewayEvent);
  }

  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> donation = crmService.getDonation(paymentGatewayEvent);

    // make sure that a donation was found and that only 1 donation was found
    if (donation.isPresent()) {
      log.info("refunding CRM donation {} with refunded charge {}", donation.get().id, paymentGatewayEvent.getTransactionId());
      // Refund the transaction in the CRM
      crmService.refundDonation(paymentGatewayEvent);
    } else {
      log.warn("unable to find CRM donation using transaction {}", paymentGatewayEvent.getTransactionId());
    }
  }

  public void processSubscription(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = crmService.getRecurringDonation(paymentGatewayEvent);

    if (recurringDonation.isEmpty()) {
      log.info("unable to find CRM recurring donation using subscription {}; creating it...",
          paymentGatewayEvent.getSubscriptionId());
      crmService.insertRecurringDonation(paymentGatewayEvent);
    } else {
      log.info("found an existing CRM recurring donation using subscription {}",
          paymentGatewayEvent.getSubscriptionId());
    }
  }

  public void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = crmService.getRecurringDonation(paymentGatewayEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find CRM recurring donation using subscriptionId {}",
          paymentGatewayEvent.getSubscriptionId());
      return;
    }

    crmService.closeRecurringDonation(paymentGatewayEvent);
    // Notifications
    EnvironmentConfig.Notifications notifications = env.getConfig().notifications.get("donation-service:close-recurring-donation");
    notificationservice.sendEmailNotification(notifications.email,
            "CRM recurring donation " + recurringDonation.get().id + " has beed closed.",
            "<html></html>");
  }

  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = crmService.getRecurringDonation(manageDonationEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find CRM recurring donation using recurringDonationId {}", manageDonationEvent.getDonationId());
      return;
    }

    PaymentGatewayService paymentGatewayService = env.paymentGatewayService(recurringDonation.get().paymentGatewayName);

    if (manageDonationEvent.getDonationId() == null) {
      manageDonationEvent.setDonationId(recurringDonation.get().id);
    }

    manageDonationEvent.setSubscriptionId(recurringDonation.get().subscriptionId);
    if (manageDonationEvent.getCancelDonation()) {
      crmService.closeRecurringDonation(manageDonationEvent);
      paymentGatewayService.closeSubscription(manageDonationEvent);
    } else {
      crmService.updateRecurringDonation(manageDonationEvent);
      paymentGatewayService.updateSubscription(manageDonationEvent);
    }
  }

  public void chargeDeposited(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> donation = crmService.getDonation(paymentGatewayEvent);

    if (donation.isEmpty()) {
      log.info("missing an CRM donation for transaction {}; notifying staff...", paymentGatewayEvent.getTransactionId());
      // TODO: Send email alert
      // TODO: First retry the original charge/intent succeeded event, just in case the original webhook failed?
      return;
    }

    crmService.insertDonationDeposit(paymentGatewayEvent);
  }
}
