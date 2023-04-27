/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.service.segment.PaymentGatewayService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
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
    if (Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().id)
        && Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().id)) {
      log.warn("payment gateway event {} failed to process the donor; skipping donation processing", paymentGatewayEvent.getCrmDonation().transactionId);
      return;
    }

    Optional<CrmDonation> existingDonation = crmService.getDonationByTransactionIds(
        paymentGatewayEvent.getCrmDonation().getTransactionIds(),
        paymentGatewayEvent.getCrmDonation().account.id,
        paymentGatewayEvent.getCrmDonation().contact.id
    );

    if (existingDonation.isPresent()) {
      paymentGatewayEvent.getCrmDonation().id = existingDonation.get().id;

      if (existingDonation.get().status != CrmDonation.Status.SUCCESSFUL && existingDonation.get().status != CrmDonation.Status.REFUNDED) {
        // allow updates to non-posted transactions occur, especially to catch cases where it initially failed is reattempted and succeeds
        log.info("found existing CRM donation {} using transaction {}, but in a non-final state; updating it with the reattempt...",
            existingDonation.get().id, paymentGatewayEvent.getCrmDonation().transactionId);
        crmService.updateDonation(paymentGatewayEvent.getCrmDonation());
        return;
      }

      // posted donation already exists in the CRM with the transactionId - do not process the donation
      log.info("found existing, posted CRM donation {} using transaction {}; skipping creation...",
          existingDonation.get().id, paymentGatewayEvent.getCrmDonation().transactionId);
      return;
    }

    paymentGatewayEvent.getCrmDonation().id = createDonation(paymentGatewayEvent.getCrmDonation());

    for (CrmDonation child : paymentGatewayEvent.getCrmDonation().children) {
      child.id = createDonation(child);
      child.parent.id = paymentGatewayEvent.getCrmDonation().id;
    }
  }

  protected String createDonation(CrmDonation crmDonation) throws Exception {
    if (crmDonation.isRecurring()) {
      Optional<CrmRecurringDonation> recurringDonation = crmService.getRecurringDonation(
          crmDonation.recurringDonation.id,
          crmDonation.recurringDonation.subscriptionId,
          crmDonation.account.id,
          crmDonation.contact.id
      );

      if (recurringDonation.isEmpty()) {
        log.info("unable to find CRM recurring donation using subscriptionId {}; creating it...",
            crmDonation.recurringDonation.subscriptionId);
        // NOTE: See the note on the customer.subscription.created event handling. We insert recurring donations
        // from subscription creation ONLY if it's in a trial period and starts in the future. Otherwise, let the
        // first donation do it in order to prevent timing issues.
        crmDonation.recurringDonation.id = crmService.insertRecurringDonation(crmDonation.recurringDonation);
      } else {
        String recurringDonationId = recurringDonation.get().id;
        log.info("found CRM recurring donation {} using subscriptionId {}",
            recurringDonationId, crmDonation.recurringDonation.subscriptionId);
        crmDonation.recurringDonation.id = recurringDonationId;
      }
    }

    return crmService.insertDonation(crmDonation);
  }

  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> donation = crmService.getDonationByTransactionIds(
        paymentGatewayEvent.getCrmDonation().getTransactionIds(),
        paymentGatewayEvent.getCrmAccount().id,
        paymentGatewayEvent.getCrmContact().id
    );

    // make sure that a donation was found and that only 1 donation was found
    if (donation.isPresent()) {
      paymentGatewayEvent.getCrmDonation().id = donation.get().id;

      log.info("refunding CRM donation {} with refunded charge {}", donation.get().id, paymentGatewayEvent.getCrmDonation().transactionId);
      // Refund the transaction in the CRM
      crmService.refundDonation(paymentGatewayEvent.getCrmDonation());
    } else {
      log.warn("unable to find CRM donation using transaction {}", paymentGatewayEvent.getCrmDonation().transactionId);
    }
  }

  public void processSubscription(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    if (Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().id)
        && Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().id)) {
      log.warn("payment gateway event {} failed to process the donor; skipping subscription processing", paymentGatewayEvent.getCrmDonation().transactionId);
      return;
    }

    Optional<CrmRecurringDonation> recurringDonation = crmService.getRecurringDonation(
        paymentGatewayEvent.getCrmRecurringDonation().id,
        paymentGatewayEvent.getCrmRecurringDonation().subscriptionId,
        paymentGatewayEvent.getCrmAccount().id,
        paymentGatewayEvent.getCrmContact().id
    );

    if (recurringDonation.isEmpty()) {
      log.info("unable to find CRM recurring donation using subscription {}; creating it...",
          paymentGatewayEvent.getCrmRecurringDonation().subscriptionId);
      String recurringDonationId = crmService.insertRecurringDonation(paymentGatewayEvent.getCrmRecurringDonation());
      paymentGatewayEvent.setCrmRecurringDonationId(recurringDonationId);
    } else {
      log.info("found an existing CRM recurring donation using subscription {}",
          paymentGatewayEvent.getCrmRecurringDonation().subscriptionId);
    }
  }

  public void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = crmService.getRecurringDonation(
        paymentGatewayEvent.getCrmRecurringDonation().id,
        paymentGatewayEvent.getCrmRecurringDonation().subscriptionId,
        paymentGatewayEvent.getCrmAccount().id,
        paymentGatewayEvent.getCrmContact().id
    );

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find CRM recurring donation using subscriptionId {}",
          paymentGatewayEvent.getCrmRecurringDonation().subscriptionId);
      return;
    }

    crmService.closeRecurringDonation(recurringDonation.get());

    String targetId = null;
    if (!Strings.isNullOrEmpty(recurringDonation.get().account.id)) {
      targetId = recurringDonation.get().account.id;
    } else if (!Strings.isNullOrEmpty(recurringDonation.get().contact.id)) {
      targetId = recurringDonation.get().contact.id;
    }

    if (!"draft-incomplete-cancelled".equalsIgnoreCase(paymentGatewayEvent.getMetadataValue(List.of("status")))) {
      notificationservice.sendNotification(
          "Recurring Donation Closed",
          "Recurring donation " + recurringDonation.get().id + " has been closed.",
          targetId,
          "donations:close-recurring-donation"
      );
    }
  }

  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = crmService.getRecurringDonationById(manageDonationEvent.getCrmRecurringDonation().id);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find CRM recurring donation using recurringDonationId {}", manageDonationEvent.getCrmRecurringDonation().id);
      return;
    }

    PaymentGatewayService paymentGatewayService = env.paymentGatewayService(recurringDonation.get().gatewayName);

    manageDonationEvent.getCrmRecurringDonation().subscriptionId = recurringDonation.get().subscriptionId;
    if (manageDonationEvent.getCancelDonation()) {
      crmService.closeRecurringDonation(manageDonationEvent.getCrmRecurringDonation());
      paymentGatewayService.closeSubscription(recurringDonation.get().subscriptionId);
    } else {
      crmService.updateRecurringDonation(manageDonationEvent);
      paymentGatewayService.updateSubscription(manageDonationEvent);
    }
  }

  public void processDeposit(List<PaymentGatewayEvent> paymentGatewayEvents) throws Exception {
    List<CrmDonation> crmDonations = new ArrayList<>();
    for (PaymentGatewayEvent e : paymentGatewayEvents) {
      Optional<CrmDonation> donation = crmService.getDonationByTransactionIds(
          e.getCrmDonation().getTransactionIds(),
          e.getCrmAccount().id,
          e.getCrmContact().id
      );
      if (donation.isPresent()) {
        e.getCrmDonation().id = donation.get().id;
        e.getCrmDonation().crmRawObject = donation.get().crmRawObject;
        crmDonations.add(e.getCrmDonation());
      } else {
        log.warn("unable to find SFDC opportunity using transaction {}", e.getCrmDonation().transactionId);
      }
    }
    crmService.insertDonationDeposit(crmDonations);
  }
}
