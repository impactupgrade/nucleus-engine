/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DonationService {

  protected final Environment env;
  protected final CrmService crmService;
  protected final NotificationService notificationservice;

  public DonationService(Environment env) {
    this.env = env;
    crmService = env.donationsCrmService();
    notificationservice = env.notificationService();
  }

  public void processDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    if (Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().id)
        && Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().id)) {
      env.logJobWarn("payment gateway event {} failed to process the donor; skipping donation processing", paymentGatewayEvent.getCrmDonation().transactionId);
      return;
    }

    fetchAndSetDonation(paymentGatewayEvent);

    if (Strings.isNullOrEmpty(paymentGatewayEvent.getCrmDonation().id)) {
      if (paymentGatewayEvent.getCrmDonation().isRecurring()) {
        fetchAndSetRecurringDonation(paymentGatewayEvent);

        if (Strings.isNullOrEmpty(paymentGatewayEvent.getCrmDonation().recurringDonation.id)) {
          createRecurringDonation(paymentGatewayEvent);
        }
      }

      createDonation(paymentGatewayEvent);
    }
  }

  protected void fetchAndSetRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    CrmDonation crmDonation = paymentGatewayEvent.getCrmDonation();
    Optional<CrmRecurringDonation> recurringDonation = crmService.getRecurringDonation(
        crmDonation.recurringDonation.id,
        crmDonation.recurringDonation.subscriptionId,
        crmDonation.account.id,
        crmDonation.contact.id
    );
    if (recurringDonation.isPresent()) {
      String recurringDonationId = recurringDonation.get().id;
      env.logJobInfo("found CRM recurring donation {} using subscriptionId {}",
          recurringDonationId, crmDonation.recurringDonation.subscriptionId);
      crmDonation.recurringDonation.id = recurringDonationId;
    }
  }

  protected void createRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    CrmDonation crmDonation = paymentGatewayEvent.getCrmDonation();
    env.logJobInfo("unable to find CRM recurring donation using subscriptionId {}; creating it...",
        crmDonation.recurringDonation.subscriptionId);
    // NOTE: See the note on the customer.subscription.created event handling. We insert recurring donations
    // from subscription creation ONLY if it's in a trial period and starts in the future. Otherwise, let the
    // first donation do it in order to prevent timing issues.
    crmDonation.recurringDonation.id = crmService.insertRecurringDonation(crmDonation.recurringDonation);
  }

  protected void fetchAndSetDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> existingDonation = crmService.getDonationByTransactionIds(
        paymentGatewayEvent.getCrmDonation().getTransactionIds(),
        paymentGatewayEvent.getCrmDonation().account.id,
        paymentGatewayEvent.getCrmDonation().contact.id
    );
    if (existingDonation.isPresent()) {
      env.logJobInfo("found existing, posted CRM donation {} using transaction {}",
          existingDonation.get().id, paymentGatewayEvent.getCrmDonation().transactionId);
      paymentGatewayEvent.getCrmDonation().id = existingDonation.get().id;
      // TODO: Ugh, I don't like this precedent. We need a better way to merge together the 1) CRM data we already have
      //  with 2) data that only Stripe has. As an example, if this donation already exists in the CRM but not in
      //  Accounting, the AccountingService needs to know what type of transaction this was. But since that's CRM
      //  vendor specific, it needs to come from the CrmService.
      //  We can't simply swap the paymentGatewayEvent's CrmDonation for the existingDonation, since PaymentGatewayEvent
      //  has other Stripe data that we do not currently store in the CRM.
      //  Maybe the answer is we first need Contact/Donation Service to look for existing records and set them on
      //  the event, and AFTER that happens, then call paymentGatewayEvent.initStripe to fill in the rest.
      paymentGatewayEvent.getCrmDonation().transactionType = existingDonation.get().transactionType;
      if (existingDonation.get().recurringDonation != null) {
        paymentGatewayEvent.getCrmRecurringDonation().id = existingDonation.get().recurringDonation.id;
      }

      if (paymentGatewayEvent.getCrmDonation().status != CrmDonation.Status.FAILED
          && existingDonation.get().status != CrmDonation.Status.SUCCESSFUL && existingDonation.get().status != CrmDonation.Status.REFUNDED) {
        updateFailedDonationReattempt(paymentGatewayEvent, existingDonation.get());
      }
    }
  }

  protected void updateFailedDonationReattempt(PaymentGatewayEvent paymentGatewayEvent, CrmDonation existingDonation) throws Exception {
    // allow updates to non-posted transactions occur, especially to catch cases where it initially failed is reattempted and succeeds
    env.logJobInfo("found existing CRM donation {} using transaction {}, but in a non-final state; updating it with the reattempt...",
        existingDonation.id, paymentGatewayEvent.getCrmDonation().transactionId);
    crmService.updateDonation(paymentGatewayEvent.getCrmDonation());
  }

  protected void createDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    env.logJobInfo("unable to find CRM donation using transaction {}; creating it...",
        paymentGatewayEvent.getCrmDonation().transactionId);

    paymentGatewayEvent.getCrmDonation().id = crmService.insertDonation(paymentGatewayEvent.getCrmDonation());

    if (paymentGatewayEvent.getCrmDonation().status == CrmDonation.Status.FAILED) {
      String targetId = null;
      if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmDonation().account.id)) {
        targetId = paymentGatewayEvent.getCrmDonation().account.id;
      } else if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmDonation().contact.id)) {
        targetId = paymentGatewayEvent.getCrmDonation().contact.id;
      }

      NotificationService.Notification notification = new NotificationService.Notification(
          "Donation: Payment Failed",
          "Donation payment attempt " + paymentGatewayEvent.getCrmDonation().id + " failed.<br/>Payment Gateway: <a href=\"" + paymentGatewayEvent.getCrmDonation().url + "\">" + paymentGatewayEvent.getCrmDonation().url + "</a>"
      );
      env.notificationService().sendNotification(notification, targetId, "donations:payment-failed");
    }
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

      env.logJobInfo("refunding CRM donation {} with refunded charge {}", donation.get().id, paymentGatewayEvent.getCrmDonation().transactionId);
      // Refund the transaction in the CRM
      crmService.refundDonation(paymentGatewayEvent.getCrmDonation());
    } else {
      env.logJobWarn("unable to find CRM donation using transaction {}", paymentGatewayEvent.getCrmDonation().transactionId);
    }
  }

  public void processSubscription(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    if (Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().id)
        && Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().id)) {
      env.logJobWarn("payment gateway event {} failed to process the donor; skipping subscription processing", paymentGatewayEvent.getCrmDonation().transactionId);
      return;
    }

    Optional<CrmRecurringDonation> recurringDonation = crmService.getRecurringDonation(
        paymentGatewayEvent.getCrmRecurringDonation().id,
        paymentGatewayEvent.getCrmRecurringDonation().subscriptionId,
        paymentGatewayEvent.getCrmAccount().id,
        paymentGatewayEvent.getCrmContact().id
    );

    if (recurringDonation.isEmpty()) {
      env.logJobInfo("unable to find CRM recurring donation using subscription {}; creating it...",
          paymentGatewayEvent.getCrmRecurringDonation().subscriptionId);
      String recurringDonationId = crmService.insertRecurringDonation(paymentGatewayEvent.getCrmRecurringDonation());
      paymentGatewayEvent.setCrmRecurringDonationId(recurringDonationId);
    } else {
      env.logJobInfo("found an existing CRM recurring donation using subscription {}",
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
      env.logJobWarn("unable to find CRM recurring donation using subscriptionId {}",
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
      NotificationService.Notification notification = new NotificationService.Notification(
          "Recurring Donation: Closed",
          "Recurring donation " + recurringDonation.get().id + " has been closed."
      );
      notificationservice.sendNotification(notification, targetId, "donations:close-recurring-donation");
    }
  }

  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = crmService.getRecurringDonationById(manageDonationEvent.getCrmRecurringDonation().id);

    if (recurringDonation.isEmpty()) {
      env.logJobWarn("unable to find CRM recurring donation using recurringDonationId {}", manageDonationEvent.getCrmRecurringDonation().id);
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
        env.logJobWarn("unable to find SFDC opportunity using transaction {}", e.getCrmDonation().transactionId);
      }
    }
    crmService.insertDonationDeposit(crmDonations);
  }
}
