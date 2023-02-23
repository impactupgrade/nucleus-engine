/*
 * Copyright (c) 2022 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.client.SfdcMetadataClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmImportEvent;
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

    File file = new File("/home/brmeyer/Downloads/Constituent+Spouse-v3-25relationships.xlsx");
    InputStream inputStream = new FileInputStream(file);
    List<Map<String, String>> rows = Utils.getExcelData(inputStream);

    List<Map<String, String>> primaryRows = new ArrayList<>();
    List<Map<String, String>> secondaryRows = new ArrayList<>();

    // TODO: There's unfortunately a wide, inconsistent mix of ways that households can be defined in RE data.
    //  Relationships between spouses will usually identify one as the head-of-household, so we first use those heads
    //  as the primary contacts of accounts. But then students/children do *not* have a parent relationship that's
    //  defined as HoH, so we can't globally rely on that to give away household groupings. We instead opt to use
    //  mailing addresses, since RE at least makes these consistent (at least for CLHS).
    List<String> headOfHouseholdIds = new ArrayList<>();
    Map<String, String> spouseIdsToHouseholdIds = new HashMap<>();
    Map<String, String> addressesToHouseholdIds = new HashMap<>();

    // Make one pass to discover all heads of households. This is unfortunately not at the Constituent level, but is
    // instead buried in the lists of relationships :(
    for (Map<String, String> row : rows) {
      // households only
      if (!Strings.isNullOrEmpty(row.get("CnBio_Org_Name"))) {
        continue;
      }

      String id = row.get("CnBio_ID");

      for (int i = 1; i <= 25; i++) {
        String prefix = "CnRelInd_1_" + new DecimalFormat("00").format(i) + "_";
        if ("Yes".equalsIgnoreCase(row.get(prefix + "Is_Headofhousehold"))) {
          // the relationship is the head of household, not this constituent itself
          String headOfHouseholdId = row.get(prefix + "ID");
          headOfHouseholdIds.add(headOfHouseholdId);
          // since we have the direct relationship, might as well save it off and use it later
          spouseIdsToHouseholdIds.put(id, headOfHouseholdId);
          break;
        }
      }
    }

    // Make another pass to discover the heads of households' addresses.
    for (Map<String, String> row : rows) {
      String id = row.get("CnBio_ID");
      if (headOfHouseholdIds.contains(id)) {
        Set<String> cnAdrPrfAddr = new LinkedHashSet<>();
        for (int i = 1; i <= 4; i++) {
          String field = "CnAdrPrf_Addrline" + i;
          if (!Strings.isNullOrEmpty(row.get(field))) {
            cnAdrPrfAddr.add(row.get(field));
          }
        }
        String street = String.join(", ", cnAdrPrfAddr);
        String zip = row.get("CnAdrPrf_ZIP");
        String address = street + " " + zip;
        address = address.trim();
        if (!Strings.isNullOrEmpty(address) && !addressesToHouseholdIds.containsKey(address)) {
          addressesToHouseholdIds.put(address, id);
        }
      }
    }

    // TODO: If any addresses do NOT have a HoH, should we make another pass and specifically look for parents,
    //  guardians, or grandparents first? Prevent kids from becomings heads of households?

    // The next pass inserts all constituents that were discovered to be heads of households, ensuring that they're
    // the primary contact on households as they're created.
    for (Map<String, String> row : rows) {
      String id = row.get("CnBio_ID");
      if (headOfHouseholdIds.contains(id)) {
        // We use the head of household's ID as the account's extref key.
        String householdId = id;
        migrate(row, householdId, primaryRows, secondaryRows);
      }
    }

    // TODO: HACK! We shouldn't technically need to run these as separate upsert batches. However, since batch
    //  inserts don't have a deterministic order, the spouses sometimes get inserted first, which causes NPSP to
    //  automatically set them as the primary contact on the household. I can't find a way to force that, without
    //  complicated logic to update the account after the fact.
    // TODO: Added benefit of this approach: if the secondary contact already exists (from one of the other imports,
    //  FACTS, HubSpot, etc), we're ensuring that BB's primary contact is getting a household with the BB ID set.
    List<CrmImportEvent> importEvents = CrmImportEvent.fromGeneric(primaryRows);
    env.primaryCrmService().processBulkImport(importEvents);
    importEvents = CrmImportEvent.fromGeneric(secondaryRows);
    env.primaryCrmService().processBulkImport(importEvents);

    primaryRows.clear();
    secondaryRows.clear();

    // Then do another pass to insert everyone else. Doing this after the above ensure households already exist.
    for (Map<String, String> row : rows) {
      String id = row.get("CnBio_ID");
      if (!headOfHouseholdIds.contains(id)) {
        Set<String> cnAdrPrfAddr = new LinkedHashSet<>();
        for (int i = 1; i <= 4; i++) {
          String field = "CnAdrPrf_Addrline" + i;
          if (!Strings.isNullOrEmpty(row.get(field))) {
            cnAdrPrfAddr.add(row.get(field));
          }
        }
        String street = String.join(", ", cnAdrPrfAddr);
        String zip = row.get("CnAdrPrf_ZIP");
        String address = street + " " + zip;
        address = address.trim();

        String householdId;
        if (spouseIdsToHouseholdIds.containsKey(id)) {
          householdId = spouseIdsToHouseholdIds.get(id);
        } else if (!Strings.isNullOrEmpty(address) && addressesToHouseholdIds.containsKey(address)) {
          householdId = addressesToHouseholdIds.get(address);
        } else {
          // business or single-contact household (IE, no defined HoH) -- use their ID for the household extref
          householdId = id;
        }

        migrate(row, householdId, primaryRows, secondaryRows);
      }
    }

    // TODO: HACK! We shouldn't technically need to run these as separate upsert batches. However, since batch
    //  inserts don't have a deterministic order, the spouses sometimes get inserted first, which causes NPSP to
    //  automatically set them as the primary contact on the household. I can't find a way to force that, without
    //  complicated logic to update the account after the fact.
    // TODO: Added benefit of this approach: if the secondary contact already exists (from one of the other imports,
    //  FACTS, HubSpot, etc), we're ensuring that BB's primary contact is getting a household with the BB ID set.
    importEvents = CrmImportEvent.fromGeneric(primaryRows);
    env.primaryCrmService().processBulkImport(importEvents);
    importEvents = CrmImportEvent.fromGeneric(secondaryRows);
    env.primaryCrmService().processBulkImport(importEvents);

    primaryRows.clear();
    secondaryRows.clear();

//    File giftsFile = new File("/home/brmeyer/Downloads/Gifts-with-Installments-v4-more-info.xlsx");
//    InputStream giftInputStream = new FileInputStream(giftsFile);
//    List<Map<String, String>> giftRows = Utils.getExcelData(giftInputStream);
//
//    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//    // CAMPAIGNS
//    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
//    int counter = 1;
//    Map<String, String> parentCampaigns = new HashMap<>();
//    Set<String> subCampaigns = new HashSet<>();
//    for (Map<String, String> giftRow : giftRows) {
//      counter++;
//      log.info("processing row {}", counter);
//
//      String campaign = giftRow.get("Gf_Campaign");
//      String appeal = giftRow.get("Gf_Appeal");
//
//      if (!Strings.isNullOrEmpty(campaign) && !parentCampaigns.containsKey(campaign)) {
//        SObject sObject = new SObject("Campaign");
//        sObject.setField("Name", campaign);
//        String id = sfdcClient.insert(sObject).getId();
//        parentCampaigns.put(campaign, id);
//      }
//      if (!Strings.isNullOrEmpty(appeal) && !subCampaigns.contains(campaign + "|" + appeal)) {
//        SObject sObject = new SObject("Campaign");
//        String name = Strings.isNullOrEmpty(campaign) ? appeal : campaign + ": " + appeal;
//        sObject.setField("Name", name);
//        sObject.setField("ParentId", parentCampaigns.get(campaign));
//        sfdcClient.batchInsert(sObject);
//        subCampaigns.add(campaign + "|" + appeal);
//      }
//    }
//    sfdcClient.batchFlush();

//    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//    // RECURRING DONATION
//    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
//    // TODO: The following completely skips Bulk Upsert!
//
//    int counter = 1;
//
//    Map<String, String> campaignIds = new HashMap<>();
//    sfdcClient.getCampaigns().forEach(c -> campaignIds.put((String) c.getField("Name"), c.getId()));
//
//    for (Map<String, String> giftRow : giftRows) {
//      counter++;
//      log.info("processing row {}", counter);
//
//      if ("Recurring Gift".equalsIgnoreCase(giftRow.get("Gf_Type"))) {
//        SObject sfdcRecurringDonation = new SObject("npe03__Recurring_Donation__c");
//
//        sfdcRecurringDonation.setField("Blackbaud_Gift_ID__c", giftRow.get("Gf_Gift_ID"));
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
//    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//    // DONATIONS
//    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
//    // TODO: The following completely skips Bulk Upsert!
//
//    int counter = 1;
//
//    Map<String, SObject> contactsByConstituentIds = new HashMap<>();
//    List<SObject> contacts = sfdcClient.queryListAutoPaged("SELECT Id, AccountId, Blackbaud_Constituent_ID__c FROM Contact WHERE Blackbaud_Constituent_ID__c!=''");
//    for (SObject contact : contacts) {
//      contactsByConstituentIds.put((String) contact.getField("Blackbaud_Constituent_ID__c"), contact);
//    }
//
//    Map<String, SObject> accountsByConstituentIds = new HashMap<>();
//    // businesses only
//    List<SObject> accounts = sfdcClient.queryListAutoPaged("SELECT Id, Blackbaud_Constituent_ID__c FROM Account WHERE Blackbaud_Constituent_ID__c!='' AND RecordTypeId='" + ORGANIZATION_RECORD_TYPE_ID + "'");
//    for (SObject account : accounts) {
//      accountsByConstituentIds.put((String) account.getField("Blackbaud_Constituent_ID__c"), account);
//    }
//
//    for (Map<String, String> giftRow : giftRows) {
//      counter++;
//
//      log.info("processing row {}", counter);
//
//      // TODO: Gift-in-Kind, MG Pay-Cash, MG Pledge, Pay-Cash, Pledge, Recurring Gift Pay-Cash, Stock/Property, Pay-Gift-in-Kind, Pay-Stock/Property
//      if ("Cash".equalsIgnoreCase(giftRow.get("Gf_Type"))) {
//        String id = giftRow.get("Gf_Gift_ID");
//
//        // TODO: Some old gifts with no IDs, skipping for now
//        if (Strings.isNullOrEmpty(id)) {
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
//          campaignId = campaignIds.get(campaign + ": " + appeal);
//        } else if (!Strings.isNullOrEmpty(campaign)) {
//          campaignId = campaignIds.get(campaign);
//        } else if (!Strings.isNullOrEmpty(appeal)) {
//          campaignId = campaignIds.get(appeal);
//        }
//        sfdcOpportunity.setField("CampaignId", campaignId);
//
//        // TODO
////        sfdcOpportunity.setField("Npe03__Recurring_Donation__c", recurringDonationId);
//
//        String constituentId = giftRow.get("Gf_CnBio_ID");
//        SObject contact = contactsByConstituentIds.get(constituentId);
//        SObject account = accountsByConstituentIds.get(constituentId);
//        if (contact != null) {
//          sfdcOpportunity.setField("ContactId", contact.getId());
//          sfdcOpportunity.setField("AccountId", contact.getField("AccountId"));
//        } else {
//          if (account != null) {
//            sfdcOpportunity.setField("AccountId", account.getId());
//          } else {
//            log.warn("MISSING CONSTITUENT: {}", constituentId);
//            continue;
//          }
//        }
//
//        sfdcClient.batchInsert(sfdcOpportunity);
//      }
//    }
//
//    sfdcClient.batchFlush();
  }

  private static void migrate(Map<String, String> row, String householdId, List<Map<String, String>> primaryRows, List<Map<String, String>> secondaryRows) {

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
          contactData.put("Contact Email", row.get(cnPhPhone));
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
      contactData.put("Contact Custom Suffix__c", row.get("CnBio_Suffix_1"));
    }

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
    contactData.put("Contact Custom BB_Type__c", cnTypeCodesStr);

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
