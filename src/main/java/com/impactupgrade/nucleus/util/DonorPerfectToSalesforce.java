/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.impactupgrade.integration.sfdc.SFDCPartnerAPIClient;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.sobject.SObject;

import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DonorPerfectToSalesforce {

  public static void main(String[] args) throws Exception {
    Environment env = new Environment() {
      @Override
      public EnvironmentConfig getConfig() {
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.crmPrimary = "salesforce";
        envConfig.salesforce.sandbox = false;
        envConfig.salesforce.url = "neighborlinkfortwayne.my.salesforce.com";
        envConfig.salesforce.username = "team+nlfw@impactupgrade.com";
        envConfig.salesforce.password = "AMC!huc*qbc@gqt8vrxdiCpe56TyHWdMBL5eqDPHlEA";
        envConfig.salesforce.enhancedRecurringDonations = true;
        envConfig.salesforce.npsp = true;
        return envConfig;
      }
    };

    new DonorPerfectToSalesforce(env).migrate();
  }

  private final Environment env;

  public DonorPerfectToSalesforce(Environment env) {
    this.env = env;
  }

  public void migrate() throws Exception {
    SfdcClient sfdcClient = env.sfdcClient();

    String constituentFile = "/home/brmeyer/Downloads/donmrg-constituents.csv";
    String donationFile = "/home/brmeyer/Downloads/donmrg-gift-transactions.csv";

    String HOUSEHOLD_RECORD_TYPE_ID = "0128a00000197xwAAA";
    String ORGANIZATION_RECORD_TYPE_ID = "0128a00000197xxAAA";

    Map<String, String> constituentIdToAccountId = new HashMap<>();
    Map<String, String> constituentIdToContactId = new HashMap<>();
    Map<String, Boolean> constituentIdToIsBusiness = new HashMap<>();
    Map<String, String> transactionNumberToOppId = new HashMap<>();

    Map<String, String> existingBusinessAccountIdsByName = new HashMap<>();
    List<SObject> accounts = sfdcClient.queryListAutoPaged("SELECT Id, DP_ID__c, RecordTypeId, Name, BillingStreet FROM Account WHERE DP_ID__c!=NULL");
    for (SObject account : accounts) {
      constituentIdToAccountId.put((String) account.getField("DP_ID__c"), account.getId());
      // TODO: verify existing record types are correct
      boolean isBusiness = account.getField("RecordTypeId").equals(ORGANIZATION_RECORD_TYPE_ID);
      constituentIdToIsBusiness.put((String) account.getField("DP_ID__c"), isBusiness);
      if (isBusiness) {
        existingBusinessAccountIdsByName.put((String) account.getField("Name"), account.getId());
      }
    }

    Map<String, String> existingContactIdsByEmail = new HashMap<>();
    List<SObject> contacts = sfdcClient.queryListAutoPaged("SELECT Id, AccountId, DP_ID__c, FirstName, LastName, Email, Account.Name, Account.BillingStreet FROM Contact WHERE Email!=''");
    for (SObject contact : contacts) {
      String emailKey = contact.getField("Email").toString().toLowerCase(Locale.ROOT);
      if (!existingContactIdsByEmail.containsKey(emailKey)) {
        existingContactIdsByEmail.put(emailKey, contact.getId());
      }

      String dpId = (String) contact.getField("DP_ID__c");
      if (!Strings.isNullOrEmpty(dpId)) {
        constituentIdToContactId.put(dpId, contact.getId());
        constituentIdToAccountId.put(dpId, (String) contact.getField("AccountId"));
      }
    }

    List<SObject> opportunities = sfdcClient.queryListAutoPaged("SELECT Id, DP_ID__c FROM Opportunity WHERE DP_ID__c!=NULL");
    for (SObject opportunity : opportunities) {
      transactionNumberToOppId.put((String) opportunity.getField("DP_ID__c"), opportunity.getId());
    }

    Map<String, String> campaignNameToId = sfdcClient.getCampaigns().stream()
        .collect(Collectors.toMap(c -> (String) c.getField("Name"), c -> c.getId(), (c1, c2) -> c1));

    List<Map<String, String>> constituentRows;
    try (InputStream is = new FileInputStream(constituentFile)) {
      constituentRows = Utils.getCsvData(is);
    }

    List<Map<String, String>> constituentRowsToBeInserted = new ArrayList<>();

    // There will be multiple rows per donor if they have multiple addresses. The old address will have
    // ADDRESS_TYPE_DESCR="Old Address" and/or ADDRESS_TYPE="OLDADD". We're opting to simply ignore rows with OLDADD.
    // We then ensure no other duplicate rows exist by-ID.

    Iterator<Map<String, String>> itr = constituentRows.iterator();
    Set<String> donorIds = new HashSet<>();
    while (itr.hasNext()) {
      Map<String, String> constituentRow = itr.next();
      if ("OLDADD".equalsIgnoreCase(constituentRow.get("ADDRESS_TYPE"))) {
        System.out.println("removing old address: " + constituentRow.get("ADDRESS"));
        itr.remove();
      } else {
        String donorId = constituentRow.get("DONOR_ID");
        if (donorIds.contains(donorId)) {
          // A small handful had additional "addresses" that appeared to be used for additional email addresses (which
          // unfortunately creates additional rows). But they all appear to be old, so I'm opting to drop them.
          System.out.println("duplicate donorId: " + donorId);
          itr.remove();
        } else {
          donorIds.add(donorId);
        }
      }
    }

    // For all non-individual donors:
    // - If first name + last name + org name, assume first name and last name are the contact and org name is the account.
    // - If last name + org name, trust the last name as the Account.
    // - If first name + last name, and no org name (rare), assume the last name as the account and ignore first name.
    // For individual donors:
    // - If first name + last name + org name, assume household account. But also create a bare account for the biz and an affiliation.
    // - Otherwise, standard contact + household.

    for (Map<String, String> constituentRow : constituentRows) {
      String donorId = constituentRow.get("DONOR_ID");
      String type = constituentRow.get("DONOR_TYPE_DESCR");

      boolean isBusiness = "Y".equalsIgnoreCase(constituentRow.get("ORG_REC"))
          || (!Strings.isNullOrEmpty(type) && !"Individual".equalsIgnoreCase(type));
      constituentIdToIsBusiness.put(donorId, isBusiness);

      String accountName = getAccountName(constituentRow);

      SObject sfdcAccount = new SObject("Account");

      sfdcAccount.setField("Name", accountName);
      sfdcAccount.setField("Description", constituentRow.get("NARRATIVE"));
      sfdcAccount.setField("npo02__Formal_Greeting__c", constituentRow.get("SALUTATION"));
      sfdcAccount.setField("npo02__Informal_Greeting__c", constituentRow.get("INFORMAL_SAL"));
      // TODO: create field
      sfdcAccount.setField("Attn__c", constituentRow.get("OPT_LINE"));

//      sfdcAccount.setField("Envelope_Name__c", constituentRow.get("EnvelopeName"));
//      sfdcAccount.setField("Type", constituentRow.get("Type"));

      String street = constituentRow.get("ADDRESS");
      if (!Strings.isNullOrEmpty(constituentRow.get("ADDRESS2"))) {
        street += ", " + constituentRow.get("ADDRESS2");
      }
      sfdcAccount.setField("BillingStreet", street);
      sfdcAccount.setField("BillingCity", constituentRow.get("CITY"));
      sfdcAccount.setField("BillingState", constituentRow.get("STATE"));
      sfdcAccount.setField("BillingPostalCode", constituentRow.get("ZIP"));
      sfdcAccount.setField("BillingCountry", constituentRow.get("COUNTRY"));

      if (isBusiness) {
        // TODO: create the field
        sfdcAccount.setField("Email__c", constituentRow.get("EMAIL"));
        sfdcAccount.setField("Phone", constituentRow.get("BUSINESS_PHONE"));
      }

      if (constituentIdToAccountId.containsKey(donorId)) {
        sfdcAccount.setId(constituentIdToAccountId.get(donorId));

        sfdcClient.batchUpdate(sfdcAccount);
        System.out.println("CONSTITUENT ACCOUNT UPDATE: " + accountName);
      } else {
        if (isBusiness) {
          sfdcAccount.setField("RecordTypeId", ORGANIZATION_RECORD_TYPE_ID);
        } else {
          sfdcAccount.setField("RecordTypeId", HOUSEHOLD_RECORD_TYPE_ID);
        }
        sfdcAccount.setField("DP_ID__c", donorId);

        constituentRowsToBeInserted.add(constituentRow);

        sfdcClient.batchInsert(sfdcAccount);
        System.out.println("CONSTITUENT ACCOUNT INSERT: " + accountName);
      }
    }

    SFDCPartnerAPIClient.BatchResults results = sfdcClient.batchFlush();
    if (!results.batchInsertResults().isEmpty()) {
      // Run through the loop again and gather the results.
      for (int i = 0; i < constituentRowsToBeInserted.size(); i++) {
        Map<String, String> constituentRow = constituentRowsToBeInserted.get(i);
        SaveResult result = results.batchInsertResults().get(i);
        if (result.isSuccess() && !Strings.isNullOrEmpty(result.getId())) {
          String sfdcAccountId = result.getId();
          String donorId = constituentRow.get("DONOR_ID");
          constituentIdToAccountId.put(donorId, sfdcAccountId);

          if (constituentIdToIsBusiness.getOrDefault(donorId, false)) {
            String accountName = getAccountName(constituentRow);
            existingBusinessAccountIdsByName.put(accountName, sfdcAccountId);
          }
        }
      }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // CONTACT
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    constituentRowsToBeInserted = new ArrayList<>();

    for (Map<String, String> constituentRow : constituentRows) {
      String donorId = constituentRow.get("DONOR_ID");
      String email = constituentRow.get("EMAIL");

      boolean isBusiness = constituentIdToIsBusiness.getOrDefault(donorId, false);

      if (!Strings.isNullOrEmpty(constituentRow.get("FIRST_NAME"))) {
        SObject sfdcContact = new SObject("Contact");

        sfdcContact.setField("FirstName", constituentRow.get("FIRST_NAME"));
        sfdcContact.setField("LastName", constituentRow.get("LAST_NAME"));
        // TODO: create field
        sfdcContact.setField("Middle_Name__c", constituentRow.get("MIDDLE_NAME"));
        sfdcContact.setField("Title", constituentRow.get("TITLE"));
        // TODO: create the field
        sfdcContact.setField("Employer__c", constituentRow.get("EMPLOYER"));
        sfdcContact.setField("Description", constituentRow.get("NARRATIVE"));

        if ("Y".equalsIgnoreCase(constituentRow.get("DECEASED"))) {
          sfdcContact.setField("npsp__Deceased__c", true);
          sfdcContact.setField("npsp__Do_Not_Contact__c", true);
        }
        if ("Y".equalsIgnoreCase(constituentRow.get("NOCALL"))) {
          sfdcContact.setField("DoNotCall", true);
        }
        if ("Y".equalsIgnoreCase(constituentRow.get("NO_EMAIL"))) {
          sfdcContact.setField("HasOptedOutOfEmail", true);
        }
        if ("Y".equalsIgnoreCase(constituentRow.get("NOMAIL"))) {
          // TODO: create field
          sfdcContact.setField("Do_Not_Mail__c", true);
        }

        sfdcContact.setField("npe01__HomeEmail__c", email);
        sfdcContact.setField("npe01__Preferred_Email__c", "Personal");
        sfdcContact.setField("MobilePhone", constituentRow.get("MOBILE_PHONE"));
        sfdcContact.setField("npe01__PreferredPhone__c", "Mobile");
        sfdcContact.setField("HomePhone", constituentRow.get("HOME_PHONE"));

        if (constituentIdToContactId.containsKey(donorId)) {
          sfdcContact.setId(constituentIdToContactId.get(donorId));

          sfdcClient.batchUpdate(sfdcContact);
          System.out.println("CONSTITUENT CONTACT UPDATE: " + constituentRow.get("FIRST_NAME") + " " + constituentRow.get("LAST_NAME"));
        } else if (existingContactIdsByEmail.containsKey(email)) {
          sfdcContact.setId(existingContactIdsByEmail.get(email));
          sfdcClient.batchUpdate(sfdcContact);
          System.out.println("CONSTITUENT CONTACT UPDATE BY EMAIL: " + constituentRow.get("FIRST_NAME") + " " + constituentRow.get("LAST_NAME"));
        } else {
          sfdcContact.setField("DP_ID__c", donorId);

          // If it was a biz, the account will be the biz itself. Allow the inserted contact to create its own
          // household, then create an affiliation with the biz account afterward.
          if (isBusiness) {
            String sfdcContactId = sfdcClient.insert(sfdcContact).getId();
            constituentIdToContactId.put(donorId, sfdcContactId);

            insertAffiliation(sfdcContactId, constituentIdToAccountId.get(donorId), sfdcClient);
          } else {
            sfdcContact.setField("AccountId", constituentIdToAccountId.get(donorId));
            constituentRowsToBeInserted.add(constituentRow);

            // If this was an individual donor, but ORG_NAME was defined, it's pointing to their employer or an org
            // they represent. Allow the donor to have a household (above), but create a bare account for the ORG_NAME
            // and create an affiliation.
            String orgName = constituentRow.get("ORG_NAME");
            if (!Strings.isNullOrEmpty(orgName) && !existingBusinessAccountIdsByName.containsKey(orgName)) {
              String sfdcContactId = sfdcClient.insert(sfdcContact).getId();

              SObject sfdcAccount = new SObject("Account");
              sfdcAccount.setField("Name", orgName);
              String sfdcAccountId = sfdcClient.insert(sfdcAccount).getId();
              existingBusinessAccountIdsByName.put(orgName, sfdcAccountId);
              insertAffiliation(sfdcContactId, constituentIdToAccountId.get(donorId), sfdcClient);
            } else {
              sfdcClient.batchInsert(sfdcContact);
            }
          }

          System.out.println("CONSTITUENT CONTACT INSERT: " + constituentRow.get("FIRST_NAME") + " " + constituentRow.get("LAST_NAME"));
        }
      }
    }

    results = sfdcClient.batchFlush();
    if (!results.batchInsertResults().isEmpty()) {
      // Run through the loop again and gather the results.
      for (int i = 0; i < constituentRowsToBeInserted.size(); i++) {
        SaveResult result = results.batchInsertResults().get(i);
        if (result.isSuccess() && !Strings.isNullOrEmpty(result.getId())) {
          Map<String, String> constituentRow = constituentRowsToBeInserted.get(i);
          String sfdcContactId = result.getId();

          constituentIdToContactId.put(constituentRow.get("DONOR_ID"), sfdcContactId);
        }
      }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // DONATIONS
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // Then loop over all Donations, combine them with the Transactions data, and insert Opportunities in SFDC

//    List<Map<String, String>> donationRows;
//    try (InputStream is = new FileInputStream(donationFile)) {
//      donationRows = Utils.getCsvData(is);
//    }
//
//    for (Map<String, String> donationRow : donationRows) {
//      processOpportunity(donationRow, constituentIdToAccountId,
//          constituentIdToContactId, transactionNumberToOppId, campaignNameToId, sfdcClient);
//    }
//
//    sfdcClient.batchFlush();
  }

  private String getAccountName(Map<String, String> constituentRow) {
    String type = constituentRow.get("DONOR_TYPE_DESCR");
    boolean isBusiness = !Strings.isNullOrEmpty(type) && !"Individual".equalsIgnoreCase(type);
    String firstName = constituentRow.get("FIRST_NAME");
    String lastName = constituentRow.get("LAST_NAME");
    String orgName = constituentRow.get("ORG_NAME");

    if (isBusiness) {
      if (!Strings.isNullOrEmpty(orgName) && !Strings.isNullOrEmpty(firstName)) {
        return orgName;
      } else {
        // Biz only, no contact. Contact will be skipped since it has no first name.
        return lastName;
      }
    } else {
      return firstName + " " + lastName;
    }
  }

  private void insertAffiliation(String contactId, String accountId, SfdcClient sfdcClient) throws InterruptedException {
    SObject affiliation = new SObject("npe5__Affiliation__c");
    affiliation.setField("npe5__Contact__c", contactId);
    affiliation.setField("npe5__Organization__c", accountId);
    affiliation.setField("npe5__Status__c", "Current");
//    affiliation.setField("npe5__Role__c", role);
    sfdcClient.batchInsert(affiliation);
  }

//  private void processOpportunity(
//      Map<String, String> donationRow,
//      Map<String, String> constituentIdToAccountId,
//      Map<String, String> constituentIdToContactId,
//      Map<String, String> transactionNumberToOppId,
//      Map<String, String> campaignNameToId,
//      SfdcClient sfdcClient
//  ) throws InterruptedException, ParseException {
//    String transactionNumber = donationRow.get("TransactionNumber");
//
//    SObject sfdcOpportunity = new SObject("Opportunity");
//    sfdcOpportunity.setField("npsp__Acknowledgment_Status__c", donationRow.get("AcknowledgementStatus"));
//    sfdcOpportunity.setField("Amount", donationRow.get("Amount"));
//    sfdcOpportunity.setField("Non_Deductible__c", donationRow.get("NonDeductible"));
//    sfdcOpportunity.setField("Description", donationRow.get("Note"));
//    sfdcOpportunity.setField("npsp__Honoree_Name__c", donationRow.get("TributeName"));
//    sfdcOpportunity.setField("npsp__Tribute_Type__c", donationRow.get("TributeType"));
//    Date d = new SimpleDateFormat("MM/dd/yyyy").parse(transactionRow.get("Date"));
//    sfdcOpportunity.setField("CloseDate", d);
//    sfdcOpportunity.setField("Check_Number__c", transactionRow.get("CheckNumber"));
//    sfdcOpportunity.setField("npsp__In_Kind_Description__c", transactionRow.get("InKindDescription"));
//    sfdcOpportunity.setField("In_Kind_Market_Value__c", transactionRow.get("InKindMarketValue"));
//    sfdcOpportunity.setField("npsp__In_Kind_Type__c", transactionRow.get("InKindType"));
//    sfdcOpportunity.setField("Payment_Gateway_Name__c", transactionRow.get("Method"));
//    sfdcOpportunity.setField("Fund__c", donationRow.get("FundName"));
//    // We have not yet seen Bloomerang with a notion of "failed attempt" transactions
//    sfdcOpportunity.setField("StageName", "Closed Won");
//    sfdcOpportunity.setField("Payment_Gateway_Name__c", transactionRow.get("Custom: Payment Gateway Name"));
//    sfdcOpportunity.setField("Payment_Gateway_Customer_ID__c", transactionRow.get("Custom: Payment Gateway Customer ID"));
//    sfdcOpportunity.setField("Payment_Gateway_Transaction_ID__c", transactionRow.get("Custom: Payment Gateway Transaction ID"));
//
//    String campaignId = null;
//    String campaign = donationRow.get("CampaignName");
//    if (!Strings.isNullOrEmpty(campaign)) {
//      if (!campaignNameToId.containsKey(campaign)) {
//        SObject sObject = new SObject("Campaign");
//        sObject.setField("Name", campaign);
//        String id = sfdcClient.insert(sObject).getId();
//        campaignNameToId.put(campaign, id);
//        campaignId = id;
//      } else {
//        campaignId = campaignNameToId.get(campaign);
//      }
//    }
//    String appeal = donationRow.get("AppealName");
//    if (!Strings.isNullOrEmpty(appeal)) {
//      if (!campaignNameToId.containsKey(appeal)) {
//        SObject sObject = new SObject("Campaign");
//        sObject.setField("Name", appeal);
//        sObject.setField("ParentId", campaignNameToId.get(campaign));
//        String id = sfdcClient.insert(sObject).getId();
//        campaignNameToId.put(appeal, id);
//        campaignId = id;
//      } else {
//        campaignId = campaignNameToId.get(appeal);
//      }
//    }
//    sfdcOpportunity.setField("CampaignId", campaignId);
//
//    if (transactionNumberToOppId.containsKey(transactionNumber)) {
//      sfdcOpportunity.setId(transactionNumberToOppId.get(transactionNumber));
//      sfdcClient.batchUpdate(sfdcOpportunity);
//    } else {
//      String accountNumber = transactionRow.get("AccountNumber");
//
//      sfdcOpportunity.setField("DP_ID__c", transactionNumber);
//      sfdcOpportunity.setField("AccountId", constituentIdToAccountId.get(accountNumber));
//      sfdcOpportunity.setField("ContactId", constituentIdToContactId.get(accountNumber));
//
//      String referenceDesignationNumber = donationRow.get("ReferenceDesignationNumber");
//      if (!Strings.isNullOrEmpty(referenceDesignationNumber)) {
//        sfdcOpportunity.setField("Npe03__Recurring_Donation__c", referenceDesignationNumberToRdId.get(referenceDesignationNumber));
//      }
//
//      String donationType;
//      if (!Strings.isNullOrEmpty(referenceDesignationNumber)) {
//        donationType = "Recurring Donation";
//      } else {
//        donationType = "Donation";
//      }
//      String donationDate = transactionRow.get("Date").split(" ")[0];
//      if (!Strings.isNullOrEmpty(donationRow.get("FundName"))) {
//        sfdcOpportunity.setField("Name", donationType + " " + donationDate + " " + donationRow.get("FundName"));
//      } else {
//        sfdcOpportunity.setField("Name", donationType + " " + donationDate);
//      }
//
//      // TODO: This is resulting in many locked-entity retries, likely due to opportunities being grouped together
//      //  by constituent.
////    sfdcClient.batchInsert(sfdcOpportunity);
//      sfdcClient.insert(sfdcOpportunity);
//    }
//  }
}
