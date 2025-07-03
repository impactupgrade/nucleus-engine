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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
  protected List<EnvironmentConfig.CommunicationPlatform> getPlatformConfigs() {
    return env.getConfig().constantContact;
  }

  @Override
  protected Set<String> getExistingContactEmails(EnvironmentConfig.CommunicationPlatform config, String listId) {
    // ConstantContact doesn't have a simple way to get all contacts for a list
    // Return empty set for now - batch operations will handle duplicates
    return new HashSet<>();
  }

  @Override
  protected void executeBatchUpsert(List<CrmContact> contacts,
      Map<String, Map<String, Object>> customFields, Map<String, Set<String>> tags,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    ConstantContactClient constantContactClient = new ConstantContactClient(config, env);

    for (CrmContact crmContact : contacts) {
      env.logJobInfo("upserting contact {} {} on list {}", crmContact.id, crmContact.email, list.id);
      constantContactClient.upsertContact(crmContact, list.id);
    }
  }

  @Override
  protected void executeBatchArchive(Set<String> emails, String listId,
      EnvironmentConfig.CommunicationPlatform config) throws Exception {
    // TODO: ConstantContact archive implementation
  }

  @Override
  protected List<String> getUnsubscribedEmails(String listId, Calendar lastSync,
      EnvironmentConfig.CommunicationPlatform config) throws Exception {
    // TODO: ConstantContact unsubscribe implementation
    return new ArrayList<>();
  }

  @Override
  protected List<String> getBouncedEmails(String listId, Calendar lastSync,
      EnvironmentConfig.CommunicationPlatform config) throws Exception {
    // TODO: ConstantContact bounced implementation
    return new ArrayList<>();
  }

  @Override
  protected Map<String, Object> buildPlatformCustomFields(CrmContact crmContact,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    // ConstantContact custom fields are handled in the client - return empty map
    return new HashMap<>();
  }
}