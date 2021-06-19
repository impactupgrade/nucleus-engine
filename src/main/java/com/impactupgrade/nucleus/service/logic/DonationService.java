/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.service.segment.PaymentGatewayService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class DonationService {

  private static final Logger log = LogManager.getLogger(DonationService.class.getName());

  private final Environment env;
  private final CrmService crmService;
  private final PaymentGatewayService paymentGatewayService;

  public DonationService(Environment env) {
    this.env = env;
    crmService = env.crmService();
    paymentGatewayService = env.paymentGatewayService();
  }

  public void createDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> existingDonation = crmService.getDonation(paymentGatewayEvent);

    if (existingDonation.isPresent()) {
      if (!existingDonation.get().isPosted()) {
        // allow updates to non-posted transactions occur, especially to catch cases where it initially failed is reattempted and succeeds
        log.info("found existing CRM donation {} using transaction {}, but in a non-posted state; updating it with the reattempt...",
            existingDonation.get().getId(), paymentGatewayEvent.getTransactionId());
        crmService.insertDonationReattempt(paymentGatewayEvent);
        return;
      }
      // posted donation already exists in the CRM with the transactionId - do not process the donation
      log.info("found existing, posted CRM donation {} using transaction {}; skipping creation...",
          existingDonation.get().getId(), paymentGatewayEvent.getTransactionId());
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
        String recurringDonationId = recurringDonation.get().id();
        log.info("found CRM recurring donation {} using subscriptionId {}",
            recurringDonationId, paymentGatewayEvent.getSubscriptionId());
        paymentGatewayEvent.setCrmRecurringDonationId(recurringDonationId);
      }
    }

    crmService.insertDonation(paymentGatewayEvent);
  }

  public void refundDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> donation = crmService.getDonation(paymentGatewayEvent);

    // make sure that a donation was found and that only 1 donation was found
    if (donation.isPresent()) {
      log.info("refunding CRM donation {} with refunded charge {}", donation.get().getId(), paymentGatewayEvent.getTransactionId());
      // Refund the transaction in the CRM
      crmService.refundDonation(paymentGatewayEvent);
    } else {
      log.warn("unable to find CRM donation using transaction {}", paymentGatewayEvent.getTransactionId());
    }
  }

  public void processSubscription(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
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

  public void closeRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = crmService.getRecurringDonation(paymentGatewayEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find CRM recurring donation using subscriptionId {}",
          paymentGatewayEvent.getSubscriptionId());
      return;
    }

    crmService.closeRecurringDonation(paymentGatewayEvent);
  }

  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = crmService.getRecurringDonation(manageDonationEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find CRM recurring donation using recurringDonationId {}", manageDonationEvent.getDonationId());
      return;
    }

    if (manageDonationEvent.getDonationId() == null) {
      manageDonationEvent.setDonationId(recurringDonation.get().id());
    }

    manageDonationEvent.setSubscriptionId(crmService.getSubscriptionId(manageDonationEvent));
    if (manageDonationEvent.getCancelDonation()) {
      crmService.closeRecurringDonation(manageDonationEvent);
      paymentGatewayService.cancelSubscription(manageDonationEvent);
    } else {
      crmService.updateRecurringDonation(manageDonationEvent);
      paymentGatewayService.updateSubscription(manageDonationEvent);
    }
  }

  public void chargeDeposited(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
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
