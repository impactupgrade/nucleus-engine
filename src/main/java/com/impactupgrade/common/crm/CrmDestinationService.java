package com.impactupgrade.common.crm;

import com.impactupgrade.common.paymentgateway.model.PaymentGatewayEvent;

public interface CrmDestinationService {

  String insertAccount(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  String insertContact(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  void insertDonationDeposit(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
  void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception;
}
