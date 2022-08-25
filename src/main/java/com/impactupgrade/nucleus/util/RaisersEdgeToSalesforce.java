/*
 * Copyright (c) 2022 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.sforce.soap.partner.sobject.SObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

// TODO: Temporary utility to eventually be worked into a reusable strategy. Square up migrations, bulk imports,
//  and the generic CRM model.
public class RaisersEdgeToSalesforce {

  public static void main(String[] args) throws Exception {
    // TODO: This is completely incorrect and needs wired into the true framework, but I'm adding it
    //  to show what the calls will ultimately look like.
//    SfdcClient sfdcClient = new SfdcClient(null);

    String localPath = "/home/skot/Downloads/Impact Upgrade/CLHS/Blackbaud-exports/";

    File file = new File(localPath + "db-Constituent+Spouse-basic-info_sm-sample.xlsx");
    InputStream inputStream = new FileInputStream(file);
    List<Map<String, String>> rows = Utils.getExcelData(inputStream);

    for (Map<String, String> row : rows) {

      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // ACCOUNT
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

      SObject sfdcAccount = new SObject("Account");

      if (!Strings.isNullOrEmpty(row.get("CnBio_Org_Name"))) {
        String query = "SELECT Id, Name FROM RecordType WHERE sObjectType='Account' AND Name='Organization'";
        // TODO: run SOQL query
        // QueryResult result = connection.query(query);
        // sfdcAccount.setField("RecordTypeId", result);
        sfdcAccount.setField("RecordTypeId", "0128V000001h1ZuQAI");
        sfdcAccount.setField("Name", row.get("CnBio_Org_Name"));
      } else {
        String query = "SELECT Id, Name FROM RecordType WHERE sObjectType='Account' AND Name='Household Account'";
        // TODO: run SOQL query
        // QueryResult result = connection.query(query);
        // sfdcAccount.setField("RecordTypeId", result);
        sfdcAccount.setField("RecordTypeId", "0128V000001h1ZtQAI");
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
      // TODO: TESTING NEEDED - will \n separated multi-line address transfer to SFDC ok?
      String cnAdrPrfAddrStr = String.join("\n", cnAdrPrfAddr);
      sfdcAccount.setField("BillingStreet", cnAdrPrfAddrStr);
      sfdcAccount.setField("BillingCity", row.get("CnAdrPrf_City"));
      sfdcAccount.setField("BillingState", row.get("CnAdrPrf_State"));
      sfdcAccount.setField("BillingPostalCode", row.get("CnAdrPrf_ZIP"));
      sfdcAccount.setField("BillingCountry", row.get("CnAdrPrf_ContryLongDscription"));
      sfdcAccount.setField("npe01__Primary_Address_Type__c", row.get("CnAdrPrf_Type"));
      if ("No".equalsIgnoreCase(row.get("CnAdrPrf_Sndmailtthisaddrss"))) {
        sfdcAccount.setField("BB_BillingAddrOptOut", true); // TODO: checkbox
      }
      if ("Yes".equalsIgnoreCase(row.get("CnAdrPrf_Seasonal"))) {
        sfdcAccount.setField("BB_BillingAddrSeasonal", true); // TODO: checkbox
      }

//      String sfdcAccountId = sfdcClient.insert(sfdcAccount).getId();
      System.out.println(sfdcAccount);

      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // CONTACT
      //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

      // TODO: may ultimately need multiple contacts, and may not need any at all for organizations
      SObject sfdcContact = new SObject("Contact");
//      sfdcContact.setField("AccountId", sfdcAccountId);

      sfdcContact.setField("Blackbaud_Id__c", row.get("CnBio_ID"));

      // Combine all ph/comments and dump in single notes/desc field
      Set<String> cnAllPhoneComments = new LinkedHashSet<>();
      // parse phone/email/fax/website items, all labeled CnPh...
      for (int i = 1; i <= 3; i++) {
        String cnPhType = "CnPh_1_0" + i + "_Phone_type";
        String cnPhPhone = "CnPh_1_0" + i + "_Phone_number";
        String cnPhDoNotContact = "CnPh_1_0" + i + "_Do_Not_Contact";
        String cnPhInactive = "CnPh_1_0" + i + "_Inactive";
        String cnPhIsPrimary = "CnPh_1_0" + i + "_Is_Primary";
        String cnPhComments = "CnPh_1_0" + i + "_Comments";
        // Append comment to set
        if (!Strings.isNullOrEmpty(row.get(cnPhComments))) {
          cnAllPhoneComments.add(row.get(cnPhPhone) + ": " + row.get(cnPhComments));
        }
        //System.out.println(row.get(cnPhType) + ", " + row.get(cnPhPhone) + ", Primary=" + row.get(cnPhPrimary) + ", Do_Not_Contact=" + row.get(cnPhDoNotContact));
        if ("Email".equalsIgnoreCase(row.get(cnPhType))) {
          sfdcContact.setField("npe01__HomeEmail__c", row.get(cnPhPhone));

          if ("Yes".equalsIgnoreCase(row.get(cnPhDoNotContact))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhInactive))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
            sfdcContact.setField("npe01__Preferred_Email__c", "Personal");
          }
        } else if ("EmailFinder".equalsIgnoreCase(row.get(cnPhType))) {
          sfdcContact.setField("npe01__AlternateEmail__c", row.get(cnPhPhone));
          if ("Yes".equalsIgnoreCase(row.get(cnPhDoNotContact))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhInactive))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
            sfdcContact.setField("npe01__Preferred_Email__c", "Alternate");
          }
        } else if ("CC Email".equalsIgnoreCase(row.get(cnPhType))) {
          sfdcContact.setField("BB_CC_Email__c", row.get(cnPhPhone));
          if ("Yes".equalsIgnoreCase(row.get(cnPhDoNotContact))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhInactive))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
            sfdcContact.setField("npe01__Preferred_Email__c", "CC Email");
          }
        } else if ("Home".equalsIgnoreCase(row.get(cnPhType))) {
          sfdcContact.setField("HomePhone", row.get(cnPhPhone));
          if ("Yes".equalsIgnoreCase(row.get(cnPhDoNotContact))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhInactive))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
            sfdcContact.setField("npe01__PreferredPhone__c", "Home");
          }
        } else if ("Home 2".equalsIgnoreCase(row.get(cnPhType))) {
          sfdcContact.setField("OtherPhone", row.get(cnPhPhone));
          if ("Yes".equalsIgnoreCase(row.get(cnPhDoNotContact))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhInactive))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
            sfdcContact.setField("npe01__PreferredPhone__c", "Other");
          }
        } else if ("Wireless".equalsIgnoreCase(row.get(cnPhType))) {
          sfdcContact.setField("MobilePhone", row.get(cnPhPhone));
          if ("Yes".equalsIgnoreCase(row.get(cnPhDoNotContact))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhInactive))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
            sfdcContact.setField("npe01__PreferredPhone__c", "Mobile");
          }
        } else if ("PhoneFinder".equalsIgnoreCase(row.get(cnPhType))) {
          sfdcContact.setField("BB_PhoneFinder__c", row.get(cnPhPhone));
          if ("Yes".equalsIgnoreCase(row.get(cnPhDoNotContact))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhInactive))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
            sfdcContact.setField("npe01__PreferredPhone__c", "PhoneFinder");
          }
        } else if ("Business/College".equalsIgnoreCase(row.get(cnPhType))) {
          // TODO: Put on both Account and Contact? Account only?
          sfdcAccount.setField("Phone", row.get(cnPhPhone));
          sfdcContact.setField("npe01__WorkPhone__c", row.get(cnPhPhone));
          if ("Yes".equalsIgnoreCase(row.get(cnPhDoNotContact))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhInactive))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
            sfdcContact.setField("npe01__PreferredPhone__c", "Work");
          }
        } else if ("Spouse".equalsIgnoreCase(row.get(cnPhType))) {
          sfdcContact.setField("BB_Spouse_Phone__c", row.get(cnPhPhone));
          if ("Yes".equalsIgnoreCase(row.get(cnPhDoNotContact))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhInactive))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
            sfdcContact.setField("npe01__PreferredPhone__c", "Spouse");
          }
        } else if ("Spouse Business".equalsIgnoreCase(row.get(cnPhType))) {
          sfdcContact.setField("BB_Spouse_Business_Phone__c", row.get(cnPhPhone));
          if ("Yes".equalsIgnoreCase(row.get(cnPhDoNotContact))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhInactive))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
            sfdcContact.setField("npe01__PreferredPhone__c", "Spouse Business");
          }
        } else if ("Bank".equalsIgnoreCase(row.get(cnPhType))) {
          sfdcContact.setField("BB_Bank_Phone__c", row.get(cnPhPhone));
          if ("Yes".equalsIgnoreCase(row.get(cnPhDoNotContact))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhInactive))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
        } else if ("Business Fax".equalsIgnoreCase(row.get(cnPhType))) {
          // Goes on Account
          sfdcAccount.setField("Fax", row.get(cnPhPhone));
          if ("Yes".equalsIgnoreCase(row.get(cnPhDoNotContact))) {
            sfdcAccount.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhInactive))) {
            sfdcAccount.setField("TODO", true); // TODO: checkbox
          }
        } else if ("Website".equalsIgnoreCase(row.get(cnPhType))) {
          // Goes on Account
          sfdcAccount.setField("Website", row.get(cnPhPhone));
        } else if ("Facebook".equalsIgnoreCase(row.get(cnPhType))) {
          sfdcContact.setField("BB_Facebook__c", row.get(cnPhPhone));
          if ("Yes".equalsIgnoreCase(row.get(cnPhDoNotContact))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhInactive))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
          if ("Yes".equalsIgnoreCase(row.get(cnPhIsPrimary))) {
            sfdcContact.setField("TODO", true); // TODO: checkbox
          }
        }
      }
      // Combine all ph/comments and dump in single notes/desc field
      String cnAllPhoneCommentsCombined = String.join(", ", cnAllPhoneComments);
      sfdcContact.setField("Description", cnAllPhoneCommentsCombined); // TODO: textarea, use Description field

      // Zero Orgs have Contact Names
      // Set Org name as Last name
      if (!Strings.isNullOrEmpty(row.get("CnBio_Org_Name"))) {
        sfdcContact.setField("LastName", row.get("CnBio_Org_Name"));
      } else {
        sfdcContact.setField("FirstName", row.get("CnBio_First_Name"));
        sfdcContact.setField("LastName", row.get("CnBio_Last_Name"));
        sfdcContact.setField("BB_CnBio_Maiden_name", row.get("CnBio_Maiden_name"));
        sfdcContact.setField("BB_CnBio_Middle_Name__c", row.get("CnBio_Middle_Name"));
        sfdcContact.setField("Preferred_Name__c", row.get("CnBio_Nickname"));
        sfdcContact.setField("BB_CnBio_Suffix_1", row.get("CnBio_Suffix_1"));
      }
      // These must have been hand-entered, could be: 1950, 1/30, 1/1950, 1/1/1950
      // Conclusion - parsing would be problematic, text box instead
      sfdcContact.setField("BB_Birthdate", row.get("CnBio_Birth_date")); // TODO: text box
      sfdcContact.setField("BB_CnBio_Ethnicity", row.get("CnBio_Ethnicity"));
      if ("Yes".equalsIgnoreCase(row.get("CnBio_Deceased"))) {
        sfdcContact.setField("npsp__Deceased__c", true);
        sfdcContact.setField("BB_Deceased_Date", row.get("CnBio_Deceased_Date")); // TODO: text box
      }
      sfdcContact.setField("Gender__c", row.get("CnBio_Gender"));
      sfdcContact.setField("BB_CnBio_Marital_status", row.get("CnBio_Marital_status"));

      // Opt out / inactive fields
      if ("Yes".equalsIgnoreCase(row.get("CnBio_Requests_no_e-mail"))) {
        sfdcContact.setField("HasOptedOutOfEmail", true);
      }
      if ("Yes".equalsIgnoreCase(row.get("CnBio_Inactive"))) {
        sfdcContact.setField("BB_CnBio_Inactive", true); // TODO: checkbox
      }
      if ("Yes".equalsIgnoreCase(row.get("CnBio_Solicitor_Inactive"))) {
        sfdcContact.setField("BB_CnBio_Solicitor_Inactive", true); // TODO: checkbox
      }
      if ("Yes".equalsIgnoreCase(row.get("CnBio_Anonymous"))) {
        sfdcContact.setField("Anonymous", true); // TODO: Custom checkbox, name not BB-specific
      }

      // If spouse does NOT have a Constituent ID, they only exist here, so create Contact
      if (Strings.isNullOrEmpty(row.get("CnSpSpBio_ID")) && !Strings.isNullOrEmpty(row.get("CnSpSpBio_Last_Name"))) {
        SObject sfdcContactSpouse = new SObject("Contact");
        sfdcContactSpouse.setField("FirstName", row.get("CnSpSpBio_First_Name"));
        sfdcContactSpouse.setField("BB_CnBio_Middle_Name__c", row.get("CnSpSpBio_Middle_Name"));
        sfdcContactSpouse.setField("LastName", row.get("CnSpSpBio_Last_Name"));
        sfdcContactSpouse.setField("Preferred_Name__c", row.get("CnSpSpBio_Nickname"));
        sfdcContactSpouse.setField("BB_CnBio_Maiden_name", row.get("CnSpSpBio_Maiden_name"));
        sfdcContactSpouse.setField("BB_CnBio_Suffix_1", row.get("CnSpSpBio_Suffix_1"));
        sfdcContactSpouse.setField("BB_Birthdate", row.get("CnSpSpBio_Birth_date")); // TODO: text box
        sfdcContactSpouse.setField("BB_CnBio_Ethnicity", row.get("CnSpSpBio_Ethnicity"));
        if ("Yes".equalsIgnoreCase(row.get("CnSpSpBio_Deceased"))) {
          sfdcContactSpouse.setField("npsp__Deceased__c", true);
          sfdcContactSpouse.setField("BB_Deceased_Date", row.get("CnSpSpBio_Deceased_Date")); // TODO: text box
        }
        sfdcContactSpouse.setField("Gender__c", row.get("CnSpSpBio_Gender"));
        if ("Yes".equalsIgnoreCase(row.get("CnSpSpBio_Requests_no_e-mail"))) {
          sfdcContactSpouse.setField("HasOptedOutOfEmail", true);
        }
        if ("Yes".equalsIgnoreCase(row.get("CnSpSpBio_Inactive"))) {
          sfdcContactSpouse.setField("BB_CnBio_Inactive", true); // TODO: checkbox
        }
        if ("Yes".equalsIgnoreCase(row.get("CnSpSpBio_Solicitor_Inactive"))) {
          sfdcContactSpouse.setField("BB_CnBio_Solicitor_Inactive", true); // TODO: checkbox
        }
        System.out.println(sfdcContactSpouse);
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
      String cnAdrAllAddrStr = String.join("\n", cnAdrAllAddr1);
      sfdcContact.setField("BB_CnAdrAll_1_01_Addrline", cnAdrAllAddrStr); // TODO: multiline text
      sfdcContact.setField("BB_CnAdrAll_1_01_City__c", row.get("CnAdrAll_1_01_City"));
      sfdcContact.setField("BB_CnAdrAll_1_01_State__c", row.get("CnAdrAll_1_01_State"));
      sfdcContact.setField("BB_CnAdrAll_1_01_ZIP__c", row.get("CnAdrAll_1_01_ZIP"));
      sfdcContact.setField("BB_CnAdrAll_1_01_ContryLongDscription__c", row.get("CnAdrAll_1_01_ContryLongDscription"));
      sfdcContact.setField("BB_CnAdrAll_1_01_Type__c", row.get("CnAdrAll_1_01_Type")); // TODO: Picklist
      if ("No".equalsIgnoreCase(row.get("CnAdrAll_1_01_Sndmailtthisaddrss"))) {
        sfdcContact.setField("BB_CnAdrAll_1_01_OptOut", true); // TODO: checkbox
      }
      if ("Yes".equalsIgnoreCase(row.get("CnAdrAll_1_01_Preferred"))) {
        sfdcContact.setField("BB_CnAdrAll_1_01_Preferred__c", true); // TODO: checkbox
      }
      if ("Yes".equalsIgnoreCase(row.get("CnAdrAll_1_01_Seasonal"))) {
        sfdcContact.setField("BB_CnAdrAll_1_01_Seasonal", true); // TODO: checkbox
      }
      // All in pattern of M/d.
      // Could potentially be parsed, but following other unpredictable date fields and going text box route
      sfdcContact.setField("BB_CnAdrAll_1_01_Seasonal_From", row.get("CnAdrAll_1_01_Seasonal_From")); // TODO: text box
      sfdcContact.setField("BB_CnAdrAll_1_01_Seasonal_To", row.get("CnAdrAll_1_01_Seasonal_To")); // TODO: text box

      // Address 2 -> Contact
      Set<String> cnAdrAllAddr2 = new LinkedHashSet<>();
      for (int i = 1; i <= 4; i++) {
        String field = "CnAdrAll_1_02_Addrline" + i;
        if (!Strings.isNullOrEmpty(row.get(field))) {
          cnAdrAllAddr2.add(row.get(field));
        }
      }
      String cnAdrAllAddr2Str = String.join("\n", cnAdrAllAddr2);
      sfdcContact.setField("BB_CnAdrAll_1_02_Addrline", cnAdrAllAddr2Str); // TODO: multiline text
      sfdcContact.setField("BB_CnAdrAll_1_02_City", row.get("CnAdrAll_1_02_City"));
      sfdcContact.setField("BB_CnAdrAll_1_02_State", row.get("CnAdrAll_1_02_State"));
      sfdcContact.setField("BB_CnAdrAll_1_02_ZIP", row.get("CnAdrAll_1_02_ZIP"));
      sfdcContact.setField("BB_CnAdrAll_1_02_ContryLongDscription", row.get("CnAdrAll_1_02_ContryLongDscription"));
      sfdcContact.setField("BB_CnAdrAll_1_02_Type", row.get("CnAdrAll_1_02_Type")); // TODO: Picklist
      if ("No".equalsIgnoreCase(row.get("CnAdrAll_1_02_Sndmailtthisaddrss"))) {
        sfdcContact.setField("BB_CnAdrAll_1_02_OptOut", true); // TODO: checkbox
      }
      if ("Yes".equalsIgnoreCase(row.get("CnAdrAll_1_02_Preferred"))) {
        sfdcContact.setField("BB_CnAdrAll_1_02_Preferred__c", true); // TODO: checkbox
      }
      if ("Yes".equalsIgnoreCase(row.get("CnAdrAll_1_02_Seasonal"))) {
        sfdcContact.setField("BB_CnAdrAll_1_02_Seasonal", true); // TODO: checkbox
      }
      // All in pattern of M/d
      sfdcContact.setField("BB_CnAdrAll_1_02_Seasonal_From", row.get("CnAdrAll_1_02_Seasonal_From")); // TODO: text box
      sfdcContact.setField("BB_CnAdrAll_1_02_Seasonal_To", row.get("CnAdrAll_1_02_Seasonal_To")); // TODO: text box

      // Constituent Types
      // TODO: Per Brett, do NOT map to "type" field. Let's think through a better way to identify former students/parents in an isolated way.
      // Possible values: {'Current Student', 'Spouse is Alumni', 'Former Guardian', 'Withdrawn Parent', 'Current Guardian', 'Member Church', 'Online Donation', 'Retired Staff', 'Delegate', 'Community Member', 'Friend', 'Withdrawn Grandparent', 'Staff', 'Withdrawn Student', 'Honorary CEF BOD', 'Former Grandparent', 'Spouse is Staff', 'Parent of Alumni', 'Former Staff', 'Current Parent', 'Spouse is Former Staff', 'Former CLHS BOD', 'Former CEF BOD', 'Alumni', 'Other', 'Spouse is Board of Direcotrs', 'Grandparent'}
      Set<String> cnTypeCodes = new LinkedHashSet<>();
      for (int i = 1; i <= 5; i++) {
        String field = "CnCnstncy_1_0" + i + "_CodeLong";
        if (!Strings.isNullOrEmpty(row.get(field))) {
          cnTypeCodes.add(row.get(field));
        }
      }
      String cnTypeCodesStr = String.join(";", cnTypeCodes);
      sfdcContact.setField("BB_Type_field", cnTypeCodesStr);  // TODO: picklist

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
      sfdcContact.setField("BB_Solicit_Codes", cnSolicitCodesStr);  // TODO: picklist


//      String sfdcContactId = sfdcClient.insert(sfdcContact).getId();
      System.out.println(sfdcContact + "\n");

    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // RECURRING DONATION
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    File giftsFile = new File(localPath + "Gifts-with-Installments-v3-more-IDs-sample.xlsx");
    InputStream giftInputStream = new FileInputStream(giftsFile);
    List<Map<String, String>> giftRows = Utils.getExcelData(giftInputStream);
    for (Map<String, String> giftRow : giftRows) {
      if ("Recurring Gift".equalsIgnoreCase(giftRow.get("Gf_Type"))) {
        SObject sfdcRecurringDonation = new SObject("Npe03__Recurring_Donation__c");

        sfdcRecurringDonation.setField("BB_Gift_ID", giftRow.get("Gf_Gift_ID"));  // TODO: field
        sfdcRecurringDonation.setField("npe03__Amount__c", giftRow.get("Gf_Amount"));

        if ("Yes".equalsIgnoreCase(giftRow.get("Gf_Send_reminders"))) {
          sfdcRecurringDonation.setField("BB_Send_reminders", true); // TODO: checkbox
        }

        sfdcRecurringDonation.setField("npsp__PaymentMethod__c", giftRow.get("Gf_Pay_method"));
        sfdcRecurringDonation.setField("BB_Receipt", giftRow.get("Gf_Receipt"));  // TODO: picklist

        // TODO: How to associate with Constituent?
        String cnBioID = giftRow.get("Gf_CnBio_ID");

        if (!Strings.isNullOrEmpty(giftRow.get("Gf_FirstInstDue"))) {
          Date d = new SimpleDateFormat("MM/dd/yyyy").parse(giftRow.get("Gf_FirstInstDue"));
          sfdcRecurringDonation.setField("npe03__Date_Established__c", d);
        }

        // TODO: how to process multiples?
        //   all three: Might have multiples: eg. '2017 Alumni Phone; 2017 December Appeal'
        //   Appears these are all on Pledges (probably a summary of all the installments metadata)
        // TODO: BB Campaign & Appeal seem to be parent / child
        //   any combination of none, one, or both may be set
        sfdcRecurringDonation.setField("npe03__Recurring_Donation_Campaign__c", giftRow.get("Gf_Campaign"));
        sfdcRecurringDonation.setField("Appeal__c", giftRow.get("Gf_Appeal"));
        sfdcRecurringDonation.setField("Fund__c", giftRow.get("Gf_Fund")); // TODO: new object? picklist?

        // BB: {'Semi-Annually', 'Quarterly', 'Single Installment', 'Monthly', 'Annually', 'Irregular'}
        // Default: every 1 Month for Monthly & Irregular
        sfdcRecurringDonation.setField("npsp__InstallmentFrequency__c", 1);
        sfdcRecurringDonation.setField("npe03__Installment_Period__c", "Monthly");
        if ("Quarterly".equalsIgnoreCase(giftRow.get("Gf_Installmnt_Frqncy"))) {
          sfdcRecurringDonation.setField("npsp__InstallmentFrequency__c", 3);
        } else if ("Semi-Annually".equalsIgnoreCase(giftRow.get("Gf_Installmnt_Frqncy"))) {
          sfdcRecurringDonation.setField("npsp__InstallmentFrequency__c", 6);
        } else if ("Annually".equalsIgnoreCase(giftRow.get("Gf_Installmnt_Frqncy"))) {
          sfdcRecurringDonation.setField("npsp__InstallmentFrequency__c", 1);
          sfdcRecurringDonation.setField("npe03__Installment_Period__c", "Yearly");
        }

        // TODO: 99% are Active, but a glance at related donations & dates imply otherwise
        // BB: {'Active', 'Completed', 'Terminated'}
        if ("Active".equalsIgnoreCase(giftRow.get("Gf_Gift_status"))) {
          sfdcRecurringDonation.setField("npsp__Status__c", "Active");
        } else {
          sfdcRecurringDonation.setField("npsp__Status__c", "Closed");
        }

        // TODO: Copied from Bloomerang, needs integrated
//        if (env.getConfig().salesforce.enhancedRecurringDonations) {
//          // NPSP Enhanced RDs will not allow you to associate the RD directly with an Account if it's a household, instead
//          // forcing us to use the contact.
//          if (constituentIdToIsBusiness.get(accountNumber)) {
//            sfdcRecurringDonation.setField("Npe03__Organization__c", constituentIdToAccountId.get(accountNumber));
//          } else {
//            sfdcRecurringDonation.setField("Npe03__Contact__c", constituentIdToContactId.get(accountNumber));
//          }
//
//          sfdcRecurringDonation.setField("npsp__RecurringType__c", "Open");
//          // It's a picklist, so it has to be a string and not numeric :(
//          LocalDate d = LocalDate.parse(giftRow.get("Gf_FirstInstDue"), DateTimeFormatter.ofPattern("M/d/yyyy"));
//          sfdcRecurringDonation.setField("npsp__Day_of_Month__c", d.getDayOfMonth() + "");
//        } else {
//          // Legacy behavior was to always use the Account, regardless if it was a business or household. Stick with that
//          // by default -- we have some orgs that depend on it.
//          sfdcRecurringDonation.setField("Npe03__Organization__c", constituentIdToAccountId.get(accountNumber));
//        }

        System.out.println(sfdcRecurringDonation + "\n");
      }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // DONATIONS
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

     /* TODO: Fields
    Gf_Gift_status
    Gf_Type
    Gf_Receipt
    Gf_SCMGFlag
     */

    for (Map<String, String> giftRow : giftRows) {

      if (!"Recurring Gift".equalsIgnoreCase(giftRow.get("Gf_Type"))) {

        SObject sfdcOpportunity = new SObject("Opportunity");
        sfdcOpportunity.setField("Amount", giftRow.get("Gf_Amount"));
        sfdcOpportunity.setField("RecordTypeId", "TODO"); // TODO: default to Donation?
        sfdcOpportunity.setField("BB_Gift_subtype", giftRow.get("Gf_Gift_subtype")); // TODO: picklist?

        // TODO: Same issues as Recurring Gift section ^^^^^
        sfdcOpportunity.setField("CampaignId", giftRow.get("Gf_Campaign")); // TODO: get related Campaign ID
        sfdcOpportunity.setField("Appeal__c", giftRow.get("Gf_Appeal"));
        sfdcOpportunity.setField("Fund__c", giftRow.get("Gf_Fund")); // TODO: new object? picklist?

        if (!Strings.isNullOrEmpty(giftRow.get("Gf_Date"))) {
          Date d = new SimpleDateFormat("MM/dd/yyyy").parse(giftRow.get("Gf_Date"));
          sfdcOpportunity.setField("CloseDate", d);
        }
        if (!Strings.isNullOrEmpty(giftRow.get("Gf_DateAdded"))) {
          Date d = new SimpleDateFormat("MM/dd/yyyy").parse(giftRow.get("Gf_DateAdded"));
          sfdcOpportunity.setField("npsp__Acknowledgment_Date__c", d);
        }
        sfdcOpportunity.setField("BB_Gift_ID", giftRow.get("Gf_Gift_ID")); // TODO: field

        if ("Yes".equalsIgnoreCase(giftRow.get("Gf_Anonymous"))) {
          sfdcOpportunity.setField("BB_Anonymous", true); // TODO: checkbox
        }

        sfdcOpportunity.setField("npsp__Honoree_Name__c", giftRow.get("Gf_Hon_Mem_name"));
        sfdcOpportunity.setField("Description", giftRow.get("Gf_Reference"));



        // TODO: How to handle SFDC Opp types & IDs (Donation, In-Kind, Major Gift, Matching)
        // BB Gf_Type: {'Other', 'Stock/Property', 'Pledge', 'Recurring Gift', 'Pay-Stock/Property', 'Pay-Gift-in-Kind', 'Cash', 'Recurring Gift Pay-Cash', 'MG Pay-Cash', 'Gift-in-Kind', 'Pay-Cash', 'MG Pledge'}
        if ("Recurring Gift Pay-Cash".equalsIgnoreCase(giftRow.get("Gf_Type"))) {

        } else if ("MG Pledge".equalsIgnoreCase(giftRow.get("Gf_Type"))) {
          sfdcOpportunity.setField("RecordTypeId", "TODO"); // TODO

        } else if ("MG Pay-Cash".equalsIgnoreCase(giftRow.get("Gf_Type"))) {
          sfdcOpportunity.setField("RecordTypeId", "TODO"); // TODO

        } else if ("Pledge".equalsIgnoreCase(giftRow.get("Gf_Type"))) {

        } else if ("Pay-Cash".equalsIgnoreCase(giftRow.get("Gf_Type"))) {

        } else if ("Gift-in-Kind".equalsIgnoreCase(giftRow.get("Gf_Type")) || "Pay-Gift-in-Kind".equalsIgnoreCase(giftRow.get("Gf_Type"))) {
          sfdcOpportunity.setField("RecordTypeId", "TODO"); // TODO
          sfdcOpportunity.setField("npsp__Fair_Market_Value__c", giftRow.get("Gf_Crrncy_Rcipt_Amont"));
        } else if ("Stock/Property".equalsIgnoreCase(giftRow.get("Gf_Type"))) {

        } else if ("Pay-Stock/Property".equalsIgnoreCase(giftRow.get("Gf_Type"))) {

        } else if ("Cash".equalsIgnoreCase(giftRow.get("Gf_Type")) || "Other".equalsIgnoreCase(giftRow.get("Gf_Type"))) {

        }

        System.out.println(sfdcOpportunity + "\n");

      }
    }
  }
}
