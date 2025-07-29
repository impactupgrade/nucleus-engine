/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.util.Utils;

import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class AbstractCommunicationService implements CommunicationService {

  protected Environment env;

  @Override
  public void init(Environment env) {
    this.env = env;
    // TODO: DO NOT try to pull CrmService here! We should consider a refactor (possibly splitting EmailService
    //  into an email sync service vs. transactional email service. Currently, nucleus-core and others use
    //  SendGridEmailService, even when no CrmService is identified.
  }

  protected List<CustomField> buildContactCustomFields(CrmContact crmContact,
      EnvironmentConfig.CommunicationPlatform communicationPlatform,
      EnvironmentConfig.CommunicationList communicationList) throws Exception {
    List<CustomField> customFields = new ArrayList<>();

    if (!Strings.isNullOrEmpty(crmContact.title)) {
      customFields.add(new CustomField("title", CustomFieldType.STRING, crmContact.title));
    }

    if (!Strings.isNullOrEmpty(crmContact.mailingAddress.city)) {
      customFields.add(new CustomField("city", CustomFieldType.STRING, crmContact.mailingAddress.city));
    }
    if (!Strings.isNullOrEmpty(crmContact.mailingAddress.state)) {
      customFields.add(new CustomField("state", CustomFieldType.STRING, crmContact.mailingAddress.state));
    }
    if (!Strings.isNullOrEmpty(crmContact.mailingAddress.postalCode)) {
      customFields.add(new CustomField("postal_code", CustomFieldType.STRING, crmContact.mailingAddress.postalCode));
    }
    if (!Strings.isNullOrEmpty(crmContact.mailingAddress.country)) {
      customFields.add(new CustomField("country", CustomFieldType.STRING, crmContact.mailingAddress.country));
    }

    customFields.add(new CustomField("crm_contact_id", CustomFieldType.STRING, crmContact.id));
    if (crmContact.account.id != null) {
      customFields.add(new CustomField("crm_account_id", CustomFieldType.STRING, crmContact.account.id));
    }
    if (crmContact.firstDonationDate != null) {
      customFields.add(new CustomField("date_of_first_donation", CustomFieldType.DATE, crmContact.firstDonationDate));
    }
    if (crmContact.lastDonationDate != null) {
      customFields.add(new CustomField("date_of_last_donation", CustomFieldType.DATE, crmContact.lastDonationDate));
    }
    if (crmContact.largestDonationAmount != null) {
      customFields.add(new CustomField("largest_donation", CustomFieldType.NUMBER, crmContact.largestDonationAmount));
    }
    if (crmContact.totalDonationAmount != null) {
      customFields.add(new CustomField("total_of_donations", CustomFieldType.NUMBER, crmContact.totalDonationAmount));
    }
    if (crmContact.numDonations != null) {
      customFields.add(new CustomField("number_of_donations", CustomFieldType.NUMBER, crmContact.numDonations));
    }
    if (crmContact.totalDonationAmountYtd != null) {
      customFields.add(new CustomField("total_of_donations_ytd", CustomFieldType.NUMBER, crmContact.totalDonationAmountYtd));
    }
    if (crmContact.numDonationsYtd != null) {
      customFields.add(new CustomField("number_of_donations_ytd", CustomFieldType.NUMBER, crmContact.numDonationsYtd));
    }

    customFields.addAll(communicationPlatform.crmFieldToCommunicationFields.stream()
            .map(mapping -> getCustomField(crmContact, mapping))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet()));

    return customFields;
  }

  protected CustomField getCustomField(CrmContact crmContact, EnvironmentConfig.CrmFieldToCommunicationField mapping) {
    Object value = crmContact.fieldFetcher != null ? crmContact.fieldFetcher.apply(mapping.crmFieldName) : null;
    if (value != null) {
      return new CustomField(mapping.communicationFieldName, getCustomFieldType(value), value);
    } else {
      return null;
    }
  }

  protected enum CustomFieldType {
    STRING, NUMBER, BOOLEAN, DATE
  }
  protected static class CustomField {
    public String name;
    public CustomFieldType type;
    public Object value;
    public CustomField(String name, CustomFieldType type, Object value) {
      this.name = name;
      this.type = type;
      this.value = value;
    }
  }

  //TODO: explicitly define in env config?
  protected CustomFieldType getCustomFieldType(Object value) {
    if (value == null) {
      return null;
    }
    CustomFieldType customFieldType;
    if (value instanceof Number) {
      customFieldType = CustomFieldType.NUMBER;
    } else if (value instanceof Boolean) {
      customFieldType = CustomFieldType.BOOLEAN;
    } else if (value instanceof Date || value instanceof Calendar || value instanceof Temporal) {
      customFieldType = CustomFieldType.DATE;
    } else {
      customFieldType = CustomFieldType.STRING;
    }
    return customFieldType;
  }

  protected Set<String> getContactTagsCleaned(CrmContact crmContact, List<String> contactCampaignNames,
      EnvironmentConfig.CommunicationPlatform communicationPlatform,
      EnvironmentConfig.CommunicationList communicationList) throws Exception {
    Set<String> tags = buildContactTags(crmContact, contactCampaignNames, communicationPlatform, communicationList);

    // Mailchimp's Salesforce plugin chokes on tags > 80 chars, which seems like a sane limit anyway.
    Set<String> cleanedTags = new HashSet<>();
    for (String tag : tags) {
      if (tag.length() > 80) {
        tag = tag.substring(0, 80);
      }
      cleanedTags.add(tag);
    }

    return cleanedTags;
  }

  // Separate method, allowing orgs to add in (or completely override) the defaults.
  // NOTE: Only use alphanumeric and _ chars! Some providers, like SendGrid, are using custom fields for
  //  tags and have limitations on field names.
  // IMPORTANT: At the moment, any new tag added here must be added to EnvironmentConfig.CommunicationPlatform.controlledTags!
  protected Set<String> buildContactTags(CrmContact crmContact, List<String> contactCampaignNames,
      EnvironmentConfig.CommunicationPlatform communicationPlatform,
      EnvironmentConfig.CommunicationList communicationList) throws Exception {
    Set<String> tags = new HashSet<>();

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // DONATION METRICS
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    if (crmContact.lastDonationDate != null) {
      tags.add("donor");
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // INTERNAL INFO
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    if (crmContact.ownerName != null) {
      tags.add("owner_" + Utils.toSlug(crmContact.ownerName));
    }

    if (contactCampaignNames != null) {
      for (String c : contactCampaignNames) {
        tags.add("campaign_" + Utils.toSlug(c));
      }
    }

    // TODO: This should be a field. And maybe call it account_recordtype, to not confuse it with the Account.Type field.
    if (!Strings.isNullOrEmpty(crmContact.account.recordTypeName)) {
      tags.add("account_type_" + Utils.toSlug(crmContact.account.recordTypeName));
    }

    tags.addAll(communicationPlatform.crmFieldToCommunicationTags.stream()
            .map(mapping -> getTagName(crmContact, mapping))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet()));

    return tags;
  }

  protected String getTagName(CrmContact crmContact, EnvironmentConfig.CrmFieldToCommunicationTag mapping) {
    Object crmFieldValue = crmContact.fieldFetcher != null ? crmContact.fieldFetcher.apply(mapping.crmFieldName) : null;
    String crmFieldValueString = crmFieldValue == null ? "" : crmFieldValue.toString();
    if (evaluate(crmFieldValueString, mapping.operator, mapping.value)) {
      return mapping.communicationTagName;
    } else {
      return null;
    }
  }

  protected boolean evaluate(String crmFieldValueString, EnvironmentConfig.Operator operator, String value) {
    if (Strings.isNullOrEmpty(crmFieldValueString) || operator == null) {
      return false;
    }
    return switch(operator) {
      case NOT_EMPTY -> Strings.isNullOrEmpty(crmFieldValueString);
      case EQUAL_TO ->  crmFieldValueString.equalsIgnoreCase(value);
      case NOT_EQUAL_TO -> !crmFieldValueString.equalsIgnoreCase(value);
    };
  }

  /**
   * Returns the ID of the group from the config map
   */
  protected String getGroupIdFromName(String groupName, Map<String, String> groups) {
    return groups.entrySet().stream()
        .filter(e -> e.getKey().equalsIgnoreCase(groupName))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElseThrow(() -> new RuntimeException("group " + groupName + " not configured in environment.json"));
  }

  @Override
  public void syncContacts(Calendar lastSync) throws Exception {
    List<EnvironmentConfig.CommunicationPlatform> configs = getPlatformConfigs();
    for (EnvironmentConfig.CommunicationPlatform config : configs) {
      for (EnvironmentConfig.CommunicationList communicationList : config.lists) {
        // platform-specific preparation (e.g., cache clearing) once per communication list
        prepareBatchProcessing(config, communicationList);

        Set<String> existingEmails = getExistingContactEmails(config, communicationList.id);

        // Process CRM contacts
        PagedResults<CrmContact> contactPagedResults = env.primaryCrmService().getEmailContacts(lastSync, communicationList);
        for (PagedResults.ResultSet<CrmContact> resultSet : contactPagedResults.getResultSets()) {
          do {
            syncContactsBatch(resultSet, config, communicationList, existingEmails);
            if (!Strings.isNullOrEmpty(resultSet.getNextPageToken())) {
              resultSet = env.primaryCrmService().queryMoreContacts(resultSet.getNextPageToken());
            } else {
              resultSet = null;
            }
          } while (resultSet != null);
        }

        // Process CRM accounts as faux contacts
        PagedResults<CrmAccount> accountPagedResults = env.primaryCrmService().getEmailAccounts(lastSync, communicationList);
        for (PagedResults.ResultSet<CrmAccount> resultSet : accountPagedResults.getResultSets()) {
          do {
            PagedResults.ResultSet<CrmContact> fauxContacts = new PagedResults.ResultSet<>();
            fauxContacts.getRecords().addAll(resultSet.getRecords().stream().map(this::asCrmContact).toList());
            syncContactsBatch(fauxContacts, config, communicationList, existingEmails);
            if (!Strings.isNullOrEmpty(resultSet.getNextPageToken())) {
              resultSet = env.primaryCrmService().queryMoreAccounts(resultSet.getNextPageToken());
            } else {
              resultSet = null;
            }
          } while (resultSet != null);
        }
      }
    }
  }

  protected final void syncContactsBatch(PagedResults.ResultSet<CrmContact> resultSet,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList communicationList,
      Set<String> existingEmails) {
    if (resultSet.getRecords().isEmpty()) {
      return;
    }

    List<CrmContact> contactsToUpsert = new ArrayList<>();
    List<CrmContact> contactsToArchive = new ArrayList<>();
    List<CrmContact> crmContacts = resultSet.getRecords();

    // Transactional is always subscribed
    if (communicationList.type == EnvironmentConfig.CommunicationListType.TRANSACTIONAL) {
      contactsToUpsert.addAll(crmContacts);
    } else {
      crmContacts.forEach(crmContact -> (crmContact.canReceiveEmail() ? contactsToUpsert : contactsToArchive).add(crmContact));
    }

    try {
      Map<String, Map<String, Object>> contactsCustomFields = new HashMap<>();
      for (CrmContact crmContact : contactsToUpsert) {
        Map<String, Object> customFieldMap = buildPlatformCustomFields(crmContact, config, communicationList);
        contactsCustomFields.put(crmContact.email, customFieldMap);
      }

      Map<String, List<String>> crmContactCampaignNames = env.primaryCrmService().getContactsCampaigns(crmContacts, communicationList);
      Map<String, Set<String>> activeTags = new HashMap<>();
      for (CrmContact crmContact : crmContacts) {
        Set<String> tagsCleaned = getContactTagsCleaned(crmContact, crmContactCampaignNames.get(crmContact.id), config, communicationList);
        activeTags.put(crmContact.email, tagsCleaned);
      }

      // Execute batch upsert
      executeBatchUpsert(contactsToUpsert, contactsCustomFields, activeTags, config, communicationList);

      // Archive contacts that should be unsubscribed
      Set<String> emailsToArchive = contactsToArchive.stream()
          .map(crmContact -> crmContact.email.toLowerCase(Locale.ROOT))
          .collect(Collectors.toSet());
      emailsToArchive.retainAll(existingEmails);
      executeBatchArchive(emailsToArchive, communicationList.id, config);

    } catch (Exception e) {
      env.logJobWarn("{} syncContacts failed", name(), e);
    }
  }

  @Override
  public void upsertContact(String contactId) throws Exception {
    CrmService crmService = env.primaryCrmService();
    List<EnvironmentConfig.CommunicationPlatform> configs = getPlatformConfigs();

    for (EnvironmentConfig.CommunicationPlatform config : configs) {
      for (EnvironmentConfig.CommunicationList communicationList : config.lists) {
        // platform-specific preparation (e.g., cache clearing) once per communication list
        prepareBatchProcessing(config, communicationList);

        Optional<CrmContact> _crmContact = crmService.getFilteredContactById(contactId, communicationList.crmFilter);
        if (_crmContact.isPresent() && !Strings.isNullOrEmpty(_crmContact.get().email)) {
          CrmContact crmContact = _crmContact.get();

          // Transactional is always subscribed
          if (communicationList.type != EnvironmentConfig.CommunicationListType.TRANSACTIONAL && !crmContact.canReceiveEmail()) {
            return;
          }

          try {
            Map<String, Object> customFields = buildPlatformCustomFields(crmContact, config, communicationList);
            Map<String, List<String>> crmContactCampaignNames = env.primaryCrmService().getContactsCampaigns(List.of(crmContact), communicationList);
            Set<String> tags = getContactTagsCleaned(crmContact, crmContactCampaignNames.get(crmContact.id), config, communicationList);

            executeBatchUpsert(List.of(crmContact), Map.of(crmContact.email, customFields), Map.of(crmContact.email, tags), config, communicationList);
          } catch (Exception e) {
            env.logJobWarn("{} upsertContact failed", name(), e);
          }
        }
      }
    }
  }

  @Override
  public void massArchive() throws Exception {
    List<EnvironmentConfig.CommunicationPlatform> configs = getPlatformConfigs();
    for (EnvironmentConfig.CommunicationPlatform config : configs) {
      for (EnvironmentConfig.CommunicationList communicationList : config.lists) {
        Set<String> emailsToArchive = getExistingContactEmails(config, communicationList.id);

        // Remove CRM contacts that should remain
        PagedResults<CrmContact> contactPagedResults = env.primaryCrmService().getEmailContacts(null, communicationList);
        for (PagedResults.ResultSet<CrmContact> resultSet : contactPagedResults.getResultSets()) {
          do {
            for (CrmContact crmContact : resultSet.getRecords()) {
              if (crmContact.canReceiveEmail()) {
                emailsToArchive.remove(crmContact.email.toLowerCase(Locale.ROOT));
              }
            }
            if (!Strings.isNullOrEmpty(resultSet.getNextPageToken())) {
              resultSet = env.primaryCrmService().queryMoreContacts(resultSet.getNextPageToken());
            } else {
              resultSet = null;
            }
          } while (resultSet != null);
        }

        // Remove CRM accounts that should remain
        PagedResults<CrmAccount> accountPagedResults = env.primaryCrmService().getEmailAccounts(null, communicationList);
        for (PagedResults.ResultSet<CrmAccount> resultSet : accountPagedResults.getResultSets()) {
          do {
            for (CrmAccount crmAccount : resultSet.getRecords()) {
              if (crmAccount.canReceiveEmail()) {
                emailsToArchive.remove(crmAccount.email.toLowerCase(Locale.ROOT));
              }
            }
            if (!Strings.isNullOrEmpty(resultSet.getNextPageToken())) {
              resultSet = env.primaryCrmService().queryMoreAccounts(resultSet.getNextPageToken());
            } else {
              resultSet = null;
            }
          } while (resultSet != null);
        }

        env.logJobInfo("massArchiving {} contacts in {}: {}", emailsToArchive.size(), name(), String.join(", ", emailsToArchive));
        executeBatchArchive(emailsToArchive, communicationList.id, config);
      }
    }
  }

  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    List<EnvironmentConfig.CommunicationPlatform> configs = getPlatformConfigs();
    for (EnvironmentConfig.CommunicationPlatform config : configs) {
      for (EnvironmentConfig.CommunicationList communicationList : config.lists) {
        List<String> unsubscribedEmails = getUnsubscribedEmails(communicationList.id, lastSync, config);
        syncUnsubscribed(unsubscribedEmails);

        List<String> bouncedEmails = getBouncedEmails(communicationList.id, lastSync, config);
        syncCleaned(bouncedEmails);
      }
    }
  }

  protected final void syncUnsubscribed(List<String> unsubscribedEmails) throws Exception {
    updateContactsByEmails(unsubscribedEmails, c -> c.emailOptOut = true);
    updateAccountsByEmails(unsubscribedEmails, a -> a.emailOptOut = true);
  }

  protected final void syncCleaned(List<String> cleanedEmails) throws Exception {
    updateContactsByEmails(cleanedEmails, c -> c.emailBounced = true);
    updateAccountsByEmails(cleanedEmails, a -> a.emailBounced = true);
  }

  protected final void updateContactsByEmails(List<String> emails, Consumer<CrmContact> contactConsumer) throws Exception {
    CrmService crmService = env.primaryCrmService();
    List<CrmContact> contacts = crmService.getContactsByEmails(emails);
    int count = 0;
    int total = contacts.size();
    for (CrmContact crmContact : contacts) {
      env.logJobInfo("updating unsubscribed contact in CRM: {} ({} of {})", crmContact.email, count++, total);
      CrmContact updateContact = new CrmContact();
      updateContact.id = crmContact.id;
      contactConsumer.accept(updateContact);
      crmService.batchUpdateContact(updateContact);
    }
    crmService.batchFlush();
  }

  protected final void updateAccountsByEmails(List<String> emails, Consumer<CrmAccount> accountConsumer) throws Exception {
    CrmService crmService = env.primaryCrmService();
    List<CrmAccount> accounts = crmService.getAccountsByEmails(emails);
    int count = 0;
    int total = accounts.size();
    for (CrmAccount account : accounts) {
      env.logJobInfo("updating unsubscribed account in CRM: {} ({} of {})", account.email, count++, total);
      CrmAccount updateAccount = new CrmAccount();
      updateAccount.id = account.id;
      accountConsumer.accept(updateAccount);
      crmService.batchUpdateAccount(updateAccount);
    }
    crmService.batchFlush();
  }

  protected final CrmContact asCrmContact(CrmAccount crmAccount) {
    CrmContact crmContact = new CrmContact();
    crmContact.account = crmAccount;
    crmContact.crmRawObject = crmAccount.crmRawObject;
    crmContact.email = crmAccount.email;
    crmContact.emailBounced = crmAccount.emailBounced;
    crmContact.emailOptIn = crmAccount.emailOptIn;
    crmContact.emailOptOut = crmAccount.emailOptOut;
    crmContact.firstName = crmAccount.name;
    crmContact.mailingAddress = crmAccount.mailingAddress;
    if (crmContact.mailingAddress == null || Strings.isNullOrEmpty(crmContact.mailingAddress.street)) {
      crmContact.mailingAddress = crmAccount.billingAddress;
    }
    return crmContact;
  }

  // platform-specific config
  protected abstract List<EnvironmentConfig.CommunicationPlatform> getPlatformConfigs();

  // platform-specific operations
  protected abstract Set<String> getExistingContactEmails(EnvironmentConfig.CommunicationPlatform config, String listId);
  protected abstract void executeBatchUpsert(List<CrmContact> contacts,
      Map<String, Map<String, Object>> customFields, Map<String, Set<String>> tags,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception;
  protected abstract void executeBatchArchive(Set<String> emails, String listId,
      EnvironmentConfig.CommunicationPlatform config) throws Exception;
  protected abstract List<String> getUnsubscribedEmails(String listId, Calendar lastSync,
      EnvironmentConfig.CommunicationPlatform config) throws Exception;
  protected abstract List<String> getBouncedEmails(String listId, Calendar lastSync,
      EnvironmentConfig.CommunicationPlatform config) throws Exception;
  protected abstract Map<String, Object> buildPlatformCustomFields(CrmContact crmContact,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception;

  // platform-specific preparation hook called once per communication list (e.g., cache clearing)
  protected void prepareBatchProcessing(EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) {
    // default implementation does nothing - subclasses can override if needed
  }
}
