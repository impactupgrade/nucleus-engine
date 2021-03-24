package com.impactupgrade.common.environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Whenever possible, we focus on being configuration-driven using one, large JSON file.
 * See environment-default.json for the base layer!
 */
public class EnvironmentConfig {
  // Even though it's a maintenance point, use a statically typed contract to prevent floating strings everywhere!

  public List<String> accountMetadataKeys = new ArrayList<>();
  public List<String> campaignMetadataKeys = new ArrayList<>();
  public List<String> contactMetadataKeys = new ArrayList<>();
  public List<String> recordTypeMetadataKeys = new ArrayList<>();

  public HubSpot hubSpot = null;

  public static class HubSpot {
    public HubSpotDonationPipeline donationPipeline;
    public HubSpotRecurringDonationPipeline recurringDonationPipeline;
    public CRMFieldsDefinition fields;

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
