/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.impactupgrade.nucleus.client.DonorWranglerClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class DonorWranglerCrmService implements BasicCrmService {

  private static final Logger log = LogManager.getLogger(DonorWranglerCrmService.class);

  private static final ObjectMapper mapper = new ObjectMapper();
  static {
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  protected Environment env;
  protected DonorWranglerClient dwClient;

  @Override
  public String name() { return "donorwrangler"; }

  @Override
  public void init(Environment env) {
    this.env = env;
    this.dwClient = env.donorwranglerClient();
  }

  @Override
  public Optional<CrmAccount> getAccountById(String id) throws Exception {
    // no accounts in Donor Wrangler
    return Optional.empty();
  }

  @Override
  public Optional<CrmAccount> getAccountByCustomerId(String customerId) throws Exception {
    // no accounts in Donor Wrangler
    return Optional.empty();
  }

  @Override
  public Optional<CrmContact> getContactById(String id) throws Exception {
    return toCrmContact(
        dwClient.contactSearch("id", id)
    );
  }

  @Override
  public Optional<CrmContact> getContactByEmail(String email) throws Exception {
    return toCrmContact(
        dwClient.getContactByEmail(email)
    );
  }

  @Override
  public Optional<CrmContact> getContactByPhone(String phone) throws Exception {
    return toCrmContact(
      dwClient.contactSearch("phone", phone)
    );
  }

  @Override
  public String insertAccount(CrmAccount crmAccount) throws Exception {
    // no accounts in Donor Wrangler
    return null;
  }

  @Override
  public void updateAccount(CrmAccount crmAccount) throws Exception {
    // no accounts in Donor Wrangler
  }

  @Override
  public String insertAccount(PaymentGatewayEvent PaymentGatewayEvent) throws Exception {
    // no accounts in Donor Wrangler
    return null;
  }

  @Override
  public String insertContact(CrmContact crmContact) throws Exception {
    return dwClient.upsertContact(crmContact);
  }

  @Override
  public void updateContact(CrmContact crmContact) throws Exception {
    dwClient.upsertContact(crmContact);
  }

  @Override
  public Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
    // TODO: Josh?
    return Optional.empty();
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    // no true RD support, outside of tracking payments within a pledged donation
    return Optional.empty();
  }

  @Override
  public List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception {
    // no true RD support, outside of tracking payments within a pledged donation
    return Collections.emptyList();
  }

  @Override
  public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return dwClient.insertDonation(paymentGatewayEvent);
  }

  @Override
  public void insertDonationReattempt(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO
  }

  @Override
  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // no refund support
  }

  @Override
  public void insertDonationDeposit(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: Josh
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // no true RD support, outside of tracking payments within a pledged donation
    return Optional.empty();
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // no true RD support, outside of tracking payments within a pledged donation
    return null;
  }

  @Override
  public void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // no true RD support, outside of tracking payments within a pledged donation
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    // no true RD support, outside of tracking payments within a pledged donation
    return Optional.empty();
  }

  @Override
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    // no true RD support, outside of tracking payments within a pledged donation
  }

  @Override
  public void closeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    // no true RD support, outside of tracking payments within a pledged donation
  }

  @Override
  public List<CrmDonation> getDonationsByAccountId(String accountId) throws Exception {
    // technically only needed for Donor Portal concepts, which will need rethought since it focuses on the account level
    return Collections.emptyList();
  }

  @Override
  public List<CrmContact> getContactsUpdatedSince(Calendar calendar) throws Exception {
    // TODO: lastUpdate field now available
    return Collections.emptyList();
  }

  @Override
  public List<CrmContact> getDonorContactsSince(Calendar calendar) throws Exception {
    // TODO: lastUpdate field now available
    return Collections.emptyList();
  }

  @Override
  public List<CrmContact> getAllContacts() throws Exception {
    // TODO
    return Collections.emptyList();
  }

  @Override
  public List<CrmContact> getAllDonorContacts() throws Exception {
    // TODO
    return Collections.emptyList();
  }

  @Override
  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
    // TODO
  }

  @Override
  public void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception {
    // TODO
  }

  protected Optional<CrmContact> toCrmContact(Optional<DonorWranglerClient.DwDonor> donor) {
    return donor.map(this::toCrmContact);
  }

  // TODO: map the rest
  protected CrmContact toCrmContact(DonorWranglerClient.DwDonor donor) {
    return new CrmContact(donor.id() + "");
  }
}
