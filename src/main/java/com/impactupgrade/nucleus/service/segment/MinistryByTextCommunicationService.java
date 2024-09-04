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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
  public void syncContacts(Calendar lastSync) throws Exception {
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
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    // TODO
  }

  @Override
  public void upsertContact(String contactId) throws Exception {
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
}
