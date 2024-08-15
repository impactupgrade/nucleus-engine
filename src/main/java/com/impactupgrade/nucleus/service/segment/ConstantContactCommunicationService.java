/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.ConstantContactClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PagedResults;

import java.util.Calendar;
import java.util.Optional;

// TODO: NEEDS FULLY UPDATED WITH EVERYTHING NEW IN THE MAILCHIMP SERVICE! Likely implies genericizing some of the MC
//  logic and pulling it upstream to AbstractCommunicationService.
public class ConstantContactCommunicationService extends AbstractCommunicationService {

  @Override
  public String name() {
    return "constantContact";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().constantContact != null && !env.getConfig().constantContact.isEmpty();
  }

  @Override
  public void syncContacts(Calendar lastSync) throws Exception {
    for (EnvironmentConfig.CommunicationPlatform communicationPlatform : env.getConfig().constantContact) {
      ConstantContactClient constantContactClient = new ConstantContactClient(communicationPlatform, env);

      for (EnvironmentConfig.CommunicationList communicationList : communicationPlatform.lists) {
        PagedResults<CrmContact> pagedResults = env.primaryCrmService().getEmailContacts(lastSync, communicationList);
        for (PagedResults.ResultSet<CrmContact> resultSet : pagedResults.getResultSets()) {
          for (CrmContact crmContact : resultSet.getRecords()) {
            env.logJobInfo("upserting contact {} {} on list {}", crmContact.id, crmContact.email, communicationList.id);
            constantContactClient.upsertContact(crmContact, communicationList.id);
          }
        }
      }
    }
  }

  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    //TODO: remove contacts?
  }

  @Override
  public void upsertContact(String contactId) throws Exception {
    CrmService crmService = env.primaryCrmService();

    for (EnvironmentConfig.CommunicationPlatform communicationPlatform : env.getConfig().constantContact) {
      ConstantContactClient constantContactClient = new ConstantContactClient(communicationPlatform, env);

      for (EnvironmentConfig.CommunicationList communicationList : communicationPlatform.lists) {
        Optional<CrmContact> crmContact = crmService.getFilteredContactById(contactId, communicationList.crmFilter);

        if (crmContact.isPresent() && !Strings.isNullOrEmpty(crmContact.get().phoneNumberForSMS())) {
          env.logJobInfo("upserting contact {} {} on list {}", crmContact.get().id, crmContact.get().phoneNumberForSMS(), communicationList.id);
          constantContactClient.upsertContact(crmContact.get(), communicationList.id);
        }
      }
    }
  }

  @Override
  public void massArchive() throws Exception {
    // TODO
  }
}