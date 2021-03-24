package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CRMImportEvent;
import com.impactupgrade.nucleus.model.MessagingWebhookEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;

import java.util.List;

public class AggregateCrmDestinationService implements CrmDestinationService {

  private final CrmDestinationService crmPrimaryService;
  private final CrmDestinationService[] crmSecondaryServices;

  public AggregateCrmDestinationService(
      CrmDestinationService crmPrimaryService, CrmDestinationService... crmSecondaryServices) {
    this.crmPrimaryService = crmPrimaryService;
    this.crmSecondaryServices = crmSecondaryServices;
  }

  // TODO: For all of these, will likely need to provide the primary id (or additional context) to the secondaries

  @Override
  public void updateDonation(CrmDonation donation) throws Exception {
    crmPrimaryService.updateDonation(donation);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.updateDonation(donation);
    }
  }

  @Override
  public String insertAccount(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    String primaryId = crmPrimaryService.insertAccount(paymentGatewayEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.insertAccount(paymentGatewayEvent);
    }
    return primaryId;
  }

  @Override
  public String insertContact(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    String primaryId = crmPrimaryService.insertContact(paymentGatewayEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.insertContact(paymentGatewayEvent);
    }
    return primaryId;
  }

  @Override
  public String insertDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    String primaryId = crmPrimaryService.insertDonation(paymentGatewayEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.insertDonation(paymentGatewayEvent);
    }
    return primaryId;
  }

  @Override
  public void refundDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    crmPrimaryService.refundDonation(paymentGatewayEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.refundDonation(paymentGatewayEvent);
    }
  }

  @Override
  public void insertDonationDeposit(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    crmPrimaryService.insertDonationDeposit(paymentGatewayEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.insertDonationDeposit(paymentGatewayEvent);
    }
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    String primaryId = crmPrimaryService.insertRecurringDonation(paymentGatewayEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.insertRecurringDonation(paymentGatewayEvent);
    }
    return primaryId;
  }

  @Override
  public void closeRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    crmPrimaryService.closeRecurringDonation(paymentGatewayEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.closeRecurringDonation(paymentGatewayEvent);
    }
  }

  @Override
  public String insertContact(MessagingWebhookEvent messagingWebhookEvent) throws Exception {
    String primaryId = crmPrimaryService.insertContact(messagingWebhookEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.insertContact(messagingWebhookEvent);
    }
    return primaryId;
  }

  @Override
  public void smsSignup(MessagingWebhookEvent messagingWebhookEvent) throws Exception {
    crmPrimaryService.smsSignup(messagingWebhookEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.smsSignup(messagingWebhookEvent);
    }
  }

  @Override
  public void processImport(List<CRMImportEvent> importEvents) throws Exception {
    crmPrimaryService.processImport(importEvents);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.processImport(importEvents);
    }
  }
}
