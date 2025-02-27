/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.sforce.soap.partner.sobject.SObject;

import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DonorPerfectToSalesforce {

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
        envConfig.salesforce.enhancedRecurringDonations = true;
        envConfig.salesforce.npsp = true;
        envConfig.timezoneId = "America/Indiana/Indianapolis";
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
    String constituentFile = "/home/brmeyer/Downloads/donmrg-constituents.csv";
    String donationFile = "/home/brmeyer/Downloads/donmrg-gift-transactions.csv";

    List<Map<String, String>> constituentRows;
    try (InputStream is = new FileInputStream(constituentFile)) {
      constituentRows = Utils.getCsvData(is);
    }


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

    List<Map<String, String>> rows = new ArrayList<>();

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // DONORS
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // For all non-individual donors:
    // - If first name + last name + org name, assume first name and last name are the contact and org name is the account.
    // - If last name + org name, trust the last name as the Account.
    // - If first name + last name, and no org name (rare), assume the last name as the account and ignore first name.
    // For individual donors:
    // - If first name + last name + org name, assume household account. But also create a bare account for the biz and an affiliation.
    // - Otherwise, standard contact + household.

    for (Map<String, String> constituentRow : constituentRows) {
      Map<String, String> row = new HashMap<>();
      rows.add(row);

      String donorId = constituentRow.get("DONOR_ID");
      String type = constituentRow.get("DONOR_TYPE_DESCR");

      boolean hasContact = !Strings.isNullOrEmpty(constituentRow.get("FIRST_NAME"));
      boolean isBusiness = "Y".equalsIgnoreCase(constituentRow.get("ORG_REC"))
          || (!Strings.isNullOrEmpty(type) && !"Individual".equalsIgnoreCase(type));
      String accountPrefix = isBusiness && hasContact ? "Organization 1" : "Account";

      String accountName = getAccountName(constituentRow);

      row.put(accountPrefix + " ExtRef DP_ID__c", donorId);
      row.put(accountPrefix + " Description", constituentRow.get("NARRATIVE"));
//      row.put(accountPrefix + " Custom npo02__Formal_Greeting__c", constituentRow.get("SALUTATION"));
//      row.put(accountPrefix + " Custom npo02__Informal_Greeting__c", constituentRow.get("INFORMAL_SAL"));
      row.put(accountPrefix + " Custom Attn__c", constituentRow.get("OPT_LINE"));

      String street = constituentRow.get("ADDRESS");
      if (!Strings.isNullOrEmpty(constituentRow.get("ADDRESS2"))) {
        street += ", " + constituentRow.get("ADDRESS2");
      }
      row.put(accountPrefix + " Billing Street", street);
      row.put(accountPrefix + " Billing City", constituentRow.get("CITY"));
      row.put(accountPrefix + " Billing State", constituentRow.get("STATE"));
      row.put(accountPrefix + " Billing Postal Code", constituentRow.get("ZIP"));
      row.put(accountPrefix + " Billing Country", constituentRow.get("COUNTRY"));

      if ("Y".equalsIgnoreCase(constituentRow.get("NOMAIL"))) {
        row.put(accountPrefix + " Custom Do_Not_Mail__c", "true");
      }

      if (isBusiness) {
        row.put(accountPrefix + " Record Type Name", "Organization");
        row.put(accountPrefix + " Name", accountName);
        row.put(accountPrefix + " Email", constituentRow.get("EMAIL"));
        row.put(accountPrefix + " Phone", constituentRow.get("BUSINESS_PHONE"));
      } else {
        row.put(accountPrefix + " Record Type Name", "Household Account");
      }

      if (hasContact) {
        row.put("Contact ExtRef DP_ID__c", donorId);
        row.put("Contact First Name", constituentRow.get("FIRST_NAME"));
        row.put("Contact Last Name", constituentRow.get("LAST_NAME"));
        row.put("Contact Custom Middle_Name__c", constituentRow.get("MIDDLE_NAME"));
        row.put("Contact Title", constituentRow.get("TITLE"));
        row.put("Contact Custom Employer__c", constituentRow.get("EMPLOYER"));
        row.put("Contact Description", constituentRow.get("NARRATIVE"));

        if ("Y".equalsIgnoreCase(constituentRow.get("DECEASED"))) {
          row.put("Contact Custom npsp__Deceased__c", "true");
          row.put("Contact Custom Do_Not_Contact__c", "true");
        }
        if ("Y".equalsIgnoreCase(constituentRow.get("NOCALL"))) {
          row.put("Contact Custom DoNotCall", "true");
        }
        if ("Y".equalsIgnoreCase(constituentRow.get("NO_EMAIL"))) {
          row.put("Contact Email Opt In", "true");
        }

        row.put("Contact Personal Email", constituentRow.get("EMAIL"));
        row.put("Contact Preferred Email", "Personal");
        row.put("Contact Mobile Phone", constituentRow.get("MOBILE_PHONE"));
        row.put("Contact Preferred Phone", "Mobile");
        row.put("Contact Home Phone", constituentRow.get("HOME_PHONE"));

        // If it was a biz, the account will be the biz itself. Allow the inserted contact to create its own
        // household, then create an affiliation with the biz account afterward.
        // If this was an individual donor, but ORG_NAME was defined, it's pointing to their employer or an org
        // they represent. Allow the donor to have a household (above), but create a bare account for the ORG_NAME
        // and create an affiliation.
        if (!isBusiness) {
          String orgName = constituentRow.get("ORG_NAME");
          if (!Strings.isNullOrEmpty(orgName)) {
            row.put("Organization 1 Name", orgName);
          }
        }
      }
    }

    List<CrmImportEvent> importEvents = CrmImportEvent.fromGeneric(rows, env);
    env.primaryCrmService().processBulkImport(importEvents);

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // DONATIONS
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    SfdcClient sfdcClient = env.sfdcClient();

    Set<String> seenDonorIds = new HashSet<>();
    sfdcClient.queryListAutoPaged("SELECT DP_ID__c FROM Account WHERE DP_ID__c!=''").forEach(a -> seenDonorIds.add(a.getField("DP_ID__c").toString()));
    sfdcClient.queryListAutoPaged("SELECT DP_ID__c FROM Contact WHERE DP_ID__c!=''").forEach(c -> seenDonorIds.add(c.getField("DP_ID__c").toString()));

    List<SObject> existingOpps = sfdcClient.queryListAutoPaged("SELECT Id, DP_ID__c, CloseDate, Amount, Account.DP_ID__c, npsp__Primary_Contact__r.DP_ID__c FROM Opportunity WHERE RecordType.Name='Donation'");
    Set<String> seenDonationIds = new HashSet<>();
    Set<String> seenDonations = new HashSet<>();
    for (SObject existingOpp : existingOpps) {
      String oppDpId = (String) existingOpp.getField("DP_ID__c");
      String accountDpId = (String) existingOpp.getChild("Account").getField("DP_ID__c");
      String contactDpId = (String) existingOpp.getChild("npsp__Primary_Contact__r").getField("DP_ID__c");
      String amount = String.format("$%.2f", Double.parseDouble(existingOpp.getField("Amount").toString()));
      String date = new SimpleDateFormat("M/d/yyyy").format(new SimpleDateFormat("yyyy-MM-dd").parse(existingOpp.getField("CloseDate").toString()));
      if (!Strings.isNullOrEmpty(oppDpId)) {
        seenDonationIds.add(oppDpId);
      }
      if (!Strings.isNullOrEmpty(accountDpId)) {
        seenDonations.add(accountDpId + "_" + date + "_" + amount);
      }
      if (!Strings.isNullOrEmpty(contactDpId)) {
        seenDonations.add(contactDpId + "_" + date + "_" + amount);
      }
    }

    // Then loop over all Donations, combine them with the Transactions data, and insert Opportunities in SFDC

    List<Map<String, String>> donationRows;
    try (InputStream is = new FileInputStream(donationFile)) {
      donationRows = Utils.getCsvData(is);
    }

    rows.clear();

    for (Map<String, String> donationRow : donationRows) {
      String giftId = donationRow.get("GIFT_ID");
      String donorId = donationRow.get("DONOR_ID");
      String date = donationRow.get("GIFT_DATE");
      String amount = donationRow.get("AMOUNT");

      if (seenDonationIds.contains(giftId)) {
        System.out.println("gift " + giftId + " " + date + " already exists");
        continue;
      }

      if (!seenDonorIds.contains(donorId)) {
        System.out.println("missing donor " + donorId);
        continue;
      }

//      String seenDpDonationKey = donorId + "_" + date + "_" + amount;
//      if (seenDonations.contains(seenDpDonationKey)) {
//        System.out.println("gift " + giftId + " " + date + " already exists, based on donor+date+amount");
//        continue;
//      }

      System.out.println("importing gift " + giftId + " " + date);

      Map<String, String> row = new HashMap<>();
      rows.add(row);

      row.put("Opportunity ExtRef DP_ID__c", giftId);
      row.put("Opportunity Amount", amount);
      row.put("Opportunity Description", donationRow.get("GIFT_NARRATIVE"));
      row.put("Opportunity Date mm/dd/yyyy", date);
      row.put("Opportunity Custom Reference__c", donationRow.get("REFERENCE"));
      row.put("Opportunity Custom Payment_Gateway_Name__c", donationRow.get("GIFT_TYPE_DESCR"));
      // We have not yet seen DP with a notion of "failed attempt" transactions
      row.put("Opportunity Stage Name", "Closed Won");
      row.put("Opportunity Custom GL_Code__c", donationRow.get("GL_CODE_DESCR"));
      row.put("Opportunity Campaign Name", donationRow.get("SOLICIT_CODE_DESCR"));
      row.put("Account ExtRef DP_ID__c", donorId);
      row.put("Contact ExtRef DP_ID__c", donorId);
    }

    importEvents = CrmImportEvent.fromGeneric(rows, env);
    env.primaryCrmService().processBulkImport(importEvents);
  }

  private String getAccountName(Map<String, String> constituentRow) {
    String type = constituentRow.get("DONOR_TYPE_DESCR");
    boolean isBusiness = !Strings.isNullOrEmpty(type) && !"Individual".equalsIgnoreCase(type);
    String firstName = constituentRow.get("FIRST_NAME");
    String lastName = constituentRow.get("LAST_NAME");
    String orgName = constituentRow.get("ORG_NAME");

    if (isBusiness) {
      if (!Strings.isNullOrEmpty(orgName) && !Strings.isNullOrEmpty(firstName)) {
        return orgName.trim();
      } else {
        // Biz only, no contact. Contact will be skipped since it has no first name.
        return lastName.trim();
      }
    } else {
      return firstName.trim() + " " + lastName.trim();
    }
  }
}
