/*
 * Copyright (c) 2022 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.impactupgrade.integration.sfdc.SFDCPartnerAPIClient;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.sobject.SObject;

import java.io.FileInputStream;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// TODO: Shift to using the Bloomerang API, instead of exports.
// TODO: Rework this to use Bulk Upsert, like the Raiser's Edge migration.
public class BloomerangToSalesforce {

  public static void main(String[] args) throws Exception {
    Environment env = new Environment() {
      @Override
      public EnvironmentConfig getConfig() {
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.crmPrimary = "salesforce";
        envConfig.salesforce.sandbox = false;
        envConfig.salesforce.url = "communityone.my.salesforce.com";
        envConfig.salesforce.username = "team+c1@impactupgrade.com";
        envConfig.salesforce.password = "a7Ty*wsaz8n9ETdLcNixtqtdI0WozJNIZIKlUhNuJ";
        envConfig.salesforce.enhancedRecurringDonations = true;
        return envConfig;
      }
    };

    new BloomerangToSalesforce(env).migrate();
  }

  private final Environment env;

  public BloomerangToSalesforce(Environment env) {
    this.env = env;
  }

  public void migrate() throws Exception {
    SfdcClient sfdcClient = new SfdcClient(env);

    // TODO: Bloomerang exports a ZIP with a few dozen CSV files. We should accept that ZIP and expand it on our own.
    String addressFile = "/home/brmeyer/Downloads/DataExport-2024-04-17/Addresses.csv";
    String constituentFile = "/home/brmeyer/Downloads/DataExport-2024-04-17/Constituents.csv";
    String donationFile = "/home/brmeyer/Downloads/DataExport-2024-04-17/Donations.csv";
    String emailFile = "/home/brmeyer/Downloads/DataExport-2024-04-17/Emails.csv";
    String householdFile = "/home/brmeyer/Downloads/DataExport-2024-04-17/Households.csv";
    String phoneFile = "/home/brmeyer/Downloads/DataExport-2024-04-17/Phones.csv";
    String recurringDonationFile = "/home/brmeyer/Downloads/DataExport-2024-04-17/RecurringDonations.csv";
    String recurringDonationPaymentFile = "/home/brmeyer/Downloads/DataExport-2024-04-17/RecurringDonationPayments.csv";
    String transactionFile = "/home/brmeyer/Downloads/DataExport-2024-04-17/Transactions.csv";

    // TODO: pull to config
    String HOUSEHOLD_RECORD_TYPE_ID = "0128c000001xDX2AAM";
    String ORGANIZATION_RECORD_TYPE_ID = "0128c000001xDX3AAM";

    // First, we need to go through multiple sheets with lookup data we need. Hold them in memory using simple Maps.

    Multimap<String, Map<String, String>> addressRowsByAccountNumber = ArrayListMultimap.create();
    try (InputStream is = new FileInputStream(addressFile)) {
      List<Map<String, String>> addressRows = Utils.getCsvData(is);
      for (Map<String, String> addressRow : addressRows) {
        addressRowsByAccountNumber.put(addressRow.get("AccountNumber"), addressRow);
      }
    }

    Multimap<String, Map<String, String>> emailRowsByAccountNumber = ArrayListMultimap.create();
    try (InputStream is = new FileInputStream(emailFile)) {
      List<Map<String, String>> emailRows = Utils.getCsvData(is);
      for (Map<String, String> emailRow : emailRows) {
        emailRowsByAccountNumber.put(emailRow.get("AccountNumber"), emailRow);
      }
    }

    Multimap<String, Map<String, String>> phoneRowsByAccountNumber = ArrayListMultimap.create();
    try (InputStream is = new FileInputStream(phoneFile)) {
      List<Map<String, String>> phoneRows = Utils.getCsvData(is);
      for (Map<String, String> phoneRow : phoneRows) {
        phoneRowsByAccountNumber.put(phoneRow.get("AccountNumber"), phoneRow);
      }
    }

    HashMap<String, Map<String, String>> transactionRowsByTransactionNumber = new HashMap<>();
    try (InputStream is = new FileInputStream(transactionFile)) {
      List<Map<String, String>> transactionRows = Utils.getCsvData(is);
      for (Map<String, String> transactionRow : transactionRows) {
        transactionRowsByTransactionNumber.put(transactionRow.get("TransactionNumber"), transactionRow);
      }
    }

    // We also need Maps to keep track of what we've inserted/updated, needed as references later on.
    Map<String, String> constituentIdToAccountId = new HashMap<>();
    Map<String, String> constituentIdToContactId = new HashMap<>();
    Map<String, Boolean> constituentIdToIsBusiness = new HashMap<>();
    Map<String, String> referenceDesignationNumberToRdId = new HashMap<>();

    // Then finally, grab what already exists in SFDC so we can know to update them.

    Map<String, SObject> existingAccountsByBloomerangIds = new HashMap<>();
    List<SObject> accounts = sfdcClient.queryListAutoPaged("SELECT Id, Bloomerang_ID__c, RecordTypeId, Name, BillingStreet FROM Account WHERE Bloomerang_ID__c!=''");
    for (SObject account : accounts) {
      existingAccountsByBloomerangIds.put((String) account.getField("Bloomerang_ID__c"), account);
      boolean isBusiness = account.getField("RecordTypeId").equals(ORGANIZATION_RECORD_TYPE_ID);
      constituentIdToIsBusiness.put((String) account.getField("Bloomerang_ID__c"), isBusiness);
    }

    Map<String, SObject> existingContactsByBloomerangIds = new HashMap<>();
    // TODO: Opting to avoid these for now. Bloomerang has
    //  separate constituents for the same person donating through their household AND a business, sharing the same
    //  email address, and in some cases they had already been merged in SFDC (single contact, one household, plus an Organization affiliation).
    //  Instead of accidentally overwriting the wrong account, we're instead allowing in duplicates that can later be manually merged.
//    Map<String, List<SObject>> existingContactsByNames = new HashMap<>();
//    Map<String, List<SObject>> existingContactsByEmails = new HashMap<>();
    List<SObject> contacts = sfdcClient.queryListAutoPaged("SELECT Id, AccountId, Bloomerang_ID__c, FirstName, LastName, Email, Account.Name, Account.BillingStreet FROM Contact WHERE Bloomerang_ID__c!=''");
    for (SObject contact : contacts) {
      existingContactsByBloomerangIds.put((String) contact.getField("Bloomerang_ID__c"), contact);

//      String nameKey = (contact.getField("FirstName") + " " + contact.getField("LastName")).toLowerCase(Locale.ROOT);
//      if (!existingContactsByNames.containsKey(nameKey)) {
//        existingContactsByNames.put(nameKey, new ArrayList<>());
//      }
//      existingContactsByNames.get(nameKey).add(contact);
//
//      if (!Strings.isNullOrEmpty((String) contact.getField("Email"))) {
//        String emailKey = contact.getField("Email").toString().toLowerCase(Locale.ROOT);
//        if (!existingContactsByEmails.containsKey(emailKey)) {
//          existingContactsByEmails.put(emailKey, new ArrayList<>());
//        }
//        existingContactsByEmails.get(emailKey).add(contact);
//      }
      constituentIdToAccountId.put((String) contact.getField("Bloomerang_ID__c"), contact.getField("AccountId").toString());
      constituentIdToContactId.put((String) contact.getField("Bloomerang_ID__c"), contact.getId());
    }

    Map<String, SObject> rdsByBloomerangIds = new HashMap<>();
    List<SObject> rds = sfdcClient.queryListAutoPaged("SELECT Id, Bloomerang_ID__c FROM Npe03__Recurring_Donation__c WHERE Bloomerang_ID__c!=''");
    for (SObject rd : rds) {
      rdsByBloomerangIds.put((String) rd.getField("Bloomerang_ID__c"), rd);
      referenceDesignationNumberToRdId.put((String) rd.getField("Bloomerang_ID__c"), rd.getId());
    }

    Map<String, SObject> opportunitiesByBloomerangIds = new HashMap<>();
    List<SObject> opportunities = sfdcClient.queryListAutoPaged("SELECT Id, Bloomerang_ID__c FROM Opportunity WHERE Bloomerang_ID__c!=''");
    for (SObject opportunity : opportunities) {
      opportunitiesByBloomerangIds.put((String) opportunity.getField("Bloomerang_ID__c"), opportunity);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // ACCOUNT
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // Bloomerang has a separate Household concept that we first need to deal with. Confusingly, a Constituent can be
    // an individual within a household, or a Constituent can be a BUSINESS account.

    List<Map<String, String>> householdRows;
    try (InputStream is = new FileInputStream(householdFile)) {
      householdRows = Utils.getCsvData(is);
    }

    List<Map<String, String>> householdRowsToBeInserted = new ArrayList<>();

    for (Map<String, String> householdRow : householdRows) {
      String accountNumber = householdRow.get("AccountNumber");

      SObject sfdcAccount = new SObject("Account");
      sfdcAccount.setField("Recognition_Name__c", householdRow.get("RecognitionName"));

      if (existingAccountsByBloomerangIds.containsKey(accountNumber)) {
        sfdcAccount.setId(existingAccountsByBloomerangIds.get(accountNumber).getId());

//        sfdcClient.batchUpdate(sfdcAccount);
        System.out.println("HOUSEHOLD UPDATE: " + householdRow.get("FullName"));
      } else {
        sfdcAccount.setField("Bloomerang_ID__c", accountNumber);
        sfdcAccount.setField("RecordTypeId", HOUSEHOLD_RECORD_TYPE_ID);
        sfdcAccount.setField("Name", householdRow.get("FullName"));
        sfdcAccount.setField("npo02__Formal_Greeting__c", householdRow.get("FormalName"));
        sfdcAccount.setField("npo02__Informal_Greeting__c", householdRow.get("InformalName"));

        householdRowsToBeInserted.add(householdRow);

//        sfdcClient.batchInsert(sfdcAccount);
        System.out.println("HOUSEHOLD INSERT: " + householdRow.get("FullName"));
      }
    }

    SFDCPartnerAPIClient.BatchResults results = sfdcClient.batchFlush();
    if (!results.batchInsertResults().isEmpty()) {
      // Run through the loop again and gather the results.
      for (int i = 0; i < householdRowsToBeInserted.size(); i++) {
        // TODO: Wouldn't this make some of the Bulk Upsert logic simpler in SfdcCrmService?
        SaveResult result = results.batchInsertResults().get(i);
        if (result.isSuccess() && !Strings.isNullOrEmpty(result.getId())) {
          Map<String, String> householdRow = householdRowsToBeInserted.get(i);
          String sfdcAccountId = result.getId();

          constituentIdToAccountId.put(householdRow.get("Head"), sfdcAccountId);
          if (!Strings.isNullOrEmpty(householdRow.get("Members"))) {
            // TODO: I'm assuming | is the separator, like other fields, but C1 doesn't actually have any member cells with more than one additional person.
            Arrays.stream(householdRow.get("Members").split("\\|")).forEach(m -> constituentIdToAccountId.put(m, sfdcAccountId));
          }
        }
      }
    }

    // Then, we loop over all Constituents, adding additional Accounts as needed.

    List<Map<String, String>> constituentRows;
    try (InputStream is = new FileInputStream(constituentFile)) {
      constituentRows = Utils.getCsvData(is);
    }

    List<Map<String, String>> constituentRowsToBeInserted = new ArrayList<>();

    for (Map<String, String> constituentRow : constituentRows) {
      String accountNumber = constituentRow.get("AccountNumber");

      boolean isBusiness = "Organization".equalsIgnoreCase(constituentRow.get("Type"));
      constituentIdToIsBusiness.put(accountNumber, isBusiness);

      if (constituentIdToAccountId.containsKey(accountNumber)) {
        // already have the household
      } else {
        SObject sfdcAccount = new SObject("Account");

        sfdcAccount.setField("Envelope_Name__c", constituentRow.get("EnvelopeName"));
        sfdcAccount.setField("Recognition_Name__c", constituentRow.get("RecognitionName"));
        sfdcAccount.setField("Website", constituentRow.get("Website"));
        sfdcAccount.setField("Type", constituentRow.get("Type"));
        // TODO: Do we need Owner IDs? Names in spreadsheet, IDs IN SF
        // sfdcAccount.setField("OwnerId", constituentRow.get("Custom: Staff Care & Cultivation"));

        for (Map<String, String> addressRow : addressRowsByAccountNumber.get(accountNumber)) {
          if ("Home".equalsIgnoreCase(addressRow.get("TypeName"))) {
            // TODO: This will overwrite the address each time if there are multiple addresses. May need to introduce
            //  secondary address fields...
            //          sfdcAccount.setField("npsp__MailingStreet__c", addressRow.get("Street"));
            //          sfdcAccount.setField("npsp__MailingCity__c", addressRow.get("City"));
            //          sfdcAccount.setField("npsp__MailingState__c", addressRow.get("State"));
            //          sfdcAccount.setField("npsp__MailingPostalCode__c", addressRow.get("PostalCode"));
            //          sfdcAccount.setField("npsp__MailingCountry__c", addressRow.get("Country"));

            if ("True".equalsIgnoreCase(addressRow.get("IsPrimary"))) {
              sfdcAccount.setField("BillingStreet", addressRow.get("Street"));
              sfdcAccount.setField("BillingCity", addressRow.get("City"));
              sfdcAccount.setField("BillingState", addressRow.get("State"));
              sfdcAccount.setField("BillingPostalCode", addressRow.get("PostalCode"));
              sfdcAccount.setField("BillingCountry", addressRow.get("Country"));
//              sfdcContact.setField("npe01__Primary_Address_Type__c", "Home");
            }
          } else if ("Work".equalsIgnoreCase(addressRow.get("TypeName"))) {
            // TODO: This will overwrite the address each time if there are multiple addresses. May need to introduce
            //  secondary address fields...
            //          sfdcAccount.setField("npsp__MailingStreet__c", addressRow.get("Street"));
            //          sfdcAccount.setField("npsp__MailingCity__c", addressRow.get("City"));
            //          sfdcAccount.setField("npsp__MailingState__c", addressRow.get("State"));
            //          sfdcAccount.setField("npsp__MailingPostalCode__c", addressRow.get("PostalCode"));
            //          sfdcAccount.setField("npsp__MailingCountry__c", addressRow.get("Country"));

            if ("True".equalsIgnoreCase(addressRow.get("IsPrimary"))) {
              sfdcAccount.setField("BillingStreet", addressRow.get("Street"));
              sfdcAccount.setField("BillingCity", addressRow.get("City"));
              sfdcAccount.setField("BillingState", addressRow.get("State"));
              sfdcAccount.setField("BillingPostalCode", addressRow.get("PostalCode"));
              sfdcAccount.setField("BillingCountry", addressRow.get("Country"));
//              sfdcContact.setField("npe01__Primary_Address_Type__c", "Work");
            }
          }
        }

        if (existingAccountsByBloomerangIds.containsKey(accountNumber)) {
          sfdcAccount.setId(existingAccountsByBloomerangIds.get(accountNumber).getId());

//          sfdcClient.batchUpdate(sfdcAccount);
          System.out.println("CONSTITUENT ACCOUNT UPDATE: " + constituentRow.get("FullName"));
        } else {
          if (isBusiness) {
            sfdcAccount.setField("RecordTypeId", ORGANIZATION_RECORD_TYPE_ID);
          } else {
            sfdcAccount.setField("RecordTypeId", HOUSEHOLD_RECORD_TYPE_ID);
          }
          sfdcAccount.setField("Bloomerang_ID__c", accountNumber);
          sfdcAccount.setField("Name", constituentRow.get("FullName"));
          sfdcAccount.setField("npo02__Formal_Greeting__c", constituentRow.get("FormalName"));
          sfdcAccount.setField("npo02__Informal_Greeting__c", constituentRow.get("InformalName"));

          constituentRowsToBeInserted.add(constituentRow);

//          sfdcClient.batchInsert(sfdcAccount);
          System.out.println("CONSTITUENT ACCOUNT INSERT: " + constituentRow.get("FullName"));
        }
      }
    }

    results = sfdcClient.batchFlush();
    if (!results.batchInsertResults().isEmpty()) {
      // Run through the loop again and gather the results.
      for (int i = 0; i < constituentRowsToBeInserted.size(); i++) {
        Map<String, String> constituentRow = constituentRowsToBeInserted.get(i);
        SaveResult result = results.batchInsertResults().get(i);
        if (result.isSuccess() && !Strings.isNullOrEmpty(result.getId())) {
          String sfdcAccountId = result.getId();

          constituentIdToAccountId.put(constituentRow.get("AccountNumber"), sfdcAccountId);
        }
      }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // CONTACT
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    constituentRowsToBeInserted = new ArrayList<>();

    for (Map<String, String> constituentRow : constituentRows) {
      String accountNumber = constituentRow.get("AccountNumber");

      boolean isBusiness = "Organization".equalsIgnoreCase(constituentRow.get("Type"));
      constituentIdToIsBusiness.put(accountNumber, isBusiness);

      if (!Strings.isNullOrEmpty(constituentRow.get("Last"))) {
        SObject sfdcContact = new SObject("Contact");

        sfdcContact.setField("FirstName", constituentRow.get("First"));
        sfdcContact.setField("LastName", constituentRow.get("Last"));
        sfdcContact.setField("Title", constituentRow.get("JobTitle"));
        if (!Strings.isNullOrEmpty(constituentRow.get("Birthdate"))) {
          Date d = new SimpleDateFormat("MM/dd/yyyy").parse(constituentRow.get("Birthdate"));
          sfdcContact.setField("Birthdate", d);
        }
        sfdcContact.setField("Gender__c", constituentRow.get("Gender"));
        sfdcContact.setField("Middle_Name__c", constituentRow.get("Middle"));
        sfdcContact.setField("Prefix__c", constituentRow.get("Prefix"));
        sfdcContact.setField("Preferred_Communication_Channel__c", constituentRow.get("CommunicationChannelPreferred"));
        // TODO: should we simply skip Inactive?
        sfdcContact.setField("Status__c", constituentRow.get("Status"));
        // TODO: affiliation?
        sfdcContact.setField("Employer__c", constituentRow.get("Employer"));
        sfdcContact.setField("Communication_Restrictions__c", constituentRow.get("CommunicationRestrictions").replaceAll("[|]+", ","));
        if ("OptedOut".equalsIgnoreCase(constituentRow.get("EmailInterestType"))) {
          sfdcContact.setField("HasOptedOutOfEmail", true);
        } else {
          sfdcContact.setField("Email_Opt_In__c", true);
        }
        sfdcContact.setField("Church_Affiliation__c", constituentRow.get("Custom: Church Affiliation"));

        // TODO: remaining contact fields
        /*
        Custom: Organization Name - 17 records with values
        Custom: Board Care & Cultivation
        */

        Set<String> emails = new HashSet<>();
        for (Map<String, String> emailRow : emailRowsByAccountNumber.get(accountNumber)) {
          if ("Home".equalsIgnoreCase(emailRow.get("TypeName"))) {
            sfdcContact.setField("npe01__HomeEmail__c", emailRow.get("Value"));
            emails.add(emailRow.get("Value").toLowerCase(Locale.ROOT));
            if ("True".equalsIgnoreCase(emailRow.get("IsPrimary"))) {
              sfdcContact.setField("npe01__Preferred_Email__c", "Personal");
            }
          } else if ("Work".equalsIgnoreCase(emailRow.get("TypeName"))) {
            sfdcContact.setField("npe01__WorkEmail__c", emailRow.get("Value"));
            emails.add(emailRow.get("Value").toLowerCase(Locale.ROOT));
            if ("True".equalsIgnoreCase(emailRow.get("IsPrimary"))) {
              sfdcContact.setField("npe01__Preferred_Email__c", "Work");
            }
          }
        }

        for (Map<String, String> phoneRow : phoneRowsByAccountNumber.get(accountNumber)) {
          if ("Home".equalsIgnoreCase(phoneRow.get("TypeName"))) {
            sfdcContact.setField("HomePhone", phoneRow.get("Number"));
            if ("True".equalsIgnoreCase(phoneRow.get("IsPrimary"))) {
              sfdcContact.setField("npe01__PreferredPhone__c", "Home");
            }
          } else if ("Work".equalsIgnoreCase(phoneRow.get("TypeName"))) {
            sfdcContact.setField("npe01__WorkPhone__c", phoneRow.get("Number"));
            if ("True".equalsIgnoreCase(phoneRow.get("IsPrimary"))) {
              sfdcContact.setField("npe01__PreferredPhone__c", "Work");
            }
          } else if ("Mobile".equalsIgnoreCase(phoneRow.get("TypeName"))) {
            sfdcContact.setField("MobilePhone", phoneRow.get("Number"));
            if ("True".equalsIgnoreCase(phoneRow.get("IsPrimary"))) {
              sfdcContact.setField("npe01__PreferredPhone__c", "Mobile");
            }
          }
        }

//        List<SObject> existingContactsByEmail = new ArrayList<>();
//        for (String email : emails) {
//          if (existingContactsByEmails.containsKey(email)) {
//            existingContactsByEmail.addAll(existingContactsByEmails.get(email));
//          }
//        }
//
//        List<SObject> existingContactsByName = new ArrayList<>();
//        String nameKey = (constituentRow.get("First") + " " + constituentRow.get("Last")).toLowerCase(Locale.ROOT);
//        if (existingContactsByNames.containsKey(nameKey)) {
//          existingContactsByName.addAll(existingContactsByNames.get(nameKey));
//        }

        if (existingContactsByBloomerangIds.containsKey(accountNumber)) {
          sfdcContact.setId(existingContactsByBloomerangIds.get(accountNumber).getId());

//          sfdcClient.batchUpdate(sfdcContact);
          System.out.println("CONSTITUENT CONTACT UPDATE: " + constituentRow.get("First") + " " + constituentRow.get("Last"));
//        } else if (!existingContactsByEmail.isEmpty()) {
//          if (existingContactsByEmail.size() > 1) {
//            System.out.println("MULTIPLE CONTACTS FOR EMAIL");
//            continue;
//          }
//
//          // TODO: need to update the account
//
//          sfdcContact.setId(existingContactsByEmail.get(0).getId());
//
////          sfdcClient.batchUpdate(sfdcContact);
//          System.out.println("CONSTITUENT CONTACT UPDATE BY EMAIL: " + constituentRow.get("First") + " " + constituentRow.get("Last"));
//        } else if (!existingContactsByName.isEmpty()) {
//          if (existingContactsByName.size() > 1) {
//            System.out.println("MULTIPLE CONTACTS FOR NAME");
//            continue;
//          }
//
//          // TODO: need to update the account
//
//          sfdcContact.setId(existingContactsByName.get(0).getId());
//
////          sfdcClient.batchUpdate(sfdcContact);
//          System.out.println("CONSTITUENT CONTACT UPDATE BY NAME: " + constituentRow.get("First") + " " + constituentRow.get("Last"));
        } else {
          // TODO: business contact household + affiliation with business?
          sfdcContact.setField("AccountId", constituentIdToAccountId.get(accountNumber));
          sfdcContact.setField("Bloomerang_ID__c", accountNumber);

          constituentRowsToBeInserted.add(constituentRow);

          sfdcClient.batchInsert(sfdcContact);
          System.out.println("CONSTITUENT CONTACT INSERT: " + constituentRow.get("First") + " " + constituentRow.get("Last"));
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

          constituentIdToContactId.put(constituentRow.get("AccountNumber"), sfdcContactId);
        }
      }
    }

    // TODO: Attachments, notes, and relationships not added

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // RECURRING DONATION
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // TODO: This starts using a lot of the logic from SfdcCrmService.setRecurringDonationFields. Better to wrap
    //  Bloomerang data with our CRM data model, then call the service directly?

    // Then loop over all Recurring Donations, combining them with Transaction data, and insert Recurring Donations in SFDC

    List<Map<String, String>> recurringDonationRows;
    try (InputStream is = new FileInputStream(recurringDonationFile)) {
      recurringDonationRows = Utils.getCsvData(is);
      recurringDonationRows = recurringDonationRows.stream().filter(r -> !rdsByBloomerangIds.containsKey(r.get("DesignationNumber"))).toList();
    }

    for (Map<String, String> recurringDonationRow : recurringDonationRows) {
      SObject sfdcRecurringDonation = new SObject("Npe03__Recurring_Donation__c");
      String transactionNumber = recurringDonationRow.get("TransactionNumber");
      String accountNumber = transactionRowsByTransactionNumber.get(transactionNumber).get("AccountNumber");

      sfdcRecurringDonation.setField("Bloomerang_ID__c", recurringDonationRow.get("DesignationNumber"));
      sfdcRecurringDonation.setField("npe03__Amount__c", recurringDonationRow.get("Amount"));
      if (!Strings.isNullOrEmpty(recurringDonationRow.get("StartDate"))) {
        Date d = new SimpleDateFormat("MM/dd/yyyy").parse(recurringDonationRow.get("StartDate"));
        sfdcRecurringDonation.setField("npe03__Date_Established__c", d);
      }
      // TODO: *might* need to set Npe03__Next_Payment_Date__c so that pledge generation does the right thing, but it should also be self-correcting as new donations come in
      sfdcRecurringDonation.setField("npsp__InstallmentFrequency__c", 1);
      // TODO
//      sfdcRecurringDonation.setField("npe03__Recurring_Donation_Campaign__c", recurringDonationRow.get("CampaignName"));
      // TODO: to eventually be replaced with campaigns
      sfdcRecurringDonation.setField("Fund__c", recurringDonationRow.get("FundName"));
      sfdcRecurringDonation.setField("Appeal__c", recurringDonationRow.get("AppealName"));
      sfdcRecurringDonation.setField("npe03__Installment_Period__c", recurringDonationRow.get("Frequency"));
      sfdcRecurringDonation.setField("Npe03__Schedule_Type__c", "Multiply By");

      if (!Strings.isNullOrEmpty(recurringDonationRow.get("EndDate"))) {
        Date d = new SimpleDateFormat("MM/dd/yyyy").parse(recurringDonationRow.get("EndDate"));
        sfdcRecurringDonation.setField("npsp__EndDate__c", d);
        sfdcRecurringDonation.setField("Npe03__Open_Ended_Status__c", "Closed");
      } else {
        sfdcRecurringDonation.setField("Npe03__Open_Ended_Status__c", "Open");
      }

      if (env.getConfig().salesforce.enhancedRecurringDonations) {
        // NPSP Enhanced RDs will not allow you to associate the RD directly with an Account if it's a household, instead
        // forcing us to use the contact.
        if (constituentIdToIsBusiness.get(accountNumber) != null && constituentIdToIsBusiness.get(accountNumber)) {
          sfdcRecurringDonation.setField("Npe03__Organization__c", constituentIdToAccountId.get(accountNumber));
        } else {
          sfdcRecurringDonation.setField("Npe03__Contact__c", constituentIdToContactId.get(accountNumber));
        }

        sfdcRecurringDonation.setField("npsp__RecurringType__c", "Open");
        // It's a picklist, so it has to be a string and not numeric :(
        LocalDate d = LocalDate.parse(recurringDonationRow.get("StartDate").split(" ")[0], DateTimeFormatter.ofPattern("M/d/yyyy"));
        sfdcRecurringDonation.setField("npsp__Day_of_Month__c", d.getDayOfMonth() + "");
      } else {
        // Legacy behavior was to always use the Account, regardless if it was a business or household. Stick with that
        // by default -- we have some orgs that depend on it.
        sfdcRecurringDonation.setField("Npe03__Organization__c", constituentIdToAccountId.get(accountNumber));
      }

      sfdcClient.batchInsert(sfdcRecurringDonation);
    }

    results = sfdcClient.batchFlush();
    // Run through the loop again and gather the results.
    for (int i = 0; i < recurringDonationRows.size(); i++) {
      SaveResult result = results.batchInsertResults().get(i);
      if (result.isSuccess() && !Strings.isNullOrEmpty(result.getId())) {
        Map<String, String> recurringDonationRow = recurringDonationRows.get(i);
        String sfdcRdId = result.getId();

        referenceDesignationNumberToRdId.put(recurringDonationRow.get("DesignationNumber"), sfdcRdId);
      }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // DONATIONS
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // Then loop over all Donations, combine them with the Transactions data, and insert Opportunities in SFDC

    List<Map<String, String>> donationRows;
    try (InputStream is = new FileInputStream(donationFile)) {
      donationRows = Utils.getCsvData(is);
      donationRows = donationRows.stream().filter(r -> !opportunitiesByBloomerangIds.containsKey(r.get("TransactionNumber"))).toList();
    }

    for (Map<String, String> donationRow : donationRows) {
      createOpportunity(donationRow, transactionRowsByTransactionNumber, constituentIdToAccountId,
          constituentIdToContactId, referenceDesignationNumberToRdId, sfdcClient);
    }

    //Then loop over all Recurring Donation Payments, combine them with the Transactions data, and insert Opportunities in SFDC (tied to the recurring donation ID)

    List<Map<String, String>> recurringDonationPaymentRows;
    try (InputStream is = new FileInputStream(recurringDonationPaymentFile)) {
      recurringDonationPaymentRows = Utils.getCsvData(is);
      recurringDonationPaymentRows = recurringDonationPaymentRows.stream().filter(r -> !opportunitiesByBloomerangIds.containsKey(r.get("TransactionNumber"))).toList();
    }

    for (Map<String, String> recurringDonationPaymentsRow : recurringDonationPaymentRows) {
      createOpportunity(recurringDonationPaymentsRow, transactionRowsByTransactionNumber, constituentIdToAccountId,
          constituentIdToContactId, referenceDesignationNumberToRdId, sfdcClient);
    }

    sfdcClient.batchFlush();

    // TODO: Pledges and PledgePayments could be combined into single Opportunities, but there's only a handful from 2014/2015
  }

  private void createOpportunity(
      Map<String, String> donationRow,
      HashMap<String, Map<String, String>> transactionRowsByTransactionNumber,
      Map<String, String> constituentIdToAccountId,
      Map<String, String> constituentIdToContactId,
      Map<String, String> referenceDesignationNumberToRdId,
      SfdcClient sfdcClient
  ) throws InterruptedException, ParseException {
    SObject sfdcOpportunity = new SObject("Opportunity");

    String transactionNumber = donationRow.get("TransactionNumber");
    Map<String, String> transactionRow = transactionRowsByTransactionNumber.get(transactionNumber);

    if (transactionRow == null) {
      return; // TODO: Some oddness in the sheets. Search "Grant 2017-CCL-011b" for an ex.
    }

    String accountNumber = transactionRow.get("AccountNumber");

    sfdcOpportunity.setField("npsp__Acknowledgment_Status__c", donationRow.get("AcknowledgementStatus"));
    sfdcOpportunity.setField("Amount", donationRow.get("Amount"));
    sfdcOpportunity.setField("Non_Deductible__c", donationRow.get("NonDeductable"));
    sfdcOpportunity.setField("Description", donationRow.get("Note"));
    sfdcOpportunity.setField("Bloomerang_ID__c", transactionNumber);
    sfdcOpportunity.setField("npsp__Honoree_Name__c", donationRow.get("TributeName"));
    sfdcOpportunity.setField("npsp__Tribute_Type__c", donationRow.get("TributeType"));
    Date d = new SimpleDateFormat("MM/dd/yyyy").parse(transactionRow.get("Date"));
    sfdcOpportunity.setField("CloseDate", d);
    sfdcOpportunity.setField("Check_Number__c", transactionRow.get("CheckNumber"));
    sfdcOpportunity.setField("npsp__In_Kind_Description__c", transactionRow.get("InKindDescription"));
    sfdcOpportunity.setField("In_Kind_Market_Value__c", transactionRow.get("InKindMarketValue"));
    sfdcOpportunity.setField("npsp__In_Kind_Type__c", transactionRow.get("InKindType"));
    sfdcOpportunity.setField("Payment_Gateway_Name__c", transactionRow.get("Method"));

    // TODO: to eventually be replaced with campaigns
    sfdcOpportunity.setField("Fund__c", donationRow.get("FundName"));
    sfdcOpportunity.setField("Appeal__c", donationRow.get("AppealName"));

    // We have not yet seen Bloomerang with a notion of "failed attempt" transactions
    sfdcOpportunity.setField("StageName", "Closed Won");

    sfdcOpportunity.setField("AccountId", constituentIdToAccountId.get(accountNumber));
    sfdcOpportunity.setField("ContactId", constituentIdToContactId.get(accountNumber));

    String referenceDesignationNumber = donationRow.get("ReferenceDesignationNumber");
    if (!Strings.isNullOrEmpty(referenceDesignationNumber)) {
      sfdcOpportunity.setField("Npe03__Recurring_Donation__c", referenceDesignationNumberToRdId.get(referenceDesignationNumber));
    }

    String donationType;
    if (!Strings.isNullOrEmpty(referenceDesignationNumber)) {
      donationType = "Recurring Donation";
    } else {
      donationType = "Donation";
    }
    String donationDate = transactionRow.get("Date").split(" ")[0];
    if (!Strings.isNullOrEmpty(donationRow.get("FundName"))) {
      sfdcOpportunity.setField("Name", donationType + " " + donationDate + " " + donationRow.get("FundName"));
    } else {
      sfdcOpportunity.setField("Name", donationType + " " + donationDate);
    }

    // TODO: This is resulting in many locked-entity retries, likely due to opportunities being grouped together
    //  by constituent.
//    sfdcClient.batchInsert(sfdcOpportunity);
    sfdcClient.insert(sfdcOpportunity);
  }
}
