package com.impactupgrade.nucleus.environment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Whenever possible, we focus on being configuration-driven using one, large JSON file.
 * See environment-default.json for the base layer! Then, organizations can provide their own environment.json file
 * to overwrite specific values from the default. environment.json is assumed to be on the thread's classpath.
 */
public class EnvironmentConfig {

  private static final Logger log = LogManager.getLogger(EnvironmentConfig.class.getName());

  // We use Set for collections of Strings. When the JSON files are merged together, this prevents duplicate values.

  public Platforms platforms = new Platforms();

  public static class Platforms {
    public String crm = "";
    public String paymentGateway = "";
  }

  public MetadataKeys metadataKeys = new MetadataKeys();

  public static class MetadataKeys {
    public Set<String> account = new HashSet<>();
    public Set<String> campaign = new HashSet<>();
    public Set<String> contact = new HashSet<>();
    public Set<String> recordType = new HashSet<>();
  }

  public Salesforce salesforce = new Salesforce();

  public static class Salesforce {
    public CRMFieldDefinitions fieldDefinitions = new CRMFieldDefinitions();
    public SalesforceCustomFields customQueryFields = new SalesforceCustomFields();
    public String defaultCampaignId = "";

    public static class SalesforceCustomFields {
      public Set<String> account = new HashSet<>();
      public Set<String> campaign = new HashSet<>();
      public Set<String> contact = new HashSet<>();
      public Set<String> donation = new HashSet<>();
      public Set<String> recurringDonation = new HashSet<>();
      public Set<String> user = new HashSet<>();
    }
  }

  public Hubspot hubspot = new Hubspot();

  public static class Hubspot {
    public HubSpotDonationPipeline donationPipeline = new HubSpotDonationPipeline();
    public HubSpotRecurringDonationPipeline recurringDonationPipeline = new HubSpotRecurringDonationPipeline();
    public HubspotCRMFieldDefinitions fieldDefinitions = new HubspotCRMFieldDefinitions();
    public String defaultSmsOptInList = "";

    public static class HubSpotDonationPipeline {
      public String id = "";
      public String successStageId = "";
      public String failedStageId = "";
      public String refundedStageId = "";
    }

    public static class HubSpotRecurringDonationPipeline {
      public String id = "";
      public String openStageId = "";
      public String closedStageId = "";
    }
  }

  public static class HubspotCRMFieldDefinitions extends CRMFieldDefinitions {
    // TODO: Although this one is specifically for a HS shortcoming (no deal-to-deal association)...
    public String recurringDonationDealId = "";
  }

  // TODO: For now, assuming this can be common across all CRMs
  public static class CRMFieldDefinitions {
    public String paymentGatewayName = "";
    public String paymentGatewayTransactionId = "";
    public String paymentGatewayCustomerId = "";
    public String paymentGatewaySubscriptionId = "";
    public String paymentGatewayRefundId = "";
    public String paymentGatewayDepositId = "";
    public String paymentGatewayDepositDate = "";
    public String paymentGatewayDepositNetAmount = "";
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
      // Then override specific properties with anything that's in env.json, if there is one.
      if (jsonOrg != null) {
        mapper.readerForUpdating(envConfig).readValue(jsonOrg);
      }
      return envConfig;
    } catch (IOException e) {
      log.error("Unable to read environment JSON files! Exiting...", e);
      System.exit(1);
      return null;
    }
  }
}
