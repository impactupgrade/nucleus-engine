/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.impactupgrade.nucleus.client.DonorWranglerClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
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
  public boolean isConfigured(Environment env) {
    return env.getConfig().donorwrangler != null;
  }

  @Override
  public void init(Environment env) {
    this.env = env;
    this.dwClient = env.donorwranglerClient();
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
  public List<CrmContact> searchContacts(String firstName, String lastName, String email, String phone, String address) {
    throw new RuntimeException("not implemented");
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
  public List<CrmContact> getEmailContacts(Calendar updatedSince, String filter) throws Exception {
    // TODO: lastUpdate field now available
    return Collections.emptyList();
  }

  @Override
  public List<CrmContact> getEmailDonorContacts(Calendar updatedSince, String filter) throws Exception {
    // TODO: lastUpdate field now available
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

  @Override
  public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
    return new EnvironmentConfig.CRMFieldDefinitions();
  }

  protected Optional<CrmContact> toCrmContact(Optional<DonorWranglerClient.DwDonor> donor) {
    return donor.map(this::toCrmContact);
  }

  // TODO: map the rest
  protected CrmContact toCrmContact(DonorWranglerClient.DwDonor donor) {
    return new CrmContact(donor.id() + "");
  }
}
