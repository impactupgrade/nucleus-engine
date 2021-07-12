package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;

import java.util.Optional;

public interface CrmNewDonationService extends CrmService {

  Optional<CrmDonation> getDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  String insertAccount(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  // We allow custom impls, but most orgs only insert the CrmContact, so do that as a default.
  default String insertContact(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    return insertContact(paymentGatewayEvent.getCrmContact());
  }
  String insertDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  void insertDonationReattempt(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  void refundDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  void insertDonationDeposit(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  String insertRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  void closeRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
}
