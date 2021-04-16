package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CRMImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.MessagingWebhookEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;

import java.util.List;
import java.util.Optional;

public interface CrmDestinationService {

  void updateDonation(CrmDonation donation) throws Exception;
  void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception;

  String insertAccount(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  String insertContact(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  String insertDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  void refundDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  void insertDonationDeposit(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  String insertRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  void closeRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  String insertContact(MessagingWebhookEvent messagingWebhookEvent) throws Exception;
  void smsSignup(MessagingWebhookEvent messagingWebhookEvent) throws Exception;

  void processImport(List<CRMImportEvent> importEvents) throws Exception;
}
