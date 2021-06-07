/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

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
public class Environment {

  private static final Logger log = LogManager.getLogger(Environment.class.getName());

  // We use Set for collections of Strings. When the JSON files are merged together, this prevents duplicate values.

  public Platforms platforms = new Platforms();

  public static class Platforms {
    public Platform crm = new Platform();
    public Platform paymentGateway = new Platform();
  }

  public static class Platform {
    public String name = "";
    public String key = "";
    public String publicKey = "";
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
    // SFDC has its own currency conversion setup on Opportunity, but HS' is statically defined. Use custom props...
    public String paymentGatewayAmountOriginal = "";
    public String paymentGatewayAmountOriginalCurrency = "";
    public String paymentGatewayAmountExchangeRate = "";
  }

  // TODO: This currently assumes a CRM only has one single set of fields, agnostic to the specific gateway. But that's
  //  not often the case! Ex: LJI and TER have separate sets of fields for Stripe vs. PaymentSpring vs. Paypal. For now,
  //  methods that need these fields most be overridden case by case in order to tweak the query to check for all the
  //  possible variations. In the future, we should look to make these definitions gateway-specific, but it will take
  //  significant refactoring. It'd be easy enough to do a map lookup, using the gateway name, if we know it ahead of
  //  time (payment gateway webhook controllers, etc). But in some cases, we don't! Ex: PaymentGatewayService isn't
  //  currently told ahead of time what gateway to expect, so that wouldn't know which set of these fields to grab.
  //  Needs careful thought...
  public static class CRMFieldDefinitions {
    public String paymentGatewayName = "";
    public String paymentGatewayTransactionId = "";
    public String paymentGatewayCustomerId = "";
    public String paymentGatewaySubscriptionId = "";
    public String paymentGatewayRefundId = "";
    public String paymentGatewayRefundDate = "";
    public String paymentGatewayDepositId = "";
    public String paymentGatewayDepositDate = "";
    public String paymentGatewayDepositNetAmount = "";
    public String emailOptIn = "";
    public String smsOptIn = "";
  }

  public String currency = "usd";

  public static Environment init() {
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
      Environment envConfig = mapper.readValue(jsonDefault, Environment.class);
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
