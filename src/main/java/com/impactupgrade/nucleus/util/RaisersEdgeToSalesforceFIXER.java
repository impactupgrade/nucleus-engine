///*
// * Copyright (c) 2022 3River Development LLC, DBA Impact Upgrade. All rights reserved.
// */
//
//package com.impactupgrade.nucleus.util;
//
//import com.google.common.base.Strings;
//import com.impactupgrade.nucleus.environment.Environment;
//import com.impactupgrade.nucleus.environment.EnvironmentConfig;
//import com.impactupgrade.nucleus.model.CrmImportEvent;
//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;
//import org.apache.poi.util.IOUtils;
//
//import java.io.File;
//import java.io.FileInputStream;
//import java.io.InputStream;
//import java.util.ArrayList;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
//public class RaisersEdgeToSalesforceFIXER {
//
//  private static final Logger log = LogManager.getLogger(RaisersEdgeToSalesforceFIXER.class);
//
//  public static void main(String[] args) throws Exception {
//    Environment env = new Environment() {
//      @Override
//      public EnvironmentConfig getConfig() {
//        EnvironmentConfig envConfig = new EnvironmentConfig();
//        envConfig.crmPrimary = "salesforce";
//        envConfig.salesforce.sandbox = false;
//        envConfig.salesforce.url = "concordialutheranhs.my.salesforce.com";
//        envConfig.salesforce.username = "team+clhs@impactupgrade.com";
//        envConfig.salesforce.password = "pqp.gnj1xcd8DFC2mgfpwl7cq1UOBQZYOZKEppFDTQ4";
//        envConfig.salesforce.enhancedRecurringDonations = true;
//        return envConfig;
//      }
//    };
//
//    migrate(env);
//  }
//
//  private static void migrate(Environment env) throws Exception {
//    // The sheets are so huge that the Apache framework we're using to read XLSX thinks it's malicious...
//    IOUtils.setByteArrayMaxOverride(Integer.MAX_VALUE);
//
//    File file = new File("/home/brmeyer/Downloads/Constituent+Spouse-v3-25relationships.xlsx");
//    InputStream inputStream = new FileInputStream(file);
//    List<Map<String, String>> rows = Utils.getExcelData(inputStream);
//
//    List<Map<String, String>> primaryRows = new ArrayList<>();
//
//    for (Map<String, String> row : rows) {
//      if (!Strings.isNullOrEmpty(row.get("CnBio_Org_Name"))) {
//        continue;
//      }
//
//      migrate(row, primaryRows);
//    }
//
//    List<CrmImportEvent> importEvents = CrmImportEvent.fromGeneric(primaryRows);
//    importEvents = importEvents.subList(27000, importEvents.size());
//    env.primaryCrmService().processBulkImport(importEvents);
//  }
//
//  private static void migrate(Map<String, String> row,
//      List<Map<String, String>> primaryRows) {
//
//    Map<String, String> contactData = new HashMap<>();
//
//    String id = row.get("CnBio_ID");
//
//    contactData.put("Contact Custom Blackbaud_Constituent_ID__c", id);
//
//    for (int i = 1; i <= 3; i++) {
//      String cnPhType = "CnPh_1_0" + i + "_Phone_type";
//      String cnPhPhone = "CnPh_1_0" + i + "_Phone_number";
//
//      if ("Yes".equalsIgnoreCase(row.get("CnPh_1_0" + i + "_Inactive")) || "Yes".equalsIgnoreCase(row.get("CnPh_1_0" + i + "_Do_Not_Contact"))) {
//        continue;
//      }
//
//      if ("Email".equalsIgnoreCase(row.get(cnPhType))) {
//        contactData.put("Contact Email", row.get(cnPhPhone));
//      }
//    }
//
//    contactData.put("Contact First Name", row.get("CnBio_First_Name"));
//    contactData.put("Contact Last Name", row.get("CnBio_Last_Name"));
//
//    primaryRows.add(contactData);
//  }
//}
