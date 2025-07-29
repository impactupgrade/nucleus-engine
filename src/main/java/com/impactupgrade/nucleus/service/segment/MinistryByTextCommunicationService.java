/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.MinistryByTextClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PagedResults;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class MinistryByTextCommunicationService extends AbstractCommunicationService {

  protected Environment env;

  @Override
  public String name() {
    return "ministrybytext";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().ministrybytext != null && !env.getConfig().ministrybytext.isEmpty();
  }

  @Override
  public void init(Environment env) {
    this.env = env;
  }

  @Override
  protected List<EnvironmentConfig.CommunicationPlatform> getPlatformConfigs() {
    return env.getConfig().ministrybytext.stream()
        .map(mbt -> (EnvironmentConfig.CommunicationPlatform) mbt)
        .collect(Collectors.toList());
  }

  @Override
  protected Set<String> getExistingContactEmails(EnvironmentConfig.CommunicationPlatform config, String listId) {
    // MinistryByText uses phone numbers, not emails - return empty set
    return new HashSet<>();
  }

  @Override
  public void syncContacts(Calendar lastSync) throws Exception {
    // Override to use SMS contacts instead of email contacts
    for (EnvironmentConfig.MBT mbtConfig : env.getConfig().ministrybytext) {
      MinistryByTextClient mbtClient = new MinistryByTextClient(mbtConfig, env);

      for (EnvironmentConfig.CommunicationList communicationList : mbtConfig.lists) {
        Set<String> seenPhones = new HashSet<>();

        PagedResults<CrmContact> pagedResults = env.primaryCrmService().getSmsContacts(lastSync, communicationList);
        for (PagedResults.ResultSet<CrmContact> resultSet : pagedResults.getResultSets()) {

          List<CrmContact> crmContacts = new ArrayList<>();
          for (CrmContact crmContact : resultSet.getRecords()) {
            String smsPn = crmContact.phoneNumberForSMS();
            if (!Strings.isNullOrEmpty(smsPn) && !seenPhones.contains(smsPn)) {
              env.logJobInfo("upserting contact {} {} on list {}", crmContact.id, smsPn, communicationList.id);
              crmContacts.add(crmContact);
              seenPhones.add(smsPn);
            }
          }

          mbtClient.upsertSubscribersBulk(crmContacts, mbtConfig, communicationList);
        }
      }
    }
  }

  @Override
  public void upsertContact(String contactId) throws Exception {
    // Override to use SMS logic instead of email logic
    CrmService crmService = env.primaryCrmService();

    for (EnvironmentConfig.MBT mbtConfig : env.getConfig().ministrybytext) {
      MinistryByTextClient mbtClient = new MinistryByTextClient(mbtConfig, env);

      for (EnvironmentConfig.CommunicationList communicationList : mbtConfig.lists) {
        Optional<CrmContact> crmContact = crmService.getFilteredContactById(contactId, communicationList.crmFilter);

        if (crmContact.isPresent() && !Strings.isNullOrEmpty(crmContact.get().phoneNumberForSMS())) {
          env.logJobInfo("upserting contact {} {} on list {}", crmContact.get().id, crmContact.get().phoneNumberForSMS(), communicationList.id);
          mbtClient.upsertSubscriber(crmContact.get(), mbtConfig, communicationList);
        }
      }
    }
  }

  @Override
  protected void executeBatchUpsert(List<CrmContact> contacts,
      Map<String, Map<String, Object>> customFields, Map<String, Set<String>> tags,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    // This method is not used since we override syncContacts and upsertContact
    throw new UnsupportedOperationException("MinistryByText uses custom SMS sync logic");
  }

  @Override
  protected void executeBatchArchive(Set<String> emails, String listId,
      EnvironmentConfig.CommunicationPlatform config) throws Exception {
    // TODO: MinistryByText archive implementation
  }

  @Override
  protected List<String> getUnsubscribedEmails(String listId, Calendar lastSync,
      EnvironmentConfig.CommunicationPlatform config) throws Exception {
    // TODO: MinistryByText unsubscribe implementation
    return new ArrayList<>();
  }

  @Override
  protected List<String> getBouncedEmails(String listId, Calendar lastSync,
      EnvironmentConfig.CommunicationPlatform config) throws Exception {
    // MinistryByText doesn't have bounced emails (SMS service)
    return new ArrayList<>();
  }

  @Override
  protected Map<String, Object> buildPlatformCustomFields(CrmContact crmContact,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    // MinistryByText doesn't use custom fields in the same way
    return new HashMap<>();
  }
}
