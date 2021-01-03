package com.impactupgrade.common.crm;

import com.impactupgrade.common.paymentgateway.model.PaymentGatewayEvent;

import java.util.List;

public class AggregateCrmDestinationService implements CrmDestinationService {

  private final CrmDestinationService crmPrimaryService;
  private final List<CrmDestinationService> crmSecondaryServices;

  public AggregateCrmDestinationService(
      CrmDestinationService crmPrimaryService, List<CrmDestinationService> crmSecondaryServices) {
    this.crmPrimaryService = crmPrimaryService;
    this.crmSecondaryServices = crmSecondaryServices;
  }

  @Override
  public String insertAccount(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.insertAccount(paymentGatewayEvent);
    }
    return crmPrimaryService.insertAccount(paymentGatewayEvent);
  }

  @Override
  public String insertContact(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    for (CrmDestinationService crmService : crmSecondaryServices) {
      crmService.insertContact(paymentGatewayEvent);
    }
    return crmPrimaryService.insertContact(paymentGatewayEvent);
  }
}
