/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.client.ConstantContactClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import org.apache.commons.collections.CollectionUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ConstantContactCommunicationService extends AbstractCommunicationService {

  protected final Map<String, String> fieldNameToId = new HashMap<>();
  protected final Map<String, String> tagIdToName = new HashMap<>();
  protected final Map<String, String> tagNameToId = new HashMap<>();

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
  protected ExistingContacts getExistingContacts(EnvironmentConfig.CommunicationPlatform config,
      EnvironmentConfig.CommunicationList list) {
    ConstantContactClient constantContactClient = env.constantContactClient(config);
    ExistingContacts existingContacts = new ExistingContacts();

    try {
      List<ConstantContactClient.Contact> contacts = constantContactClient.getListMembers(list.id);
      Map<String, String> tagIdToNameMap = tagIdToName(constantContactClient);

      for (ConstantContactClient.Contact contact : contacts) {
        String email = contact.emailAddress.emailAddress.toLowerCase(Locale.ROOT);
        existingContacts.emailsToIds.put(email, contact.id);
        Set<String> tags = contact.tagIds.stream()
            .map(tagIdToNameMap::get)
            .collect(Collectors.toSet());
        existingContacts.emailsToTags.put(email, tags);
      }
    } catch (Exception e) {
      env.logJobError("Failed to get existing contacts from Constant Contact: {}", e.getMessage());
    }

    return existingContacts;
  }

  @Override
  protected void executeBatchUpsert(List<CrmContact> crmContacts,
      Map<String, Map<String, Object>> customFields, Map<String, Set<String>> tagNames,
      ExistingContacts _existingContacts, EnvironmentConfig.CommunicationPlatform config,
      EnvironmentConfig.CommunicationList list) throws Exception {
    ConstantContactClient constantContactClient = env.constantContactClient(config);

    // Run the actual contact upserts
    List<ConstantContactClient.ContactImport> contacts = toContactImports(config, crmContacts, customFields);
    String upsertBatchId = constantContactClient.upsertContactsBatch(list.id, contacts);
    constantContactClient.runBatchOperations(config, upsertBatchId, 0);

    // fetch all contacts again to get any new IDs -- CC unfortunately requires IDs and not emails for tag operations
    // TODO: does the API have a way to limit getListMembers with a list of emails? otherwise, this is the same problem
    //  as before where we're fetching the whole audience for every single batch of CRM contacts -- we're effectively
    //  ignoring the passed-in _existingContacts entirely
    ExistingContacts existingContacts = getExistingContacts(config, list);

    // update all contacts' tags
    List<ConstantContactClient.TaggedContact> taggedContacts = crmContacts.stream()
        .map(crmContact -> {
          String email = crmContact.email.toLowerCase(Locale.ROOT);
          Set<String> existingTags = existingContacts.emailsToTags.getOrDefault(email, new HashSet<>());
          Set<String> activeTags = tagNames.get(crmContact.email);
          // TODO: Verify the need for this. It appears that if a contact was previously unsubscribed in CC, the upsert
          //  call does NOT add them to the list. Which then means they don't show up in existingContacts, since they're
          //  not a member of the list. And so they won't exist in this map.
          return new ConstantContactClient.TaggedContact(crmContact.email, activeTags, new HashSet<>(existingTags));
        })
        .collect(Collectors.toList());
    updateTagsBatch(list.id, taggedContacts, existingContacts, constantContactClient, config);
  }

  protected Map<String, String> tagIdToName(ConstantContactClient constantContactClient) {
    if (tagIdToName.isEmpty()) {
      tagIdToName.putAll(constantContactClient.getAllTags().stream().collect(Collectors.toMap(tag -> tag.id, tag -> tag.name)));
    }

    return tagIdToName;
  }

  protected Map<String, String> tagNameToId(ConstantContactClient constantContactClient) {
    if (tagNameToId.isEmpty()) {
      tagNameToId.putAll(constantContactClient.getAllTags().stream().collect(Collectors.toMap(tag -> tag.name, tag -> tag.id)));
    }

    return tagNameToId;
  }

  @Override
  protected void executeUpsert(CrmContact contact, Map<String, Object> customFields, Set<String> tags,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    // TODO
  }

  @Override
  protected void executeBatchArchive(Set<String> emails,
      ExistingContacts existingContacts, EnvironmentConfig.CommunicationPlatform config,
      EnvironmentConfig.CommunicationList list) throws Exception {
    ConstantContactClient constantContactClient = env.constantContactClient(config);
    List<String> contactIds = emails.stream().map(existingContacts.emailsToIds::get).toList();
    String archiveBatchId = constantContactClient.archiveContactsBatch(contactIds);
    constantContactClient.runBatchOperations(config, archiveBatchId, 0);
  }

  @Override
  protected Set<String> getUnsubscribedEmails(Calendar lastSync,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    ConstantContactClient constantContactClient = env.constantContactClient(config);
    List<ConstantContactClient.Contact> contacts = constantContactClient.getListMembers(list.id, "unsubscribed", lastSync);
    return getEmails(contacts);
  }

  @Override
  protected Set<String> getBouncedEmails(Calendar lastSync,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    return Collections.emptySet();
  }

  @Override
  protected Map<String, Object> buildPlatformCustomFields(CrmContact crmContact,
      EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) throws Exception {
    ConstantContactClient constantContactClient = env.constantContactClient(config);

    Map<String, Object> customFieldMap = new HashMap<>();

    List<CustomField> contactCustomFields = buildContactCustomFields(crmContact, config, list);
    if (fieldNameToId.isEmpty()) {
      List<ConstantContactClient.CustomField> customFields = constantContactClient.getAllCustomFields();
      for (ConstantContactClient.CustomField customField : customFields) {
        fieldNameToId.put(customField.name, customField.id);
      }
    }
    for (CustomField contactCustomField : contactCustomFields) {
      if (contactCustomField.value == null) {
        continue;
      }

      if (!fieldNameToId.containsKey(contactCustomField.name)) {
        String type = switch (contactCustomField.type) {
          case DATE -> "date";
          default -> "string";
        };
        ConstantContactClient.CustomField customField = constantContactClient.createCustomField(contactCustomField.name, type);
        fieldNameToId.put(customField.name, customField.id);
      }

      Object value = contactCustomField.value;
      if (contactCustomField.type == CustomFieldType.DATE) {
        Calendar c = (Calendar) value;
        value = new SimpleDateFormat("MM/dd/yyyy").format(c.getTime());
      }

      // TODO: technically we don't even need the IDs from fieldNameToId, since all we use custom fields for are
      //  bulk imports which needs the names only. fieldNameToId could be reduced to a list of fieldNames.
      customFieldMap.put(contactCustomField.name, value);
    }

    return customFieldMap;
  }

  @Override
  protected void prepareBatchProcessing(EnvironmentConfig.CommunicationPlatform config, EnvironmentConfig.CommunicationList list) {
    // Clear the cache since fields differ between audiences - done once per communication list
    fieldNameToId.clear();
    tagIdToName.clear();
    tagNameToId.clear();
  }

  protected Set<String> getEmails(List<ConstantContactClient.Contact> contacts) {
    return contacts.stream().map(memberInfo -> memberInfo.emailAddress.emailAddress.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
  }

  // CC's API forces us provide a list of contact ids paired with a list of tag ids to either add or remove to that
  // entire set of contacts, as opposed to a batch set of contacts and all the tags that should be added/removed from
  // them individually. So we break down the set into the tags that need removed/added, paired with the total list
  // of contacts for each of them.
  // TODO: Some of this is generic and duplicates MC. Pull what we can to super?
  protected void updateTagsBatch(String listId, List<ConstantContactClient.TaggedContact> taggedContacts,
      ExistingContacts existingContacts, ConstantContactClient constantContactClient,
      EnvironmentConfig.CommunicationPlatform config) {

    // Unlike MC, CC does not auto create tags. We must explicitly handle them here.
    taggedContacts.stream().flatMap(c -> c.activeTagNames().stream()).distinct().forEach(tagName -> {
      if (!tagNameToId(constantContactClient).containsKey(tagName)) {
        ConstantContactClient.Tag tag = constantContactClient.createTag(tagName);
        tagIdToName.put(tag.id, tagName);
        tagNameToId.put(tag.name, tag.id);
      }
    });

    Map<String, List<String>> tagIdsToAdd = new HashMap<>();
    Map<String, List<String>> tagIdsToRemove = new HashMap<>();

    taggedContacts.stream()
        .filter(taggedContact ->
            // skip any contacts that don't actually exist in CC (expected -- previously unsubscribed contact can't be
            // added to a list, etc)
            existingContacts.emailsToIds.containsKey(taggedContact.email())
                && (CollectionUtils.isNotEmpty(taggedContact.activeTagNames()) || CollectionUtils.isNotEmpty(taggedContact.inactiveTagNames()))
        )
        .forEach(taggedContact -> {
          // filter out tags we want to keep from the tags-to-remove
          taggedContact.inactiveTagNames().removeAll(taggedContact.activeTagNames());
          // filter down the tags-to-remove to only the ones we actually have control over
          taggedContact.inactiveTagNames().removeIf(t -> {
            List<String> controlledTags = Stream.concat(
                config.defaultControlledTags.stream(), config.customControlledTags.stream()
            ).toList();
            boolean controlled = false;
            for (String controlledTag : controlledTags) {
              if (t.contains(controlledTag)) {
                controlled = true;
                break;
              }
            }
            // backwards -- we're taking the whole list of tags we could potentially remove, seeing if any of them
            // match our controlledList, and removing any that are NOT controlled by Nucleus
            return !controlled;
          });

          taggedContact.activeTagNames().forEach(tagName -> {
            String tagId = tagNameToId.get(tagName);
            if (!tagIdsToAdd.containsKey(tagId)) {
              tagIdsToAdd.put(tagId, new ArrayList<>());
            }
            tagIdsToAdd.get(tagId).add(existingContacts.emailsToIds.get(taggedContact.email()));
          });

          taggedContact.inactiveTagNames().forEach(tagName -> {
            String tagId = tagNameToId.get(tagName);
            if (!tagIdsToRemove.containsKey(tagId)) {
              tagIdsToRemove.put(tagId, new ArrayList<>());
            }
            tagIdsToRemove.get(tagId).add(existingContacts.emailsToIds.get(taggedContact.email()));
          });
        });

    tagIdsToAdd.forEach((tagId, contactIds) -> {
      String batchId = constantContactClient.addContactTagsBatch(contactIds, tagId);
      constantContactClient.runBatchOperations(config, batchId, 0);
    });

    tagIdsToRemove.forEach((tagId, contactIds) -> {
      String batchId = constantContactClient.removeContactTagsBatch(contactIds, tagId);
      constantContactClient.runBatchOperations(config, batchId, 0);
    });
  }

  protected List<ConstantContactClient.ContactImport> toContactImports(EnvironmentConfig.CommunicationPlatform config,
      List<CrmContact> crmContacts, Map<String, Map<String, Object>> customFieldsMap) {
    return crmContacts.stream()
        .map(crmContact -> toContactImport(config, crmContact, customFieldsMap.get(crmContact.email)))
        .collect(Collectors.toList());
  }

  protected ConstantContactClient.ContactImport toContactImport(EnvironmentConfig.CommunicationPlatform config,
      CrmContact crmContact, Map<String, Object> customFields) {
    if (crmContact == null) {
      return null;
    }

    ConstantContactClient.ContactImport contact = new ConstantContactClient.ContactImport();
    contact.emailAddress = crmContact.email;
    contact.firstname = crmContact.firstName;
    contact.lastname = crmContact.lastName;
//    contact.smsNumber = crmContact.phoneNumberForSMS();

    // On imports, custom fields are in the root of the contact payload, prefixed with cf.
    //  Ex: "cf:custom_field_name": "the value"
    customFields.forEach((key, value) -> contact.customFields.put("cf:" + key, value));
    contact.customFields.putAll(customFields);

    return contact;
  }
}
