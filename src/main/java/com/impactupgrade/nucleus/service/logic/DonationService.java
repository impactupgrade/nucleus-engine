package com.impactupgrade.nucleus.service.logic;

import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;
import com.impactupgrade.nucleus.service.segment.AggregateCrmDestinationService;
import com.impactupgrade.nucleus.service.segment.CrmSourceService;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.service.segment.PaymentGatewayService;
import com.impactupgrade.nucleus.service.segment.StripePaymentGatewayService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class DonationService {

  private static final Logger log = LogManager.getLogger(DonationService.class.getName());

  private final Environment env;
  private final CrmSourceService crmSource;
  private final AggregateCrmDestinationService crmDestinations;
  private final PaymentGatewayService paymentGatewayService;

  public DonationService(Environment env) {
    this.env = env;
    crmSource = env.crmSourceService();
    crmDestinations = env.crmDonationDestinationServices();
    paymentGatewayService = env.paymentGatewayService();
  }

  public void createDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> existingDonation = crmSource.getDonation(paymentGatewayEvent);

    if (existingDonation.isPresent()) {
      if (!existingDonation.get().isSuccessful() && paymentGatewayEvent.isTransactionSuccess()) {
        // The donation originally failed, but then the *same charge* was retried and succeeded. IE, Stripe will
        // sometimes retry a failure within the same Charge, or allow donors to do that if they're in Stripe Checkout.
        // It won't come across as a separate Charge, but keep the original. Update it!
        log.info("found existing CRM donation {} using transaction {}, but in a failed state; marking it as successful...",
            existingDonation.get().getId(), paymentGatewayEvent.getTransactionId());
        existingDonation.get().setSuccessful(true);
        crmDestinations.updateDonation(existingDonation.get());
        return;
      }
      // donation already exists in the CRM with the transactionId - do not process the donation
      log.info("found existing CRM donation {} using transaction {}; skipping creation...",
          existingDonation.get().getId(), paymentGatewayEvent.getTransactionId());
      return;
    }

    if (paymentGatewayEvent.isTransactionRecurring()) {
      Optional<CrmRecurringDonation> recurringDonation = crmSource.getRecurringDonation(paymentGatewayEvent);

      if (recurringDonation.isEmpty()) {
        log.info("unable to find CRM recurring donation using subscriptionId {}; creating it...",
            paymentGatewayEvent.getSubscriptionId());
        // NOTE: See the note on the customer.subscription.created event handling. We insert recurring donations
        // from subscription creation ONLY if it's in a trial period and starts in the future. Otherwise, let the
        // first donation do it in order to prevent timing issues.
        String recurringDonationId = crmDestinations.insertRecurringDonation(paymentGatewayEvent);
        paymentGatewayEvent.setPrimaryCrmRecurringDonationId(recurringDonationId);
      } else {
        String recurringDonationId = recurringDonation.get().id();
        log.info("found CRM recurring donation {} using subscriptionId {}",
            recurringDonationId, paymentGatewayEvent.getSubscriptionId());
        paymentGatewayEvent.setPrimaryCrmRecurringDonationId(recurringDonationId);
      }
    }

    crmDestinations.insertDonation(paymentGatewayEvent);
  }

  public void refundDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> donation = crmSource.getDonation(paymentGatewayEvent);

    // make sure that a donation was found and that only 1 donation was found
    if (donation.isPresent()) {
      log.info("refunding CRM donation {} with refunded charge {}", donation.get().getId(), paymentGatewayEvent.getTransactionId());
      // Refund the transaction in the CRM
      crmDestinations.refundDonation(paymentGatewayEvent);
    } else {
      log.warn("unable to find CRM donation using transaction {}", paymentGatewayEvent.getTransactionId());
    }
  }

  public void processSubscription(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = crmSource.getRecurringDonation(paymentGatewayEvent);

    if (recurringDonation.isEmpty()) {
      log.info("unable to find CRM recurring donation using subscription {}; creating it...",
          paymentGatewayEvent.getSubscriptionId());
      crmDestinations.insertRecurringDonation(paymentGatewayEvent);
    } else {
      log.info("found an existing CRM recurring donation using subscription {}",
          paymentGatewayEvent.getSubscriptionId());
    }
  }

  public void closeRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = crmSource.getRecurringDonation(paymentGatewayEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find CRM recurring donation using subscriptionId{}",
          paymentGatewayEvent.getSubscriptionId());
      return;
    }

    crmDestinations.closeRecurringDonation(paymentGatewayEvent);
  }

  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = crmSource.getRecurringDonation(manageDonationEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find CRM recurring donation using recurringDonationId {}", manageDonationEvent.getDonationId());
      return;
    }

    crmDestinations.updateRecurringDonation(manageDonationEvent);
    manageDonationEvent.setSubscriptionId(crmSource.getSubscriptionId(manageDonationEvent));
    paymentGatewayService.updateSubscription(manageDonationEvent);
  }

  public void chargeDeposited(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> donation = crmSource.getDonation(paymentGatewayEvent);

    if (donation.isEmpty()) {
      log.info("missing an CRM donation for transaction {}; notifying staff...", paymentGatewayEvent.getTransactionId());
      // TODO: Send email alert
      // TODO: First retry the original charge/intent succeeded event, just in case the original webhook failed?
      return;
    }

    crmDestinations.insertDonationDeposit(paymentGatewayEvent);
  }
}