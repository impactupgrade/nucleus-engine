/*
 * Copyright (c) 2022 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.bind.XmlObject;
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

public class RaisersEdgeToSalesforceHOUSEHOLDFIXES {

  public static void main(String[] args) throws Exception {
    Environment env = new Environment() {
      @Override
      public EnvironmentConfig getConfig() {
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.crmPrimary = "salesforce";
        envConfig.salesforce.sandbox = false;
        envConfig.salesforce.url = "concordialutheranhs.my.salesforce.com";
        envConfig.salesforce.username = "team+clhs@impactupgrade.com";
        envConfig.salesforce.password = "fZfy3hBEdYcwgbuCUdjQbz40Ui2KP8KalwXR51OYl9Vu";
        envConfig.salesforce.enhancedRecurringDonations = true;
        return envConfig;
      }
    };

    migrate(env);
  }

  private static void migrate(Environment env) throws Exception {
    // The sheets are so huge that the Apache framework we're using to read XLSX thinks it's malicious...
    IOUtils.setByteArrayMaxOverride(Integer.MAX_VALUE);

    // Mostly basing this on Bulk Upsert, but cheating in places and going straight to SFDC.
    SfdcClient sfdcClient = new SfdcClient(env);

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // CONSTITUENTS
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    File file = new File("/home/brmeyer/Downloads/Constituent+Spouse-v6-head-of-household.xlsx");
    InputStream inputStream = new FileInputStream(file);
    List<Map<String, String>> rows = Utils.getExcelData(inputStream);

    Map<String, SObject> existingAccountsByExtRef = new HashMap<>();
    sfdcClient.queryListAutoPaged("SELECT Id, Blackbaud_Constituent_ID__c FROM Account WHERE Blackbaud_Constituent_ID__c!=''")
        .forEach(c -> existingAccountsByExtRef.put(c.getField("Blackbaud_Constituent_ID__c").toString(), c));

    Map<String, SObject> existingContactsByExtRef = new HashMap<>();
    sfdcClient.queryListAutoPaged("SELECT Id, FirstName, LastName, Blackbaud_Constituent_ID__c, Account.Blackbaud_Constituent_ID__c FROM Contact WHERE Blackbaud_Constituent_ID__c!=''")
        .forEach(c -> existingContactsByExtRef.put(c.getField("Blackbaud_Constituent_ID__c").toString(), c));

    // TODO: There's unfortunately a wide, inconsistent mix of ways that households can be defined in RE data.
    //  Relationships between spouses will usually identify one as the head-of-household, so we first use those heads
    //  as the primary contacts of accounts. But then students/children do *not* have a parent relationship that's
    //  defined as HoH, so we can't globally rely on that to give away household groupings. We instead opt to use
    //  mailing addresses, since RE at least makes these consistent (at least for CLHS).
    List<String> headOfHouseholdIds = new ArrayList<>();
    Map<String, String> addressesToHouseholdId = new HashMap<>();
    Map<String, List<String>> addressesToConstituentIds = new HashMap<>();

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
          break;
        }
      }
    }

    // Make another pass to discover the heads of households' addresses.
    for (Map<String, String> row : rows) {
      String id = row.get("CnBio_ID");

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

      if (headOfHouseholdIds.contains(id)) {
        if (!Strings.isNullOrEmpty(address) && !addressesToHouseholdId.containsKey(address)) {
          addressesToHouseholdId.put(address, id);
        }
      }

      if (!addressesToConstituentIds.containsKey(address)) {
        addressesToConstituentIds.put(address, new ArrayList<>());
      }
      addressesToConstituentIds.get(address).add(id);
    }

//    for (Map<String, String> row : rows) {
//      String id = row.get("CnBio_ID");
//      if (headOfHouseholdIds.contains(id) && existingAccountsByExtRef.containsKey(id) && existingContactsByExtRef.containsKey(id)) {
//        SObject account = new SObject("Account");
//        account.setId(existingAccountsByExtRef.get(id).getId());
//        account.setField("npe01__One2OneContact__c", existingContactsByExtRef.get(id).getId());
//        sfdcClient.batchUpdate(account);
//      }
//    }
//    sfdcClient.batchFlush();

    for (Map<String, String> row : rows) {
      String id = row.get("CnBio_ID");

      if (!id.equalsIgnoreCase("47080")) {
        continue;
      }

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

        if (existingContactsByExtRef.get(id) != null) {
          if (Strings.isNullOrEmpty(address)) {

          } else if (addressesToHouseholdId.get(address) != null) {
//            if (existingAccountsByExtRef.get(addressesToHouseholdIds.get(address)) != null) {
//              SObject contact = new SObject("Contact");
//              contact.setId(existingContactsByExtRef.get(id).getId());
//              contact.setField("AccountId", existingAccountsByExtRef.get(addressesToHouseholdIds.get(address)).getId());
//              sfdcClient.batchUpdate(contact);
//            } else {
//              System.out.println("MISSING HOUSEHOLD " + id);
//            }
          } else if (addressesToConstituentIds.get(address) != null && addressesToConstituentIds.get(address).size() == 1) {
            // only one contact in the house, so they'll be primary already
          }
          else {
            System.out.println(id + " (" + row.get("CnBio_First_Name") + " " + row.get("CnBio_Last_Name")+ ")");
          }
        }
      }
    }
    sfdcClient.batchFlush();
  }

  private static void migrateConstituent(Map<String, String> row, String accountExtRef, List<Map<String, String>> primaryRows,
      Map<String, SObject> existingAccountsByExtRef, Map<String, SObject> existingContactsByExtRef) {

    // these eventually get combined, but separating them so that (as an ex) spouses can repurpose the account
    // data we've already parsed
    Map<String, String> accountData = new HashMap<>();
    Map<String, String> contactData = new HashMap<>();

    String contactExtRef = row.get("CnBio_ID");
    String firstName = row.get("CnBio_First_Name");
    String lastName = row.get("CnBio_Last_Name");

    SObject existingContact = existingContactsByExtRef.get(contactExtRef);
    if (existingContact != null) {
      XmlObject account = existingContact.getChild("Account");
      if (accountExtRef.equalsIgnoreCase((String) account.getField("Blackbaud_Constituent_ID__c"))) {
        // contact is already in the correct account, so return
        return;
      }
    } else {
      // contact didn't exist, typically due to shared email address
      return;
    }

    if (!existingAccountsByExtRef.containsKey(accountExtRef)) {
      // account didn't exist by extref, usually due to merges
      return;
    }

    if (!firstName.equalsIgnoreCase((String) existingContact.getField("FirstName"))) {
      // contact overwrite, typically due to shared email address
      return;
    }

    // TODO: TEMP
    if (!lastName.equalsIgnoreCase("Nash")) {
      return;
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // ACCOUNT
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    accountData.put("Account ExtRef Blackbaud_Constituent_ID__c", accountExtRef);
    accountData.put("Account Custom Blackbaud_Constituent_ID__c", accountExtRef);

    boolean isBusiness = !Strings.isNullOrEmpty(row.get("CnBio_Org_Name"));

    if (isBusiness) {
      return;
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // CONTACT
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    contactData.put("Contact ExtRef Blackbaud_Constituent_ID__c", contactExtRef);
    contactData.put("Contact Custom Blackbaud_Constituent_ID__c", contactExtRef);

    // combine and save off the rows
    contactData.putAll(accountData);
    primaryRows.add(contactData);

    System.out.println(firstName + " " + lastName + ": contact=" + contactExtRef + " account=" + accountExtRef);
  }
}
