package com.impactupgrade.nucleus.environment;

import java.util.List;

/**
 * Whenever possible, we focus on being configuration-driven using one, large JSON file.
 * See environment-default.json for the base layer!
 */
public class EnvironmentConfig {
  // Even though it's a maintenance point, use a statically typed contract to prevent floating strings everywhere!

  public MetadataKeys metadataKeys;

  public static class MetadataKeys {
    public List<String> account;
    public List<String> campaign;
    public List<String> contact;
    public List<String> recordType;
  }

  public Salesforce salesforce;

  public static class Salesforce {
    public CRMFieldsDefinition fields;
    public SalesforceCustomFields customFields;

    public static class SalesforceCustomFields {
      public List<String> account;
      public List<String> campaign;
      public List<String> contact;
      public List<String> donation;
      public List<String> recurringDonation;
      public List<String> user;
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
    // TODO: use environment-default.json as a base, but overlay clients' json for overrides
    return new EnvironmentConfig();
  }
}
