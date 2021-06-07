/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.event.CrmImportEvent;
import com.impactupgrade.nucleus.model.crm.CrmContact;
import com.impactupgrade.nucleus.model.crm.CrmDonation;
import com.impactupgrade.nucleus.model.crm.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.event.ManageDonationEvent;
import com.impactupgrade.nucleus.model.event.MessagingWebhookEvent;
import com.impactupgrade.nucleus.model.event.OpportunityEvent;
import com.impactupgrade.nucleus.model.event.PaymentGatewayWebhookEvent;

import java.util.List;
import java.util.Optional;

public interface CrmService {

  Optional<CrmContact> getContactByEmail(String email) throws Exception;
  Optional<CrmContact> getContactByPhone(String phone) throws Exception;

  void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception;
  void addContactToList(CrmContact crmContact, String listId) throws Exception;

  String insertOpportunity(OpportunityEvent opportunityEvent) throws Exception;

  String insertContact(MessagingWebhookEvent messagingWebhookEvent) throws Exception;
  void updateContact(MessagingWebhookEvent messagingWebhookEvent) throws Exception;

  Optional<CrmDonation> getDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  String insertAccount(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
  String insertContact(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception;
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

  void processImport(List<CrmImportEvent> importEvents) throws Exception;
}
