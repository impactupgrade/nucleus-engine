/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.client.ConstantContactClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConstantContactCommunicationService extends AbstractCommunicationService {

  private ConstantContactClient constantContactClient;
  private final Map<String, ConstantContactClient.CustomField> customFieldsCache = new HashMap<>();

  @Override
  public String name() {
    return "constantcontact";
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
    ConstantContactClient client = getClient(config);
    List<String> emails = client.getExistingContactEmails();
    return new HashSet<>(emails);
  }

  @Override
  protected void executeBatchUpsert(List<CrmContact> contacts, Map<String, Map<String, Object>> customFields, 
      Map<String, Set<String>> tags, EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    ConstantContactClient client = getClient(config);
    
    String listId = list != null ? list.id : null;
    if (listId == null || listId.isEmpty()) {
      env.logJobInfo("No listId specified for Constant Contact platform config, using bulk import without list assignment");
    }
    
    client.bulkImportContactsWithCustomFields(contacts, listId, customFields);
  }

  @Override
  protected void executeBatchArchive(Set<String> emails, String listId, EnvironmentConfig.CommunicationPlatform config) throws Exception {
    ConstantContactClient client = getClient(config);
    client.archiveContacts(new ArrayList<>(emails));
  }

  @Override
  protected List<String> getUnsubscribedEmails(String listId, Calendar lastSync, EnvironmentConfig.CommunicationPlatform config) throws Exception {
    ConstantContactClient client = getClient(config);
    return client.getUnsubscribedEmails();
  }

  @Override
  protected List<String> getBouncedEmails(String listId, Calendar lastSync, EnvironmentConfig.CommunicationPlatform config) throws Exception {
    return new ArrayList<>();
  }

  @Override
  protected Map<String, Object> buildPlatformCustomFields(CrmContact crmContact, EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    ConstantContactClient client = getClient(config);
    Map<String, Object> platformCustomFields = new HashMap<>();
    
    List<CustomField> customFields = buildContactCustomFields(crmContact, config, list);
    if (customFields.isEmpty()) {
      return platformCustomFields;
    }
    
    // Populate cache if empty
    if (customFieldsCache.isEmpty()) {
      List<ConstantContactClient.CustomField> existingFields = client.getCustomFields();
      for (ConstantContactClient.CustomField field : existingFields) {
        customFieldsCache.put(field.name, field);
      }
    }
    
    for (CustomField customField : customFields) {
      if (customField.value == null) {
        continue;
      }
      
      String fieldName = customField.name;
      ConstantContactClient.CustomField ccField = customFieldsCache.get(fieldName);
      if (ccField == null) {
        String fieldType = "TEXT";
        if (customField.type == CustomFieldType.NUMBER) {
          fieldType = "NUMBER";
        } else if (customField.type == CustomFieldType.DATE) {
          fieldType = "DATE";
        } else if (customField.type == CustomFieldType.BOOLEAN) {
          fieldType = "TEXT";
        }
        
        try {
          ccField = client.createCustomField(fieldName, fieldName, fieldType);
          customFieldsCache.put(fieldName, ccField);
        } catch (Exception e) {
          env.logJobWarn("Failed to create custom field '{}' in Constant Contact: {}", fieldName, e.getMessage());
          continue;
        }
      }
      
      if (ccField != null) {
        Object value = customField.value;
        if (customField.type == CustomFieldType.BOOLEAN) {
          value = ((Boolean) value) ? "1" : "0";
        } else if (customField.type == CustomFieldType.DATE) {
          Calendar calendar = (Calendar) value;
          value = String.format("%04d-%02d-%02d", 
              calendar.get(Calendar.YEAR),
              calendar.get(Calendar.MONTH) + 1,
              calendar.get(Calendar.DAY_OF_MONTH));
        }
        platformCustomFields.put(ccField.customFieldId, value.toString());
      }
    }
    
    return platformCustomFields;
  }

  private ConstantContactClient getClient(EnvironmentConfig.CommunicationPlatform platformConfig) {
    if (constantContactClient == null) {
      constantContactClient = new ConstantContactClient(platformConfig, env);
    }
    return constantContactClient;
  }
}