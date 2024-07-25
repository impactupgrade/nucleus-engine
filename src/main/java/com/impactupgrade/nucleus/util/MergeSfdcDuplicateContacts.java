/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.sforce.soap.partner.MergeRequest;
import com.sforce.soap.partner.MergeResult;
import com.sforce.soap.partner.sobject.SObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MergeSfdcDuplicateContacts {

  private static final Logger log = LogManager.getLogger(MergeSfdcDuplicateContacts.class);

  public static void main(String[] args) throws Exception {
    Environment env = new Environment() {
      @Override
      public EnvironmentConfig getConfig() {
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.crmPrimary = "salesforce";
        envConfig.salesforce.sandbox = false;
        envConfig.salesforce.url = "TODO";
        envConfig.salesforce.username = "TODO";
        envConfig.salesforce.password = "TODO";
        envConfig.salesforce.npsp = true;
        envConfig.salesforce.enhancedRecurringDonations = true;
        return envConfig;
      }
    };
    mergeDuplicates(env);
  }

  // TODO: update with batch ops
  // TODO: Assuming it's a contact...
  // TODO: genericize/expand into nucleus-engine
  // TODO: This could be way more intelligent. Get all the contacts in each set, then go through all fields this
  //  org cares about (would need to be a full, configurable list), and set the values on the primary record
  //  if only one of the duplicates had a value for that particular field (or all of them had the same value)?
  private static void mergeDuplicates(Environment env) throws Exception {
    CrmService sfdcCrmService = env.crmService("salesforce");
    SfdcClient sfdcClient = env.sfdcClient();

    List<SObject> duplicateRecordSets = sfdcClient.queryListAutoPaged("SELECT Id FROM DuplicateRecordSet ORDER BY CreatedDate DESC");
    int total = duplicateRecordSets.size();
    int count = 0;
    for (SObject duplicateRecordSet : duplicateRecordSets) {
      count++;
      log.info("DuplicateRecordSet {} of {}", count, total);

      List<SObject> duplicateRecordItems = sfdcClient.queryListAutoPaged("SELECT RecordId FROM DuplicateRecordItem WHERE DuplicateRecordSetId='" + duplicateRecordSet.getId() + "'");

      if (duplicateRecordItems.size() <= 1) {
        log.info("DuplicateRecordSet did not contain multiple items; deleting the DuplicateRecordSet itself...");
        sfdcClient.delete(duplicateRecordSet);
        continue;
      }

      SObject primary = null;
      List<SObject> secondary = new ArrayList<>();

      Set<String> names = new HashSet<>();
      Set<String> emails = new HashSet<>();
      Set<String> mobilePhones = new HashSet<>();
      Set<String> homePhones = new HashSet<>();
      Set<String> workPhones = new HashSet<>();
      Set<String> addresses = new HashSet<>();

      for (SObject duplicateRecordItem : duplicateRecordItems) {
        CrmContact contact = sfdcCrmService.getContactById((String) duplicateRecordItem.getField("RecordId")).get();

        // Note: Do not consider which Contacts have related Opportunities, as merge will move those automatically.
        if (!Strings.isNullOrEmpty(contact.mailingAddress.street)
            || !Strings.isNullOrEmpty(contact.account.billingAddress.street)
            || !Strings.isNullOrEmpty(contact.account.mailingAddress.street)) {
          if (primary == null) {
            primary = (SObject) contact.crmRawObject;
          } else {
            secondary.add((SObject) contact.crmRawObject);
          }

          if (!Strings.isNullOrEmpty(contact.mailingAddress.street)) {
            addresses.add(Utils.normalizeStreet(contact.mailingAddress.street));
          }
          if (!Strings.isNullOrEmpty(contact.account.billingAddress.street)) {
            addresses.add(Utils.normalizeStreet(contact.account.billingAddress.street));
          }
          if (!Strings.isNullOrEmpty(contact.account.mailingAddress.street)) {
            addresses.add(Utils.normalizeStreet(contact.account.mailingAddress.street));
          }
        } else {
          secondary.add((SObject) contact.crmRawObject);
        }

        names.add(contact.firstName + " " + contact.lastName);
        if (!Strings.isNullOrEmpty(contact.email)) {
          emails.add(contact.email);
        }
        if (!Strings.isNullOrEmpty(contact.mobilePhone)) {
          String pn = contact.mobilePhone.replaceAll("[\\D]", "");
          if (pn.length() == 11 && pn.startsWith("1")) {
            pn = pn.substring(1);
          }
          mobilePhones.add(pn);
        }
        if (!Strings.isNullOrEmpty(contact.homePhone)) {
          String pn = contact.homePhone.replaceAll("[\\D]", "");
          if (pn.length() == 11 && pn.startsWith("1")) {
            pn = pn.substring(1);
          }
          homePhones.add(pn);
        }
        if (!Strings.isNullOrEmpty(contact.workPhone)) {
          String pn = contact.workPhone.replaceAll("[\\D]", "");
          if (pn.length() == 11 && pn.startsWith("1")) {
            pn = pn.substring(1);
          }
          workPhones.add(pn);
        }
      }

      if (names.size() > 1) {
        log.info("DuplicateRecordSet contained multiple names ({}}); deleting the DuplicateRecordSet itself...",
            String.join(", ", names));
        sfdcClient.delete(duplicateRecordSet);
        continue;
      }

      if (addresses.size() > 1) {
        log.info("DuplicateRecordSet contained multiple addresses; skipping merge...");
        continue;
      }

      if (emails.size() > 1) {
        log.info("DuplicateRecordSet contained multiple emails; skipping merge...");
        continue;
      }
      if (mobilePhones.size() > 1) {
        log.info("DuplicateRecordSet contained multiple mobilePhones; skipping merge...");
        continue;
      }
      if (homePhones.size() > 1) {
        log.info("DuplicateRecordSet contained multiple homePhones; skipping merge...");
        continue;
      }
      if (workPhones.size() > 1) {
        log.info("DuplicateRecordSet contained multiple workPhones; skipping merge...");
        continue;
      }

      secondary.sort((s1, s2) -> {
        Calendar createdDate1 = Utils.getCalendarFromDateTimeString((String) s1.getField("CreatedDate"));
        Calendar createdDate2 = Utils.getCalendarFromDateTimeString((String) s2.getField("CreatedDate"));
        return createdDate1.compareTo(createdDate2);
      });

      String email = emails.stream().findFirst().orElse(null);
      String mobilePhone = mobilePhones.stream().findFirst().orElse(null);
      String homePhone = homePhones.stream().findFirst().orElse(null);
      String workPhone = workPhones.stream().findFirst().orElse(null);

      // If all we have is secondaries, simply pick the oldest to be the primary.
      if (primary == null && !secondary.isEmpty()) {
        primary = secondary.remove(0);
      }

      if (primary == null) {
        log.info("DuplicateRecordSet contained no primary; skipping merge...");
        continue;
      }

      if (secondary.isEmpty()) {
        log.info("DuplicateRecordSet contained no secondaries; skipping merge...");
        continue;
      }

      boolean success = true;
      // Simply do one at a time since there's a max of 3 per request.
      for (SObject secondaryItem : secondary) {
        MergeRequest mergeRequest = new MergeRequest();

        SObject masterRecord = new SObject("Contact");
        masterRecord.setId(primary.getId());
        masterRecord.setField("Email", email);
        masterRecord.setField("npe01__WorkEmail__c", secondaryItem.getField("npe01__WorkEmail__c"));
        masterRecord.setField("MobilePhone", mobilePhone);
        masterRecord.setField("HomePhone", homePhone);
        masterRecord.setField("npe01__WorkPhone__c", workPhone);
        masterRecord.setField("Title", secondaryItem.getField("Title"));
        masterRecord.setField("Industry", secondaryItem.getField("Industry"));
        mergeRequest.setMasterRecord(masterRecord);

        mergeRequest.setRecordToMergeIds(new String[] { secondaryItem.getId() });

        log.info("merging {}: {} into {}", primary.getField("Name"), secondaryItem.getId(), masterRecord.getId());

        MergeResult mergeResult = sfdcClient.merge(mergeRequest);
        if (!mergeResult.isSuccess()) {
          success = false;
        }
      }

      if (success) {
        sfdcClient.delete(duplicateRecordSet);
      }
    }
  }
}
