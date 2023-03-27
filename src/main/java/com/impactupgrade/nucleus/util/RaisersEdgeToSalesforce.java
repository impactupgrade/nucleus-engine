/*
 * Copyright (c) 2022 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.client.SfdcMetadataClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.sforce.soap.partner.sobject.SObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.util.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// TODO: Temporary utility to eventually be worked into a reusable strategy. Square up migrations, bulk imports,
//  and the generic CRM model.
public class RaisersEdgeToSalesforce {

  private static final Logger log = LogManager.getLogger(RaisersEdgeToSalesforce.class);

  // TODO: pull to config
  private static String HOUSEHOLD_RECORD_TYPE_ID = "0128V000001h1ZtQAI";
  private static String ORGANIZATION_RECORD_TYPE_ID = "0128V000001h1ZuQAI";
  private static String DONATION_RECORD_TYPE_ID = "0128V000001h1ZvQAI";

  public static void main(String[] args) throws Exception {
    Environment env = new Environment() {
      @Override
      public EnvironmentConfig getConfig() {
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.crmPrimary = "salesforce";
        envConfig.salesforce.sandbox = false;
        envConfig.salesforce.url = "concordialutheranhs.my.salesforce.com";
        envConfig.salesforce.username = "team+clhs@impactupgrade.com";
        envConfig.salesforce.password = "pqp.gnj1xcd8DFC2mgfpwl7cq1UOBQZYOZKEppFDTQ4";
        envConfig.salesforce.enhancedRecurringDonations = true;
        return envConfig;
      }
    };

//    provisionFields(env);
//    deleteFields(env);
    migrate(env);
  }

  private static void provisionFields(Environment env) throws Exception {
    SfdcMetadataClient sfdcMetadataClient = new SfdcMetadataClient(env);

//    sfdcMetadataClient.createCustomField("Account", "BB_Address_Type__c", "BB Address Type", FieldType.Text, 100);
  }

  private static void deleteFields(Environment env) throws Exception {
    SfdcMetadataClient sfdcMetadataClient = new SfdcMetadataClient(env);

//    sfdcMetadataClient.deleteCustomFields("Contact",
//        "BB_Spouse_Business_Phone__c", "BB_Spouse_Phone__c", "BB_PhoneFinder__c", "BB_CnPh_1_02_Comments__c", "BB_CnPh_1_02_Do_Not_Contact__c", "BB_CnPh_1_02_Inactive__c", "BB_CnPh_1_02_Is_Primary__c", "BB_CnPh_1_02_Phone_number__c", "BB_CnPh_1_02_Phone_type__c", "BB_CnPh_1_03_Comments__c", "BB_CnPh_1_03_Do_Not_Contact__c", "BB_CnPh_1_03_Inactive__c", "BB_CnPh_1_03_Is_Primary__c", "BB_CnPh_1_03_Phone_number__c", "BB_CnPh_1_03_Phone_type__c"
//    );
  }

  private static void migrate(Environment env) throws Exception {
    // The sheets are so huge that the Apache framework we're using to read XLSX thinks it's malicious...
    IOUtils.setByteArrayMaxOverride(Integer.MAX_VALUE);

    // Mostly basing this on Bulk Upsert, but cheating in places and going straight to SFDC.
    SfdcClient sfdcClient = new SfdcClient(env);

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // CONSTITUENTS
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

//    File file = new File("/home/brmeyer/Downloads/Constituent+Spouse-v6-head-of-household.xlsx");
//    InputStream inputStream = new FileInputStream(file);
//    List<Map<String, String>> rows = Utils.getExcelData(inputStream);
//
//    List<Map<String, String>> primaryRows = new ArrayList<>();
//    List<Map<String, String>> secondaryRows = new ArrayList<>();
//
//    // TODO: There's unfortunately a wide, inconsistent mix of ways that households can be defined in RE data.
//    //  Relationships between spouses will usually identify one as the head-of-household, so we first use those heads
//    //  as the primary contacts of accounts. But then students/children do *not* have a parent relationship that's
//    //  defined as HoH, so we can't globally rely on that to give away household groupings. We instead opt to use
//    //  mailing addresses, since RE at least makes these consistent (at least for CLHS).
//    List<String> headOfHouseholdIds = new ArrayList<>();
//    Map<String, String> spouseIdsToHouseholdIds = new HashMap<>();
//    Map<String, String> addressesToHouseholdIds = new HashMap<>();
//
//    // There are many spouse combos sharing a single email address. We'll include the email for only the first one we
//    // see (likely the head of household), otherwise the search-by-email step of Bulk Upsert overwrites records.
//    List<String> seenEmails = new ArrayList<>();
//
//    // Make one pass to discover all heads of households. This is unfortunately not at the Constituent level, but is
//    // instead buried in the lists of relationships :(
//    for (Map<String, String> row : rows) {
//      // households only
//      if (!Strings.isNullOrEmpty(row.get("CnBio_Org_Name"))) {
//        continue;
//      }
//
//      String id = row.get("CnBio_ID");
//
//      for (int i = 1; i <= 25; i++) {
//        String prefix = "CnRelInd_1_" + new DecimalFormat("00").format(i) + "_";
//        if ("Yes".equalsIgnoreCase(row.get(prefix + "Is_Headofhousehold"))) {
//          // the relationship is the head of household, not this constituent itself
//          String headOfHouseholdId = row.get(prefix + "ID");
//          headOfHouseholdIds.add(headOfHouseholdId);
//          // since we have the direct relationship, might as well save it off and use it later
//          spouseIdsToHouseholdIds.put(id, headOfHouseholdId);
//          break;
//        }
//      }
//    }
//
//    // Make another pass to discover the heads of households' addresses.
//    for (Map<String, String> row : rows) {
//      String id = row.get("CnBio_ID");
//      if (headOfHouseholdIds.contains(id)) {
//        Set<String> cnAdrPrfAddr = new LinkedHashSet<>();
//        for (int i = 1; i <= 4; i++) {
//          String field = "CnAdrPrf_Addrline" + i;
//          if (!Strings.isNullOrEmpty(row.get(field))) {
//            cnAdrPrfAddr.add(row.get(field));
//          }
//        }
//        String street = String.join(", ", cnAdrPrfAddr);
//        String zip = row.get("CnAdrPrf_ZIP");
//        String address = street + " " + zip;
//        address = address.trim();
//        if (!Strings.isNullOrEmpty(address) && !addressesToHouseholdIds.containsKey(address)) {
//          addressesToHouseholdIds.put(address, id);
//        }
//      }
//    }
//
//    // TODO: If any addresses do NOT have a HoH, should we make another pass and specifically look for parents,
//    //  guardians, or grandparents first? Prevent kids from becomings heads of households?
//
//    // The next pass inserts all constituents that were discovered to be heads of households, ensuring that they're
//    // the primary contact on households as they're created.
//    for (Map<String, String> row : rows) {
//      String id = row.get("CnBio_ID");
//      if (headOfHouseholdIds.contains(id)) {
//        // We use the head of household's ID as the account's extref key.
//        String householdId = id;
//        migrate(row, householdId, primaryRows, secondaryRows, seenEmails);
//      }
//    }
//
//    // TODO: HACK! We shouldn't technically need to run these as separate upsert batches. However, since batch
//    //  inserts don't have a deterministic order, the spouses sometimes get inserted first, which causes NPSP to
//    //  automatically set them as the primary contact on the household. I can't find a way to force that, without
//    //  complicated logic to update the account after the fact.
//    // TODO: Added benefit of this approach: if the secondary contact already exists (from one of the other imports,
//    //  FACTS, HubSpot, etc), we're ensuring that BB's primary contact is getting a household with the BB ID set.
//    List<CrmImportEvent> importEvents = CrmImportEvent.fromGeneric(primaryRows);
//    env.primaryCrmService().processBulkImport(importEvents);
//    importEvents = CrmImportEvent.fromGeneric(secondaryRows);
//    env.primaryCrmService().processBulkImport(importEvents);
//
//    primaryRows.clear();
//    secondaryRows.clear();
//
//    // Then do another pass to insert everyone else. Doing this after the above ensure households already exist.
//    for (Map<String, String> row : rows) {
//      String id = row.get("CnBio_ID");
//      if (!headOfHouseholdIds.contains(id)) {
//        Set<String> cnAdrPrfAddr = new LinkedHashSet<>();
//        for (int i = 1; i <= 4; i++) {
//          String field = "CnAdrPrf_Addrline" + i;
//          if (!Strings.isNullOrEmpty(row.get(field))) {
//            cnAdrPrfAddr.add(row.get(field));
//          }
//        }
//        String street = String.join(", ", cnAdrPrfAddr);
//        String zip = row.get("CnAdrPrf_ZIP");
//        String address = street + " " + zip;
//        address = address.trim();
//
//        String householdId;
//        if (spouseIdsToHouseholdIds.containsKey(id)) {
//          householdId = spouseIdsToHouseholdIds.get(id);
//        } else if (!Strings.isNullOrEmpty(address) && addressesToHouseholdIds.containsKey(address)) {
//          householdId = addressesToHouseholdIds.get(address);
//        } else {
//          // business or single-contact household (IE, no defined HoH) -- use their ID for the household extref
//          householdId = id;
//        }
//
//        migrate(row, householdId, primaryRows, secondaryRows, seenEmails);
//      }
//    }
//
//    // TODO: HACK! We shouldn't technically need to run these as separate upsert batches. However, since batch
//    //  inserts don't have a deterministic order, the spouses sometimes get inserted first, which causes NPSP to
//    //  automatically set them as the primary contact on the household. I can't find a way to force that, without
//    //  complicated logic to update the account after the fact.
//    // TODO: Added benefit of this approach: if the secondary contact already exists (from one of the other imports,
//    //  FACTS, HubSpot, etc), we're ensuring that BB's primary contact is getting a household with the BB ID set.
//    importEvents = CrmImportEvent.fromGeneric(primaryRows);
//    env.primaryCrmService().processBulkImport(importEvents);
//    importEvents = CrmImportEvent.fromGeneric(secondaryRows);
//    env.primaryCrmService().processBulkImport(importEvents);
//
//    primaryRows.clear();
//    secondaryRows.clear();

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // RELATIONSHIPS
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

//    File relsFile = new File("/home/brmeyer/Downloads/Individual-relationships-v3-banks-contacts-solicitors.xlsx");
//    InputStream relsInputStream = new FileInputStream(relsFile);
//    List<Map<String, String>> relRows = Utils.getExcelData(relsInputStream);

    Map<String, SObject> constituentIdToContact = new HashMap<>();
    List<SObject> contacts = sfdcClient.queryListAutoPaged("SELECT Id, AccountId, Blackbaud_Constituent_ID__c FROM Contact WHERE Blackbaud_Constituent_ID__c!=''");
    for (SObject contact : contacts) {
      constituentIdToContact.put((String) contact.getField("Blackbaud_Constituent_ID__c"), contact);
    }

    Map<String, SObject> constituentIdToAccount = new HashMap<>();
    // businesses only
    List<SObject> accounts = sfdcClient.queryListAutoPaged("SELECT Id, Blackbaud_Constituent_ID__c FROM Account WHERE Blackbaud_Constituent_ID__c!='' AND RecordTypeId='" + ORGANIZATION_RECORD_TYPE_ID + "'");
    for (SObject account : accounts) {
      constituentIdToAccount.put((String) account.getField("Blackbaud_Constituent_ID__c"), account);
    }

//    // prevent duplicates
//    List<String> seenRelationships = new ArrayList<>();
//
//    // TODO: Pull all relationships and hold in a map so we can prevent duplicates in the future.
//    for (int i = 0; i < relRows.size(); i++) {
//      log.info("processing relationship row {}", i + 2);
//
//      Map<String, String> relRow = relRows.get(i);
//      String id = relRow.get("IndCnBio_ID");
//
//      for (int j = 1; j <= 15; j++) {
//        String prefix = "IndCnRelInd_1_" + new DecimalFormat("00").format(j) + "_";
//        String relatedId = relRow.get(prefix + "ID");
//        String relationshipType = relRow.get(prefix + "Relation_Code");
////        String relationshipNotes = relRow.get(prefix + "Notes");
//
//        if (constituentIdToContact.get(id) == null || Strings.isNullOrEmpty(relatedId) || constituentIdToContact.get(relatedId) == null) {
//          continue;
//        }
//
//        String from = constituentIdToContact.get(id).getId();
//        String to = constituentIdToContact.get(relatedId).getId();
//
//        if (seenRelationships.contains(from + "::" + to) || seenRelationships.contains(to + "::" + from)) {
//          continue;
//        }
//
//        SObject relationship = new SObject("npe4__Relationship__c");
//        relationship.setField("npe4__Contact__c", from);
//        relationship.setField("npe4__RelatedContact__c", to);
//        // TODO: missing in export
////        relationship.setField("npe4__Description__c", relationshipNotes);
//        relationship.setField("npe4__Status__c", "Current");
//        relationship.setField("npe4__Type__c", relationshipType);
//        sfdcClient.batchInsert(relationship);
//
//        seenRelationships.add(from + "::" + to);
//        seenRelationships.add(to + "::" + from);
//      }
//      for (int j = 1; j <= 15; j++) {
//        String prefix = "IndCnRelOrg_1_" + new DecimalFormat("00").format(j) + "_";
//        String relatedId = relRow.get(prefix + "ID");
//        String relationshipType = relRow.get(prefix + "Relation_Code");
////        String relationshipNotes = relRow.get(prefix + "Notes");
//
//        if (constituentIdToContact.get(id) == null || Strings.isNullOrEmpty(relatedId) || constituentIdToAccount.get(relatedId) == null) {
//          continue;
//        }
//
//        String from = constituentIdToContact.get(id).getId();
//        String to = constituentIdToAccount.get(relatedId).getId();
//
//        if (seenRelationships.contains(from + "::" + to) || seenRelationships.contains(to + "::" + from)) {
//          continue;
//        }
//
//        SObject affiliation = new SObject("npe5__Affiliation__c");
//        affiliation.setField("npe5__Contact__c", from);
//        affiliation.setField("npe5__Organization__c", to);
//        // TODO: missing in export
////        affiliation.setField("npe5__Description__c", relationshipNotes);
//        affiliation.setField("npe5__Status__c", "Current");
//        affiliation.setField("npe5__Role__c", relationshipType);
//        sfdcClient.batchInsert(affiliation);
//
//        seenRelationships.add(from + "::" + to);
//        seenRelationships.add(to + "::" + from);
//      }
//    }
//    sfdcClient.batchFlush();

//    File giftsFile = new File("/home/brmeyer/Downloads/Gifts-with-Installments-v5-soft-credits.xlsx");
//    InputStream giftInputStream = new FileInputStream(giftsFile);
//    List<Map<String, String>> giftRows = Utils.getExcelData(giftInputStream);

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // CAMPAIGNS
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // TODO: The following completely skips Bulk Upsert!

//    Map<String, String> campaignNameToId = sfdcClient.getCampaigns().stream()
//        .collect(Collectors.toMap(c -> (String) c.getField("Name"), c -> c.getId()));
//
//    for (int i = 0; i < giftRows.size(); i++) {
//      log.info("processing campaign row {}", i + 2);
//
//      Map<String, String> giftRow = giftRows.get(i);
//      String campaign = giftRow.get("Gf_Campaign");
//      String appeal = giftRow.get("Gf_Appeal");
//
//      if (!Strings.isNullOrEmpty(campaign) && !campaignNameToId.containsKey(campaign)) {
//        SObject sObject = new SObject("Campaign");
//        sObject.setField("Name", campaign);
//        String id = sfdcClient.insert(sObject).getId();
//        campaignNameToId.put(campaign, id);
//      }
//
//      String name = Strings.isNullOrEmpty(campaign) ? appeal : campaign + ": " + appeal;
//      if (!Strings.isNullOrEmpty(appeal) && !campaignNameToId.containsKey(name)) {
//        SObject sObject = new SObject("Campaign");
//        sObject.setField("Name", name);
//        sObject.setField("ParentId", campaignNameToId.get(campaign));
//        String id = sfdcClient.insert(sObject).getId();
//        campaignNameToId.put(name, id);
//      }
//    }
//    sfdcClient.batchFlush();

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // EVENTS
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

//    Map<String, String> campaignNameToId = sfdcClient.getCampaigns().stream()
//        .collect(Collectors.toMap(c -> (String) c.getField("Name"), c -> c.getId()));
//
//    File eventsFile = new File("/home/brmeyer/Downloads/Events.xlsx");
//    InputStream eventsInputStream = new FileInputStream(eventsFile);
//    List<Map<String, String>> eventRows = Utils.getExcelData(eventsInputStream);
//
//    for (int i = 0; i < eventRows.size(); i++) {
//      log.info("processing event row {}", i + 2);
//
//      Map<String, String> eventRow = eventRows.get(i);
//
//      SObject sObject = new SObject("Campaign");
//      sObject.setField("Name", eventRow.get("Ev_Name"));
//      sObject.setField("Description", eventRow.get("Ev_Description"));
//      if ("Yes".equalsIgnoreCase(eventRow.get("Ev_Inactive"))) {
//        sObject.setField("Status", "Completed");
//      } else {
//        sObject.setField("Status", "In Progress");
//      }
//      if (!Strings.isNullOrEmpty(eventRow.get("Ev_Start_Date"))) {
//        Date d = new SimpleDateFormat("MM/dd/yyyy").parse(eventRow.get("Ev_Start_Date"));
//        sObject.setField("StartDate", d);
//      }
//      if (!Strings.isNullOrEmpty(eventRow.get("Ev_End_Date"))) {
//        Date d = new SimpleDateFormat("MM/dd/yyyy").parse(eventRow.get("Ev_End_Date"));
//        sObject.setField("EndDate", d);
//      }
//      sObject.setField("Type", eventRow.get("Ev_Type"));
//      if (!Strings.isNullOrEmpty("Ev_Goal")) {
//        sObject.setField("ExpectedRevenue", eventRow.get("Ev_Goal").replace("$", "").replaceAll(",", ""));
//      }
//      sObject.setField("Blackbaud_Campaign_ID__c", eventRow.get("Ev_Event_ID"));
//      if (!Strings.isNullOrEmpty(eventRow.get("Ev_Campaign_ID"))) {
//        sObject.setField("ParentId", campaignNameToId.get(eventRow.get("Ev_Campaign_ID")));
//      }
//
//      sfdcClient.batchInsert(sObject);
//    }
//    sfdcClient.batchFlush();

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // PARTICIPANTS
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

//    Map<String, String> eventIdToCampaignId = sfdcClient.queryListAutoPaged("SELECT Id, Blackbaud_Campaign_ID__c FROM Campaign WHERE Blackbaud_Campaign_ID__c!=''").stream()
//        .collect(Collectors.toMap(c -> (String) c.getField("Blackbaud_Campaign_ID__c"), c -> c.getId()));
//
//    File participantsFile = new File("/home/brmeyer/Downloads/Participants-more-fields-v2.xlsx");
//    InputStream participantsInputStream = new FileInputStream(participantsFile);
//    List<Map<String, String>> participantRows = Utils.getExcelData(participantsInputStream);
//
//    for (int i = 0; i < participantRows.size(); i++) {
//      log.info("processing participant row {}", i + 2);
//
//      Map<String, String> participantRow = participantRows.get(i);
//
//      if (!eventIdToCampaignId.containsKey(participantRow.get("PrtEv_Event_ID"))) {
//        continue;
//      }
//
//      if (constituentIdToContact.containsKey(participantRow.get("Prt_ID"))) {
//        SObject sObject = new SObject("CampaignMember");
//        sObject.setField("ContactId", constituentIdToContact.get(participantRow.get("Prt_ID")).getId());
//        sObject.setField("CampaignId", eventIdToCampaignId.get(participantRow.get("PrtEv_Event_ID")));
//        sfdcClient.batchInsert(sObject);
//      }
//
//      if (!Strings.isNullOrEmpty(participantRow.get("PrtGst_1_01_ID")) && constituentIdToContact.containsKey(participantRow.get("PrtGst_1_01_ID"))) {
//        SObject sObject = new SObject("CampaignMember");
//        sObject.setField("ContactId", constituentIdToContact.get(participantRow.get("PrtGst_1_01_ID")).getId()); // PrtCnBio_ID
//        sObject.setField("CampaignId", eventIdToCampaignId.get(participantRow.get("PrtEv_Event_ID")));
//        sfdcClient.batchInsert(sObject);
//      }
//    }
//    sfdcClient.batchFlush();

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // NOTES
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    File notesFile = new File("/home/brmeyer/Downloads/Notes.xlsx");
    InputStream notesInputStream = new FileInputStream(notesFile);
    List<Map<String, String>> noteRows = Utils.getExcelData(notesInputStream);

//    for (int i = 0; i < noteRows.size(); i++) {
//      log.info("processing note row {}", i + 2);
//
//      Map<String, String> noteRow = noteRows.get(i);
//
//      SObject cn = new SObject("ContentNote");
//      if (!Strings.isNullOrEmpty(noteRow.get("Constituent Note Title"))) {
//        cn.setField("Title", noteRow.get("Constituent Note Title"));
//      } else {
//        cn.setField("Title", "Note"); // required field
//      }
//      String note = noteRow.get("Constituent Note Date") + " " + noteRow.get("Constituent Note Author") + ": " + noteRow.get("Constituent Notes");
////      cn.setField("Content", Base64.getEncoder().encode(note.getBytes(StandardCharsets.UTF_8)));
//      cn.setField("Content", note);
//      sfdcClient.batchInsert(cn);
//    }
//    SFDCPartnerAPIClient.BatchResults batchResults = sfdcClient.batchFlush();
//    for (int i = 0; i < noteRows.size(); i++) {
//      log.info("processing note row {}", i + 2);
//
//      SaveResult result = batchResults.batchInsertResults().get(i);
//      if (!result.isSuccess() || Strings.isNullOrEmpty(result.getId())) {
//        log.info("ContentNote insert may have failed; skipping ContentDocumentLink insert");
//        continue;
//      }
//
//      Map<String, String> noteRow = noteRows.get(i);
//
//      SObject cdl = new SObject("ContentDocumentLink");
//      cdl.setField("ContentDocumentId", result.getId());
//      if (constituentIdToContact.containsKey(noteRow.get("Constituent ID"))) {
//        cdl.setField("LinkedEntityId", constituentIdToContact.get(noteRow.get("Constituent ID")).getId());
//      } else {
//        cdl.setField("LinkedEntityId", constituentIdToAccount.get(noteRow.get("Constituent ID")).getId());
//      }
//      sfdcClient.batchInsert(cdl);
//    }
//    sfdcClient.batchFlush();

//    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//    // RECURRING DONATION
//    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
//    // TODO: The following completely skips Bulk Upsert!
//
//    counter = 1;
//
//    for (Map<String, String> giftRow : giftRows) {
//      counter++;
//      log.info("processing recurring donation row {}", counter);
//
//      if ("Recurring Gift".equalsIgnoreCase(giftRow.get("Gf_Type"))) {
//        SObject sfdcRecurringDonation = new SObject("npe03__Recurring_Donation__c");
//
//        sfdcRecurringDonation.setField("Blackbaud_Gift_ID__c", giftRow.get("Gf_System_ID"));
//        Double amount = null;
//        if (!Strings.isNullOrEmpty(giftRow.get("Gf_Amount"))) {
//          amount = Double.parseDouble(giftRow.get("Gf_Amount").replace("$", "").replace(",", ""));
//        }
//        sfdcRecurringDonation.setField("npe03__Amount__c", amount);
//        sfdcRecurringDonation.setField("Name", giftRow.get("Gf_Description"));
//        sfdcRecurringDonation.setField("Fund__c", giftRow.get("Gf_Fund"));
//        sfdcRecurringDonation.setField("npsp__PaymentMethod__c", giftRow.get("Gf_Pay_method"));
//        sfdcRecurringDonation.setField("Reference__c", giftRow.get("Gf_Reference"));
//        Date d = new SimpleDateFormat("MM/dd/yyyy").parse(giftRow.get("Gf_FirstInstDue"));
//        sfdcRecurringDonation.setField("npe03__Date_Established__c", d);
//        sfdcRecurringDonation.setField("npsp__RecurringType__c", "Open");
//        LocalDate ld = LocalDate.parse(giftRow.get("Gf_FirstInstDue"), DateTimeFormatter.ofPattern("M/d/yyyy"));
//        sfdcRecurringDonation.setField("npsp__Day_of_Month__c", ld.getDayOfMonth() + "");
//
//        // BB: {'Active', 'Completed', 'Terminated'}
//        if ("Active".equalsIgnoreCase(giftRow.get("Gf_Gift_status"))) {
//          sfdcRecurringDonation.setField("npsp__Status__c", "Active");
//        } else {
//          sfdcRecurringDonation.setField("npsp__Status__c", "Closed");
//        }
//
//        // BB: {'Semi-Annually', 'Quarterly', 'Single Installment', 'Monthly', 'Annually', 'Irregular'}
//        // Default: every 1 Month for Monthly & Irregular
//        sfdcRecurringDonation.setField("npsp__InstallmentFrequency__c", 1);
//        sfdcRecurringDonation.setField("npe03__Installment_Period__c", "Monthly");
//        if ("Quarterly".equalsIgnoreCase(giftRow.get("Gf_Installmnt_Frqncy"))) {
//          sfdcRecurringDonation.setField("npsp__InstallmentFrequency__c", 3);
//        } else if ("Semi-Annually".equalsIgnoreCase(giftRow.get("Gf_Installmnt_Frqncy"))) {
//          sfdcRecurringDonation.setField("npsp__InstallmentFrequency__c", 6);
//        } else if ("Annually".equalsIgnoreCase(giftRow.get("Gf_Installmnt_Frqncy"))) {
//          sfdcRecurringDonation.setField("npsp__InstallmentFrequency__c", 1);
//          sfdcRecurringDonation.setField("npe03__Installment_Period__c", "Yearly");
//        }
//
//        String campaignId = null;
//        String campaign = giftRow.get("Gf_Campaign");
//        String appeal = giftRow.get("Gf_Appeal");
//        if (!Strings.isNullOrEmpty(campaign) && !Strings.isNullOrEmpty(appeal)) {
//          campaignId = campaignIds.get(campaign + ": " + appeal);
//        } else if (!Strings.isNullOrEmpty(campaign)) {
//          campaignId = campaignIds.get(campaign);
//        } else if (!Strings.isNullOrEmpty(appeal)) {
//          campaignId = campaignIds.get(appeal);
//        }
//        sfdcRecurringDonation.setField("npe03__Recurring_Donation_Campaign__c", campaignId);
//
//        // TODO: This should become a bulk lookup (loop over the rows, grab all constituent IDs, and query in a single shot)
//        //  in the future. But CLHS has so few that I'm just brute forcing it.
//        String constituentId = giftRow.get("Gf_CnBio_ID");
//        log.info(constituentId);
//        Optional<SObject> contact = sfdcClient.querySingle("SELECT Id FROM Contact WHERE Blackbaud_Constituent_ID__c='" + constituentId + "'");
//        if (contact.isPresent()) {
//          sfdcRecurringDonation.setField("Npe03__Contact__c", contact.get().getId());
//        } else {
//          // businesses only
//          Optional<SObject> account = sfdcClient.querySingle("SELECT Id FROM Account WHERE Blackbaud_Constituent_ID__c='" + constituentId + "' AND RecordTypeId='" + ORGANIZATION_RECORD_TYPE_ID + "'");
//          if (account.isPresent()) {
//            sfdcRecurringDonation.setField("Npe03__Organization__c", account.get().getId());
//          } else {
//            log.warn("MISSING CONSTITUENT: {}", constituentId);
//            continue;
//          }
//        }
//
//        // TODO: temporarily using standard inserts -- running into some missing constituents, so wanting to step through it
////        sfdcClient.batchInsert(sfdcRecurringDonation);
//        sfdcClient.insert(sfdcRecurringDonation);
//      }
//    }
//
//    sfdcClient.batchFlush();
//
//    Map<String, String> giftIdToOpp = sfdcClient.queryListAutoPaged("SELECT Id, Blackbaud_Gift_ID__c FROM Opportunity WHERE Blackbaud_Gift_ID__c!=''").stream()
//        .collect(Collectors.toMap(c -> (String) c.getField("Blackbaud_Gift_ID__c"), c -> c.getId()));

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // DONATIONS
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

//    // TODO: The following completely skips Bulk Upsert!
//
//    counter = 1;
//
//    // We order the batch inserts by donor ID, bunching them together per contact/account, which helps
//    // prevent lock errors.
//    Map<String, List<SObject>> oppInsertsByDonorId = new HashMap<>();
//
//    for (Map<String, String> giftRow : giftRows) {
//      counter++;
//
//      log.info("processing donation row {}", counter);
//
//      // TODO: Gift-in-Kind, MG Pay-Cash, MG Pledge, Pay-Cash, Pledge, Stock/Property, Pay-Gift-in-Kind, Pay-Stock/Property
//      // TODO: Pledges and Pay-Cash installments appear to share the same BB ID, so we should be able to use true
//      //  pledges and payments. That's (thankfully) not true of RDs.
//      if (List.of("Cash", "Recurring Gift Pay-Cash").contains(giftRow.get("Gf_Type"))) {
//        String id = giftRow.get("Gf_System_ID");
//
//        if (giftIdToOpp.containsKey(id)) {
//          continue;
//        }
//
//        SObject sfdcOpportunity = new SObject("Opportunity");
//
//        sfdcOpportunity.setField("RecordTypeId", DONATION_RECORD_TYPE_ID);
//        sfdcOpportunity.setField("Blackbaud_Gift_ID__c", id);
//        Double amount = null;
//        if (!Strings.isNullOrEmpty(giftRow.get("Gf_Amount"))) {
//          amount = Double.parseDouble(giftRow.get("Gf_Amount").replace("$", "").replace(",", ""));
//        }
//        sfdcOpportunity.setField("Amount", amount);
//        sfdcOpportunity.setField("Name", giftRow.get("Gf_Description"));
//        sfdcOpportunity.setField("Fund__c", giftRow.get("Gf_Fund"));
//        sfdcOpportunity.setField("Payment_Method__c", giftRow.get("Gf_Pay_method"));
//        sfdcOpportunity.setField("Reference__c", giftRow.get("Gf_Reference"));
//        sfdcOpportunity.setField("StageName", "Closed Won");
//        if (!Strings.isNullOrEmpty(giftRow.get("Gf_Date"))) {
//          Date d = new SimpleDateFormat("MM/dd/yyyy").parse(giftRow.get("Gf_Date"));
//          sfdcOpportunity.setField("CloseDate", d);
//        }
//
//        String campaignId = null;
//        String campaign = giftRow.get("Gf_Campaign");
//        String appeal = giftRow.get("Gf_Appeal");
//        if (!Strings.isNullOrEmpty(campaign) && !Strings.isNullOrEmpty(appeal)) {
//          campaignId = campaignNameToId.get(campaign + ": " + appeal);
//        } else if (!Strings.isNullOrEmpty(campaign)) {
//          campaignId = campaignNameToId.get(campaign);
//        } else if (!Strings.isNullOrEmpty(appeal)) {
//          campaignId = campaignNameToId.get(appeal);
//        }
//        sfdcOpportunity.setField("CampaignId", campaignId);
//
////        sfdcOpportunity.setField("Npe03__Recurring_Donation__c", recurringDonationId);
//
//        String constituentId = giftRow.get("Gf_CnBio_ID");
//        SObject contact = constituentIdToContact.get(constituentId);
//        SObject account = constituentIdToAccount.get(constituentId);
//        String donorId;
//        if (contact != null) {
//          sfdcOpportunity.setField("AccountId", contact.getField("AccountId"));
//          sfdcOpportunity.setField("ContactId", contact.getId());
//          donorId = contact.getId();
//        } else {
//          if (account != null) {
//            sfdcOpportunity.setField("AccountId", account.getId());
//            donorId = account.getId();
//          } else {
//            log.warn("MISSING CONSTITUENT: {}", constituentId);
//            continue;
//          }
//        }
//
//        if (!oppInsertsByDonorId.containsKey(donorId)) {
//          oppInsertsByDonorId.put(donorId, new ArrayList<>());
//        }
//        oppInsertsByDonorId.get(donorId).add(sfdcOpportunity);
//      }
//    }
//
//    counter = 1;
//    for (Map.Entry<String, List<SObject>> entry : oppInsertsByDonorId.entrySet()) {
//      for (SObject opp : entry.getValue()) {
//        counter++;
//        log.info("processing donation row {}", counter);
//
//        // TODO: This really slows things down, but we're running into lock contention if a single contact/account's
//        //  opportunities are spread across multiple inserts. We could optimize it by looking at current batch sizes
//        //  and flush only when the next batch will push it over the edge?
//        sfdcClient.batchInsert(opp);
//      }
//      sfdcClient.batchFlush();
//    }
//    sfdcClient.batchFlush();

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // SOFT CREDITS
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

//    // doesn't matter if there were multiple soft credits, we only care that it had any, period
//    List<String> softCreditGiftIds = sfdcClient.queryListAutoPaged("SELECT Opportunity.Blackbaud_Gift_ID__c FROM OpportunityContactRole WHERE Opportunity.Blackbaud_Gift_ID__c!='' AND IsPrimary=FALSE AND Role='Soft Credit'").stream()
//        .map(r -> (String) r.getChild("Opportunity").getField("Blackbaud_Gift_ID__c")).collect(Collectors.toList());
//    softCreditGiftIds.addAll(sfdcClient.queryListAutoPaged("SELECT npsp__Opportunity__r.Blackbaud_Gift_ID__c FROM npsp__Partial_Soft_Credit__c WHERE npsp__Opportunity__r.Blackbaud_Gift_ID__c!=''").stream()
//        .map(r -> (String) r.getChild("npsp__Opportunity__r").getField("Blackbaud_Gift_ID__c")).toList());
//
//    counter = 1;
//    for (Map<String, String> giftRow : giftRows) {
//      counter++;
//
//      log.info("processing soft credit row {}", counter);
//
//      // TODO: Gift-in-Kind, MG Pay-Cash, MG Pledge, Pay-Cash, Pledge, Stock/Property, Pay-Gift-in-Kind, Pay-Stock/Property
//      if (List.of("Cash", "Recurring Gift Pay-Cash").contains(giftRow.get("Gf_Type"))) {
//        String id = giftRow.get("Gf_System_ID");
//
//        if (softCreditGiftIds.contains(id)) {
//          continue;
//        }
//
//        if (!giftIdToOpp.containsKey(id)) {
//          log.info("missing gift {}", id);
//          continue;
//        }
//
//        // if Gf_SfCrdt_1_02_Amount, partial soft credits (do 1-5)
//        // else, standard opp contact role with Soft Credit type
//        if (!Strings.isNullOrEmpty(giftRow.get("Gf_SfCrdt_1_02_Amount"))) {
//          // more than one soft credit, so we'll need the partial setup
//          for (int i = 1; i <= 5; i++) {
//            String prefix = "Gf_SfCrdt_1_" + new DecimalFormat("00").format(i) + "_";
//            String constituentId = giftRow.get(prefix + "Constit_ID");
//            String amount = giftRow.get(prefix + "Amount");
//            if (!Strings.isNullOrEmpty(amount)) {
//              if (!constituentIdToContact.containsKey(constituentId)) {
//                log.info("missing constituent {}", constituentId);
//                continue;
//              }
//
//              amount = amount.replace("$", "").replaceAll(",", "");
//
//              SObject partialSoftCredit = new SObject("npsp__Partial_Soft_Credit__c");
//              partialSoftCredit.setField("npsp__Opportunity__c", giftIdToOpp.get(id));
//              // TODO: Are any of the soft credits going to Accounts? Would need to use npsp__Account_Soft_Credit__c obj.
//              partialSoftCredit.setField("npsp__Contact__c", constituentIdToContact.get(constituentId).getId());
//              partialSoftCredit.setField("npsp__Amount__c", amount);
//              sfdcClient.batchInsert(partialSoftCredit);
//            }
//          }
//        } else if (!Strings.isNullOrEmpty(giftRow.get("Gf_SfCrdt_1_01_Amount"))) {
//          if (!constituentIdToContact.containsKey(giftRow.get("Gf_SfCrdt_1_01_Constit_ID"))) {
//            log.info("missing constituent {}", giftRow.get("Gf_SfCrdt_1_01_Constit_ID"));
//            continue;
//          }
//
//          SObject contactRole = new SObject("OpportunityContactRole");
//          contactRole.setField("OpportunityId", giftIdToOpp.get(id));
//          // TODO: Are any of the soft credits going to Accounts? Would need to use npsp__Account_Soft_Credit__c obj.
//          contactRole.setField("ContactId", constituentIdToContact.get(giftRow.get("Gf_SfCrdt_1_01_Constit_ID")).getId());
//          contactRole.setField("IsPrimary", false);
//          contactRole.setField("Role", "Soft Credit");
//          sfdcClient.batchInsert(contactRole);
//        }
//      }
//    }
//    sfdcClient.batchFlush();
  }

  private static void migrate(Map<String, String> row, String householdId, List<Map<String, String>> primaryRows, List<Map<String, String>> secondaryRows, List<String> seenEmails) {

    // these eventually get combined, but separating them so that (as an ex) spouses can repurpose the account
    // data we've already parsed
    Map<String, String> accountData = new HashMap<>();
    Map<String, String> contactData = new HashMap<>();
    List<Map<String, String>> secondaryContactData = new ArrayList<>();

    String id = row.get("CnBio_ID");

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // ACCOUNT
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    accountData.put("Account ExtRef Blackbaud_Constituent_ID__c", householdId);
    accountData.put("Account Custom Blackbaud_Constituent_ID__c", householdId);

    boolean isBusiness = !Strings.isNullOrEmpty(row.get("CnBio_Org_Name"));

    if (isBusiness) {
      accountData.put("Account Record Type ID", ORGANIZATION_RECORD_TYPE_ID);
      accountData.put("Account Name", row.get("CnBio_Org_Name"));
    } else {
      accountData.put("Account Record Type ID", HOUSEHOLD_RECORD_TYPE_ID);
      // don't set the explicit household name -- NPSP will automatically manage it based on the associated contacts
    }

    // Preferred address -> Account Billing Address
    // Combine Street fields 1-4 into single string
    Set<String> cnAdrPrfAddr = new LinkedHashSet<>();
    for (int i = 1; i <= 4; i++) {
      String field = "CnAdrPrf_Addrline" + i;
      if (!Strings.isNullOrEmpty(row.get(field))) {
        cnAdrPrfAddr.add(row.get(field));
      }
    }
    String cnAdrPrfAddrStr = String.join(", ", cnAdrPrfAddr);
    accountData.put("Account Billing Address", cnAdrPrfAddrStr);
    accountData.put("Account Billing City", row.get("CnAdrPrf_City"));
    accountData.put("Account Billing State", row.get("CnAdrPrf_State"));
    accountData.put("Account Billing PostCode", row.get("CnAdrPrf_ZIP"));
    accountData.put("Account Billing Country", row.get("CnAdrPrf_ContryLongDscription"));
    accountData.put("Account Custom BB_Address_Type__c", row.get("CnAdrPrf_Type"));
    if ("No".equalsIgnoreCase(row.get("CnAdrPrf_Sndmailtthisaddrss"))) {
      accountData.put("Account Custom BB_BillingAddrOptOut__c", "true");
    }
    if ("Yes".equalsIgnoreCase(row.get("CnAdrPrf_Seasonal"))) {
      accountData.put("Account Custom BB_BillingAddrSeasonal__c", "true");
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // CONTACT
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    contactData.put("Contact ExtRef Blackbaud_Constituent_ID__c", id);
    contactData.put("Contact Custom Blackbaud_Constituent_ID__c", id);
    contactData.put("Contact Custom Append Source__c", "Blackbaud");

    // Combine all ph/comments and dump in single notes/desc field
    Set<String> cnAllPhoneComments = new LinkedHashSet<>();
    // parse phone/email/fax/website items, all labeled CnPh...
    for (int i = 1; i <= 3; i++) {
      String cnPhType = "CnPh_1_0" + i + "_Phone_type";
      String cnPhPhone = "CnPh_1_0" + i + "_Phone_number";
      String cnPhIsPrimary = "CnPh_1_0" + i + "_Is_Primary";
      String cnPhComments = "CnPh_1_0" + i + "_Comments";

      if ("Yes".equalsIgnoreCase(row.get("CnPh_1_0" + i + "_Inactive")) || "Yes".equalsIgnoreCase(row.get("CnPh_1_0" + i + "_Do_Not_Contact"))) {
        continue;
      }

      // Append comment to set
      if (!Strings.isNullOrEmpty(row.get(cnPhComments))) {
        cnAllPhoneComments.add(row.get(cnPhPhone) + ": " + row.get(cnPhComments));
      }

      if ("Email".equalsIgnoreCase(row.get(cnPhType))) {
        if (isBusiness) {
          accountData.put("Account Custom Email__c", row.get(cnPhPhone));
        } else {
          if (!seenEmails.contains(row.get(cnPhPhone))) {
            contactData.put("Contact Email", row.get(cnPhPhone));
            seenEmails.add(row.get(cnPhPhone));
          }
        }
      } else if ("Home".equalsIgnoreCase(row.get(cnPhType))) {
        contactData.put("Contact Home Phone", row.get(cnPhPhone));
        if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
          contactData.put("Contact Custom npe01__PreferredPhone__c", "Home");
        }
      } else if ("Home 2".equalsIgnoreCase(row.get(cnPhType))) {
        contactData.put("Contact Custom OtherPhone", row.get(cnPhPhone));
        if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
          contactData.put("Contact Custom npe01__PreferredPhone__c", "Other");
        }
      } else if ("Wireless".equalsIgnoreCase(row.get(cnPhType))) {
        contactData.put("Contact Mobile Phone", row.get(cnPhPhone));
        if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
          contactData.put("Contact Custom npe01__PreferredPhone__c", "Mobile");
        }
      } else if ("Business/College".equalsIgnoreCase(row.get(cnPhType))) {
        // Goes on Account
        accountData.put("Account Phone", row.get(cnPhPhone));
      } else if ("Business Fax".equalsIgnoreCase(row.get(cnPhType))) {
        // Goes on Account
        accountData.put("Account Fax", row.get(cnPhPhone));
      } else if ("Website".equalsIgnoreCase(row.get(cnPhType))) {
        // Goes on Account
        accountData.put("Account Website", row.get(cnPhPhone));
      } else if ("Facebook".equalsIgnoreCase(row.get(cnPhType))) {
        contactData.put("Contact Custom BB_Facebook__c", row.get(cnPhPhone));
      }
    }

    // Combine all ph/comments and dump in single notes/desc field
    String cnAllPhoneCommentsCombined = String.join(", ", cnAllPhoneComments);
    accountData.put("Account Custom BB_Notes__c", cnAllPhoneCommentsCombined);
    contactData.put("Contact Custom BB_Notes__c", cnAllPhoneCommentsCombined);

    // Zero Orgs have Contact Names
    // Set Org name as Last name
    if (isBusiness) {
      accountData.put("Account Name", row.get("CnBio_Org_Name"));
    } else {
      contactData.put("Contact First Name", row.get("CnBio_First_Name"));
      contactData.put("Contact Last Name", row.get("CnBio_Last_Name"));
      contactData.put("Contact Custom Maiden_Name__c", row.get("CnBio_Maiden_name"));
      contactData.put("Contact Custom Middle_Name__c", row.get("CnBio_Middle_Name"));
      contactData.put("Contact Custom Preferred_Name__c", row.get("CnBio_Nickname"));
      contactData.put("Contact Custom Salutation", row.get("CnBio_Title_1"));
      contactData.put("Contact Custom Suffix__c", row.get("CnBio_Suffix_1"));

      // These must have been hand-entered, could be: 1950, 1/30, 1/1950, 1/1/1950
      contactData.put("Contact Custom BB_Birthdate__c", row.get("CnBio_Birth_date"));
      contactData.put("Contact Custom BB_CnBio_Ethnicity__c", row.get("CnBio_Ethnicity"));
      if ("Yes".equalsIgnoreCase(row.get("CnBio_Deceased"))) {
        contactData.put("Contact Custom npsp__Deceased__c", "true");
        // These must have been hand-entered, could be: 1950, 1/30, 1/1950, 1/1/1950
        contactData.put("Contact Custom BB_CnBio_Deceased_Date__c", row.get("CnBio_Deceased_Date"));
      }
      contactData.put("Contact Custom Gender__c", row.get("CnBio_Gender"));
      contactData.put("Contact Custom BB_CnBio_Marital_status__c", row.get("CnBio_Marital_status"));
      contactData.put("Contact Custom Class_Of__c", row.get("CnPrAl_Class_of"));
      contactData.put("Contact Custom Student_Notes__c", row.get("CnPrAl_Notes"));
      contactData.put("Contact Custom Student_Status__c", row.get("CnPrAl_Status"));
    }

    // Opt out / inactive fields
    if ("Yes".equalsIgnoreCase(row.get("CnBio_Requests_no_e-mail"))) {
      contactData.put("Contact Custom HasOptedOutOfEmail", "true");
    }
    if ("Yes".equalsIgnoreCase(row.get("CnBio_Inactive"))) {
      contactData.put("Contact Custom BB_CnBio_Inactive__c", "true");
    }
    if ("Yes".equalsIgnoreCase(row.get("CnBio_Solicitor_Inactive"))) {
      contactData.put("Contact Custom BB_CnBio_Solicitor_Inactive__c", "true");
    }
    if ("Yes".equalsIgnoreCase(row.get("CnBio_Anonymous"))) {
      contactData.put("Contact Custom BB_CnBio_Anonymous__c", "true");
    }

    // Address 1 -> Contact
    // Possible types: {'Winter', 'Summer', 'Main', 'Home', 'Previous address', 'Home 2', 'Business', 'Business/College'}
    // Types are not exclusive, so both could be one type, a mix, or neither
    Set<String> cnAdrAllAddr1 = new LinkedHashSet<>();
    for (int i = 1; i <= 4; i++) {
      String field = "CnAdrAll_1_01_Addrline" + i;
      if (!Strings.isNullOrEmpty(row.get(field))) {
        cnAdrAllAddr1.add(row.get(field));
      }
    }
    String cnAdrAllAddrStr = String.join(", ", cnAdrAllAddr1);
    contactData.put("Contact Custom BB_Address1_Street__c", cnAdrAllAddrStr);
    contactData.put("Contact Custom BB_Address1_City__c", row.get("CnAdrAll_1_01_City"));
    contactData.put("Contact Custom BB_Address1_State__c", row.get("CnAdrAll_1_01_State"));
    contactData.put("Contact Custom BB_Address1_ZIP__c", row.get("CnAdrAll_1_01_ZIP"));
    contactData.put("Contact Custom BB_Address1_Country__c", row.get("CnAdrAll_1_01_ContryLongDscription"));
    contactData.put("Contact Custom BB_Address1_Type__c", row.get("CnAdrAll_1_01_Type"));
    if ("No".equalsIgnoreCase(row.get("CnAdrAll_1_01_Sndmailtthisaddrss"))) {
      contactData.put("Contact Custom BB_Address1_OptOut__c", "true");
    }
    if ("Yes".equalsIgnoreCase(row.get("CnAdrAll_1_01_Preferred"))) {
      contactData.put("Contact Custom BB_Address1_Preferred__c", "true");
    }
    if ("Yes".equalsIgnoreCase(row.get("CnAdrAll_1_01_Seasonal"))) {
      contactData.put("Contact Custom BB_Address1_Seasonal__c", "true");
    }
    // All in pattern of M/d.
    // Could potentially be parsed, but following other unpredictable date fields and going text box route
    contactData.put("Contact Custom BB_Address1_Seasonal_From__c", row.get("CnAdrAll_1_01_Seasonal_From"));
    contactData.put("Contact Custom BB_Address1_Seasonal_To__c", row.get("CnAdrAll_1_01_Seasonal_To"));

    // Address 2 -> Contact
    Set<String> cnAdrAllAddr2 = new LinkedHashSet<>();
    for (int i = 1; i <= 4; i++) {
      String field = "CnAdrAll_1_02_Addrline" + i;
      if (!Strings.isNullOrEmpty(row.get(field))) {
        cnAdrAllAddr2.add(row.get(field));
      }
    }
    String cnAdrAllAddr2Str = String.join(", ", cnAdrAllAddr2);
    contactData.put("Contact Custom BB_Address2_Street__c", cnAdrAllAddr2Str);
    contactData.put("Contact Custom BB_Address2_City__c", row.get("CnAdrAll_1_02_City"));
    contactData.put("Contact Custom BB_Address2_State__c", row.get("CnAdrAll_1_02_State"));
    contactData.put("Contact Custom BB_Address2_ZIP__c", row.get("CnAdrAll_1_02_ZIP"));
    contactData.put("Contact Custom BB_Address2_Country__c", row.get("CnAdrAll_1_02_ContryLongDscription"));
    contactData.put("Contact Custom BB_Address2_Type__c", row.get("CnAdrAll_1_02_Type"));
    if ("No".equalsIgnoreCase(row.get("CnAdrAll_1_02_Sndmailtthisaddrss"))) {
      contactData.put("Contact Custom BB_Address2_OptOut__c", "true");
    }
    if ("Yes".equalsIgnoreCase(row.get("CnAdrAll_1_02_Preferred"))) {
      contactData.put("Contact Custom BB_Address2_Preferred__c", "true");
    }
    if ("Yes".equalsIgnoreCase(row.get("CnAdrAll_1_02_Seasonal"))) {
      contactData.put("Contact Custom BB_Address2_Seasonal__c", "true");
    }
    // All in pattern of M/d.
    // Could potentially be parsed, but following other unpredictable date fields and going text box route
    contactData.put("Contact Custom BB_Address2_Seasonal_From__c", row.get("CnAdrAll_1_02_Seasonal_From"));
    contactData.put("Contact Custom BB_Address2_Seasonal_To__c", row.get("CnAdrAll_1_02_Seasonal_To"));

    // Constituent Types
    // Possible values: {'Current Student', 'Spouse is Alumni', 'Former Guardian', 'Withdrawn Parent', 'Current Guardian', 'Member Church', 'Online Donation', 'Retired Staff', 'Delegate', 'Community Member', 'Friend', 'Withdrawn Grandparent', 'Staff', 'Withdrawn Student', 'Honorary CEF BOD', 'Former Grandparent', 'Spouse is Staff', 'Parent of Alumni', 'Former Staff', 'Current Parent', 'Spouse is Former Staff', 'Former CLHS BOD', 'Former CEF BOD', 'Alumni', 'Other', 'Spouse is Board of Direcotrs', 'Grandparent'}
    Set<String> cnTypeCodes = new LinkedHashSet<>();
    for (int i = 1; i <= 5; i++) {
      String field = "CnCnstncy_1_0" + i + "_CodeLong";
      if (!Strings.isNullOrEmpty(row.get(field))) {
        cnTypeCodes.add(row.get(field));
      }
    }
    String cnTypeCodesStr = String.join(";", cnTypeCodes);
    contactData.put("Contact Custom Type__c", cnTypeCodesStr);

    // Solicit codes
    // Possible values: 'Do not  mail', 'Removed by request', 'Do not phone', 'Do not send Mass Appeal', 'Do Not Send Stelter Mailings', 'Do Not Contact/Solicit', 'Send All Information', 'Do Not Mail - Out of the Country', 'Do Not Send "Cadets"', 'Send Publications only'
    Set<String> cnSolicitCodes = new LinkedHashSet<>();
    for (int i = 1; i <= 5; i++) {
      String field = "CnSolCd_1_0" + i + "_Solicit_Code";
      if (!Strings.isNullOrEmpty(row.get(field))) {
        cnSolicitCodes.add(row.get(field));
      }
    }
    String cnSolicitCodesStr = String.join(";", cnSolicitCodes);
    contactData.put("Contact Custom BB_Solicit_Codes__c", cnSolicitCodesStr);

    // If relationships define individuals *without* their own constituent IDs, we need to save them off as
    // secondary contacts here since it's the only time we'll see them. We'll create fake constituent IDs for them,
    // using the original + an index.
    for (int i = 1; i <= 25; i++) {
      String prefix = "CnRelInd_1_" + new DecimalFormat("00").format(i) + "_";
      if (Strings.isNullOrEmpty(row.get(prefix + "ID")) && !Strings.isNullOrEmpty(row.get(prefix + "Last_Name"))) {
        Map<String, String> data = new HashMap<>();

        // append the index to the end of the constituent ID
        data.put("Contact ExtRef Blackbaud_Constituent_ID__c", row.get("CnBio_ID") + "-" + i);
        data.put("Contact Custom Blackbaud_Constituent_ID__c", row.get("CnBio_ID") + "-" + i);
        data.put("Contact Custom Append Source__c", "Blackbaud");

        data.put("Contact First Name", row.get(prefix + "First_Name"));
        data.put("Contact Last Name", row.get(prefix + "Last_Name"));
        data.put("Contact Custom Preferred_Name__c", row.get(prefix + "Nickname"));
        data.put("Contact Custom Suffix__c", row.get(prefix + "Suffix_1"));
        data.put("Contact Custom BB_Birthdate__c", row.get(prefix + "Birth_date"));
        if ("Yes".equalsIgnoreCase(row.get(prefix + "Deceased"))) {
          data.put("Contact Custom npsp__Deceased__c", "true");
          data.put("Contact Custom BB_CnBio_Deceased_Date__c", row.get(prefix + "Deceased_Date"));
        }
        data.put("Contact Custom Gender__c", row.get(prefix + "Gender"));
        if ("Yes".equalsIgnoreCase(row.get(prefix + "Inactive"))) {
          data.put("Contact Custom BB_CnBio_Inactive__c", "true");
        }
        data.put("Contact Mobile Phone", row.get(prefix + "Primary_phone"));

        secondaryContactData.add(data);
      }
    }

    // combine and save off the rows
    contactData.putAll(accountData);
    primaryRows.add(contactData);
    for (Map<String, String> data : secondaryContactData) {
      if (!data.isEmpty()) {
        data.putAll(accountData);
        secondaryRows.add(data);
      }
    }
  }
}
