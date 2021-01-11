package com.impactupgrade.common.crm;

import com.impactupgrade.common.paymentgateway.model.PaymentGatewayEvent;

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
  public String insertAccount(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    String primaryId = crmPrimaryService.insertAccount(paymentGatewayEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.insertAccount(paymentGatewayEvent);
    }
    return primaryId;
  }

  @Override
  public String insertContact(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    String primaryId = crmPrimaryService.insertContact(paymentGatewayEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.insertContact(paymentGatewayEvent);
    }
    return primaryId;
  }

  @Override
  public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    String primaryId = crmPrimaryService.insertDonation(paymentGatewayEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.insertDonation(paymentGatewayEvent);
    }
    return primaryId;
  }

  @Override
  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    crmPrimaryService.refundDonation(paymentGatewayEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.refundDonation(paymentGatewayEvent);
    }
  }

  @Override
  public void insertDonationDeposit(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    crmPrimaryService.insertDonationDeposit(paymentGatewayEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.insertDonationDeposit(paymentGatewayEvent);
    }
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    String primaryId = crmPrimaryService.insertRecurringDonation(paymentGatewayEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.insertRecurringDonation(paymentGatewayEvent);
    }
    return primaryId;
  }

  @Override
  public void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    crmPrimaryService.closeRecurringDonation(paymentGatewayEvent);
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.closeRecurringDonation(paymentGatewayEvent);
    }
  }
}
