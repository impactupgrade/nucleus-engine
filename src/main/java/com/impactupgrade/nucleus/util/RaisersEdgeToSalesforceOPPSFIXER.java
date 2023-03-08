///*
// * Copyright (c) 2022 3River Development LLC, DBA Impact Upgrade. All rights reserved.
// */
//
//package com.impactupgrade.nucleus.util;
//
//import com.impactupgrade.nucleus.client.SfdcClient;
//import com.impactupgrade.nucleus.environment.Environment;
//import com.impactupgrade.nucleus.environment.EnvironmentConfig;
//import com.sforce.soap.partner.sobject.SObject;
//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;
//
//import java.util.List;
//
//public class RaisersEdgeToSalesforceOPPSFIXER {
//
//  private static final Logger log = LogManager.getLogger(RaisersEdgeToSalesforceOPPSFIXER.class);
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
//    SfdcClient sfdcClient = new SfdcClient(env);
//    List<SObject> opps = sfdcClient.queryListAutoPaged("select id from Opportunity where recordtypeid!='0128V000001h1ZvQAI'");
//    for (SObject opp : opps) {
//      SObject updateOpp = new SObject("Opportunity");
//      updateOpp.setId(opp.getId());
//      updateOpp.setField("RecordTypeId", "0128V000001h1ZvQAI");
//      sfdcClient.batchUpdate(updateOpp);
//    }
//    sfdcClient.batchFlush();
//  }
//}
