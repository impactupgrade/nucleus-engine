package com.impactupgrade.nucleus.environment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Whenever possible, we focus on being configuration-driven using one, large JSON file.
 * See environment-default.json for the base layer! Then, organizations can provide their own environment.json file
 * to overwrite specific values from the default. environment.json is assumed to be on the thread's classpath.
 */
public class EnvironmentConfig {

  private static final Logger log = LogManager.getLogger(EnvironmentConfig.class.getName());

  // We use Set for collections of Strings. When the JSON files are merged together, this prevents duplicate values.

  public MetadataKeys metadataKeys;

  public static class MetadataKeys {
    public Set<String> account;
    public Set<String> campaign;
    public Set<String> contact;
    public Set<String> recordType;
  }

  public Salesforce salesforce;

  public static class Salesforce {
    public CRMFieldsDefinition fields;
    public SalesforceCustomFields customFields;

    public static class SalesforceCustomFields {
      public Set<String> account;
      public Set<String> campaign;
      public Set<String> contact;
      public Set<String> donation;
      public Set<String> recurringDonation;
      public Set<String> user;
    }
  }

  public Hubspot hubspot;

  public static class Hubspot {
    public HubSpotDonationPipeline donationPipeline;
    public HubSpotRecurringDonationPipeline recurringDonationPipeline;
    public CRMFieldsDefinition fields;
    public String defaultSmsOptInList;

    public static class HubSpotDonationPipeline {
      public String name;
      public String successStage;
      public String failedStage;
      public String refundedStage;
    }

    public static class HubSpotRecurringDonationPipeline {
      public String name;
      public String openStage;
      public String closedStage;
    }
  }

  // TODO: For now, assuming this can be common across all CRMs
  public static class CRMFieldsDefinition {
    public String paymentGatewayName;
    public String paymentGatewayTransactionId;
    public String paymentGatewayCustomerId;
    public String paymentGatewaySubscriptionId;
    public String paymentGatewayRefundId;
    public String paymentGatewayDepositId;
    public String paymentGatewayDepositDate;
    public String paymentGatewayDepositNetAmount;
  }

  public static EnvironmentConfig init() {
    try (
        InputStream jsonDefault = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("environment-default.json");
        InputStream jsonOrg = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("environment.json")
    ) {
      ObjectMapper mapper = new ObjectMapper();
      // Allows nested objects, collections, etc. to be merged together.
      mapper.setDefaultMergeable(true);
      // Start with the default JSON as the foundation.
      EnvironmentConfig envConfig = mapper.readValue(jsonDefault, EnvironmentConfig.class);
      // Then override specific properties with anything that's in env.json.
      mapper.readerForUpdating(envConfig).readValue(jsonOrg);
      return envConfig;
    } catch (IOException e) {
      log.error("Unable to read environment JSON files! Exiting...", e);
      System.exit(1);
      return null;
    }
  }
}
