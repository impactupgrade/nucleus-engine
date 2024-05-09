/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.sforce.soap.partner.sobject.SObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MergeSfdcDuplicateRDs {

  private static final Logger log = LogManager.getLogger(MergeSfdcDuplicateRDs.class);

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
        envConfig.salesforce.npsp = true;
        envConfig.salesforce.enhancedRecurringDonations = true;
        return envConfig;
      }
    };
    mergeDuplicates(env);
  }

  private static void mergeDuplicates(Environment env) throws Exception {
    CrmService sfdcCrmService = env.crmService("salesforce");
    SfdcClient sfdcClient = env.sfdcClient();

    List<SObject> rds = sfdcClient.queryListAutoPaged("SELECT Id, Payment_Gateway_Subscription_ID__c FROM Npe03__Recurring_Donation__c ORDER BY CreatedDate ASC");
    Map<String, List<SObject>> rdsBySubscriptionId = rds.stream()
        .filter(rd -> !Strings.isNullOrEmpty((String) rd.getField("Payment_Gateway_Subscription_ID__c")))
        .collect(Collectors.groupingBy(rd -> rd.getField("Payment_Gateway_Subscription_ID__c").toString()));
    int total = rdsBySubscriptionId.size();
    int count = 0;
    for (Map.Entry<String, List<SObject>> entry : rdsBySubscriptionId.entrySet()) {
      count++;
      log.info("duplicate RD set {} of {}", count, total);

      SObject primary = null;
      List<SObject> secondary = new ArrayList<>();

      for (SObject rd : entry.getValue()) {
        if (primary == null) {
          primary = rd;
        } else {
          secondary.add(rd);
        }
      }

      if (secondary.isEmpty()) {
        log.info("duplicate RD set contained no secondaries; skipping merge...");
        continue;
      }

      // Simply do one at a time since there's a max of 3 per request.
      for (SObject secondaryItem : secondary) {
        List<SObject> opps = sfdcClient.queryListAutoPaged("SELECT Id FROM Opportunity WHERE Npe03__Recurring_Donation__c='" + secondaryItem.getId() + "'");
        for (SObject opp : opps) {
          SObject update = new SObject("Opportunity");
          update.setId(opp.getId());
          update.setField("Npe03__Recurring_Donation__c", primary.getId());
          sfdcClient.batchUpdate(update);
        }
        sfdcClient.batchFlush();

        sfdcClient.delete(secondaryItem);
      }
    }
  }
}
