/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.DonorWranglerClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmContactListType;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.PagedResults;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DonorWranglerCrmService implements BasicCrmService {

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
    return !Strings.isNullOrEmpty(env.getConfig().donorwrangler.subdomain);
  }

  @Override
  public void init(Environment env) {
    this.env = env;
    this.dwClient = env.donorwranglerClient();
  }

  @Override
  public Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception {
    //Not currently implemented
    return Optional.empty();
  }

  @Override
  public Optional<CrmContact> getFilteredContactByEmail(String email, String filter) throws Exception {
    //Not currently implemented
    return Optional.empty();
  }

  @Override
  public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) throws Exception {
    // TODO: For now, supporting the individual use cases, but this needs reworked at the client level. Add support for
    //  combining clauses, owner, keyword search, pagination, etc.

    if (!contactSearch.ids.isEmpty()) {
      Optional<CrmContact> crmContact = toCrmContact(
          dwClient.contactSearch("id", contactSearch.ids.stream().findFirst().get())
      );
      return PagedResults.getPagedResultsFromCurrentOffset(crmContact, contactSearch);
    } else if (!contactSearch.emails.isEmpty()) {
      Optional<CrmContact> crmContact = toCrmContact(
          dwClient.getContactByEmail(contactSearch.emails.stream().findFirst().get())
      );
      return PagedResults.getPagedResultsFromCurrentOffset(crmContact, contactSearch);
    } else if (!contactSearch.phones.isEmpty()) {
      Optional<CrmContact> crmContact = toCrmContact(
          dwClient.contactSearch("phone", contactSearch.phones.stream().findFirst().get())
      );
      return PagedResults.getPagedResultsFromCurrentOffset(crmContact, contactSearch);
    } else {
      return new PagedResults<>();
    }
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
  public List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds) throws Exception {
    // TODO: Josh?
    return Collections.emptyList();
  }

  @Override
  public String insertDonation(CrmDonation crmDonation) throws Exception {
    return dwClient.insertDonation(crmDonation);
  }

  @Override
  public void updateDonation(CrmDonation crmDonation) throws Exception {
    // TODO
  }

  @Override
  public void refundDonation(CrmDonation crmDonation) throws Exception {
    // no refund support
  }

  @Override
  public List<CrmUser> getUsers() throws Exception {
    return Collections.emptyList();
  }

  @Override
  public Map<String, String> getContactLists(CrmContactListType listType) throws Exception {
    return Collections.emptyMap();
  }

  @Override
  public Map<String, String> getFieldOptions(String object) throws Exception {
    return Collections.emptyMap();
  }

  @Override
  public List<CrmContact> getEmailContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    // TODO: lastUpdate field now available
    return Collections.emptyList();
  }

  @Override
  public List<CrmAccount> getEmailAccounts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public List<CrmContact> getSmsContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    // TODO: lastUpdate field now available
    return Collections.emptyList();
  }

  @Override
  public double getDonationsTotal(String filter) throws Exception {
    // TODO
    return 0.0;
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
