package com.impactupgrade.common.paymentgateway;

import com.impactupgrade.common.crm.AggregateCrmDestinationService;
import com.impactupgrade.common.crm.CrmSourceService;
import com.impactupgrade.common.crm.model.CrmCampaign;
import com.impactupgrade.common.crm.model.CrmDonation;
import com.impactupgrade.common.crm.model.CrmRecurringDonation;
import com.impactupgrade.common.environment.Environment;
import com.impactupgrade.common.paymentgateway.model.PaymentGatewayEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class DonationService {

  private static final Logger log = LogManager.getLogger(DonationService.class.getName());

  private final Environment env;
  private final CrmSourceService crmSource;
  private final AggregateCrmDestinationService crmDestinations;

  public DonationService(Environment env) {
    this.env = env;
    crmSource = env.crmSourceService();
    crmDestinations = env.crmDonationDestinationServices();
  }

  public void createDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> existingDonation = crmSource.getDonation(paymentGatewayEvent);

    if (existingDonation.isPresent()) {
      // donation already exists in Salesforce with the transactionId - do not process the donation
      log.info("found existing SFDC donation {} using transaction {}; skipping creation...",
          existingDonation.get().id(), paymentGatewayEvent.getTransactionId());
      return;
    }

    Optional<CrmCampaign> campaign = crmSource.getCampaignByIdOrDefault(paymentGatewayEvent.getCampaignId());
    Optional<String> recurringDonationId = Optional.empty();

    if (paymentGatewayEvent.isTransactionRecurring()) {
      Optional<CrmRecurringDonation> recurringDonation = crmSource.getRecurringDonation(paymentGatewayEvent);

      if (recurringDonation.isEmpty()) {
        log.info("unable to find SFDC recurring donation using subscriptionId {}; creating it...",
            paymentGatewayEvent.getSubscriptionId());
        // NOTE: See the note on the customer.subscription.created event handling. We insert recurring donations
        // from subscription creation ONLY if it's in a trial period and starts in the future. Otherwise, let the
        // first donation do it in order to prevent timing issues.
        recurringDonationId = Optional.of(crmDestinations.insertRecurringDonation(paymentGatewayEvent));
      } else {
        log.info("found SFDC recurring donation {} using subscriptionId {}",
            recurringDonation.get().id(), paymentGatewayEvent.getSubscriptionId());
        recurringDonationId = recurringDonation.map(CrmRecurringDonation::id);
      }
    }

    crmDestinations.insertDonation(paymentGatewayEvent, recurringDonationId);
  }

  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> donation = crmSource.getDonation(paymentGatewayEvent);

    // make sure that a donation was found and that only 1 donation was found
    if (donation.isPresent()) {
      log.info("refunding SFDC donation {} with refunded charge {}", donation.get().id(), paymentGatewayEvent.getTransactionId());
      // Refund the transaction in Salesforce
      crmDestinations.refundDonation(paymentGatewayEvent);
    } else {
      log.warn("unable to find SFDC donation using transaction {}", paymentGatewayEvent.getTransactionId());
    }
  }

  public void processSubscription(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = crmSource.getRecurringDonation(paymentGatewayEvent);

    if (recurringDonation.isEmpty()) {
      log.info("unable to find SFDC recurring donation using subscription {}; creating it...",
          paymentGatewayEvent.getSubscriptionId());
      crmDestinations.insertRecurringDonation(paymentGatewayEvent);
    } else {
      log.info("found an existing SFDC recurring donation using subscription {}",
          paymentGatewayEvent.getSubscriptionId());
    }
  }

  public void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = crmSource.getRecurringDonation(paymentGatewayEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find SFDC recurring donation using subscriptionId{}",
          paymentGatewayEvent.getSubscriptionId());
      return;
    }

    crmDestinations.closeRecurringDonation(paymentGatewayEvent);
  }

  public void chargeDeposited(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> donation = crmSource.getDonation(paymentGatewayEvent);

    if (donation.isEmpty()) {
      log.info("missing an SFDC donation for transaction {}; notifying staff...", paymentGatewayEvent.getTransactionId());
      // TODO: Send email alert
      // TODO: First retry the original charge/intent succeeded event, just in case the original webhook failed?
      return;
    }

    crmDestinations.insertDonationDeposit(paymentGatewayEvent);
  }
}
