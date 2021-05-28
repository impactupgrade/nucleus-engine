package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CRMImportEvent;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.OpportunityEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;

import java.util.List;
import java.util.Optional;

public interface CrmService {

  Optional<CrmContact> getContactByEmail(String email) throws Exception;
  Optional<CrmContact> getContactByPhone(String phone) throws Exception;

  // Most flows need to create some common record types. Let's try to keep these generic whenever possible!
  String insertAccount(CrmAccount crmAccount) throws Exception;
  String insertContact(CrmContact crmContact) throws Exception;
  void updateContact(CrmContact crmContact) throws Exception;

  void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception;
  void addContactToList(CrmContact crmContact, String listId) throws Exception;

  String insertOpportunity(OpportunityEvent opportunityEvent) throws Exception;

  Optional<CrmDonation> getDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  String insertDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  void insertDonationReattempt(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  void refundDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  void insertDonationDeposit(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  String insertRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  void closeRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  void closeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception;

  Optional<CrmRecurringDonation> getRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception;
  String getSubscriptionId(ManageDonationEvent manageDonationEvent) throws Exception;
  void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception;

  void processImport(List<CRMImportEvent> importEvents) throws Exception;
}
