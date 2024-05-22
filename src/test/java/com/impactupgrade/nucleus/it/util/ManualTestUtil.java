/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it.util;

import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.sforce.soap.partner.sobject.SObject;

import java.util.List;

public class ManualTestUtil {

  public static void main(String[] args) throws Exception {
    clearSfdc();
  }

  private static void clearSfdc() throws Exception {
    Environment env = new Environment("environment-it-sfdc-stripe.json");
    SfdcClient sfdcClient = env.sfdcClient();

    List<SObject> sObjects = sfdcClient.queryListAutoPaged("SELECT Id FROM Opportunity");
    sfdcClient.batchDelete(sObjects.toArray());
    sfdcClient.batchFlush();

    sObjects = sfdcClient.queryListAutoPaged("SELECT Id FROM Npe03__Recurring_Donation__c");
    sfdcClient.batchDelete(sObjects.toArray());

    sObjects = sfdcClient.queryListAutoPaged("SELECT Id FROM Task");
    sfdcClient.batchDelete(sObjects.toArray());
    sfdcClient.batchFlush();
    sfdcClient.batchFlush();

    sObjects = sfdcClient.queryListAutoPaged("SELECT Id FROM Contact");
    sfdcClient.batchDelete(sObjects.toArray());
    sfdcClient.batchFlush();

    sObjects = sfdcClient.queryListAutoPaged("SELECT Id FROM Account");
    sfdcClient.batchDelete(sObjects.toArray());
    sfdcClient.batchFlush();

    sObjects = sfdcClient.queryListAutoPaged("SELECT Id FROM Campaign");
    sfdcClient.batchDelete(sObjects.toArray());
    sfdcClient.batchFlush();
  }
}
