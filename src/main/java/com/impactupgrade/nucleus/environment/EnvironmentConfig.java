/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.environment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whenever possible, we focus on being configuration-driven using one, large JSON file.
 * See environment-default.json for the base layer! Then, organizations can provide their own environment.json file
 * to overwrite specific values from the default. environment.json is assumed to be on the thread's classpath.
 */
public class EnvironmentConfig {

  private static final Logger log = LogManager.getLogger(EnvironmentConfig.class.getName());

  // NOTE: We use Set for collections of Strings. When the JSON files are merged together, this prevents duplicate values.

  // TODO: Once all orgs are using the DB and not local enviroment.json files, this could be removed and code updated
  //  to use the API Key field on the database row itself.
  public String apiKey = "";

  // Some flows will select a CRM by name, when appropriate (especially if kicked off by the Portal).
  // Other flows may be specific to one CRM or another, depending on the org. MOST will only have one, in which case
  // primary should be set to true. But some will have a split of something like Salesforce for donors and HubSpot for
  // marketing, the latter used for SMS/email. In that case, primary is still treated as the default, but individual
  // flows can be overridden.
  // TODO: Currently, if no override, we'll simply grab the "primary" from the list. In the future, likely after
  //  Camel is introduced, this will change to instead allow a flow to be run multiple times, one per CRM. Ex: we may
  //  want to push donations to multiple CRMs at once.
  public String crmPrimary = "";
  public String crmDonations = "";
  public String crmMessaging = "";

  public String emailTransactional = "";

  public static class Platform {
    // if keys
    public String publicKey = "";
    public String secretKey = "";

    // if basic auth
    public String username = "";
    public String password = "";

    // if OAuth
    public String clientId = "";
    public String clientSecret = "";
    public String tokenServerUrl = "";
    public String accessToken = "";
    public String refreshToken = "";
  }

  public Platform stripe = new Platform();

  public static class Notification {
    public String from = "";
    public Set<String> to = new HashSet<>();
  }

  public static class Task {
    public String assignTo = "";
  }

  public static class Notifications {
    public Notification email = null;
    public Notification sms = null;
    public Task task = null;
  }

  public Map<String, Notifications> notifications = new HashMap<>();

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
    public String paymentGatewayRefundDepositId = "";
    public String paymentGatewayRefundDepositDate = "";

    public String paymentGatewayDepositId = "";
    public String paymentGatewayDepositDate = "";
    public String paymentGatewayDepositNetAmount = "";
    public String paymentGatewayDepositFee = "";

    // donation designation
    public String fund = "";

    public String emailOptIn = "";
    public String emailOptOut = "";
    public String emailGroups = "";

    public String smsOptIn = "";
    public String smsOptOut = "";

    public String contactLanguage = "";
  }

  public Bloomerang bloomerang = new Bloomerang();

  // For now, don't actually need the CRMFieldDefinitions...
  public static class Bloomerang extends Platform {
    public String anonymousId = "";
  }

  public Hubspot hubspot = new Hubspot();

  public static class Hubspot extends Platform {
    public String portalId = "";
    public HubSpotDonationPipeline donationPipeline = new HubSpotDonationPipeline();
    public HubSpotRecurringDonationPipeline recurringDonationPipeline = new HubSpotRecurringDonationPipeline();
    public HubspotCRMFieldDefinitions fieldDefinitions = new HubspotCRMFieldDefinitions();
    public HubspotCustomFields customQueryFields = new HubspotCustomFields();
    public String defaultSmsOptInList = "";
    public boolean enableRecurring = false;
    public EmailPlatform email = new EmailPlatform();

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

    public static class HubspotCRMFieldDefinitions extends CRMFieldDefinitions {
      // TODO: Although this one is specifically for a HS shortcoming (no deal-to-deal association)...
      public String recurringDonationDealId = "";
      // HS assumes monthly, so we need a custom prop to define the true frequency...
      public String recurringDonationFrequency = "";
      // Similarly, we need to know the original, true amount of the recurring gift, regardless of the frequency.
      public String recurringDonationRealAmount = "";
      // SFDC has its own currency conversion setup on Opportunity, but HS' is statically defined. Use custom props...
      public String paymentGatewayAmountOriginal = "";
      public String paymentGatewayAmountOriginalCurrency = "";
      public String paymentGatewayAmountExchangeRate = "";
    }

    public static class HubspotCustomFields {
      public Set<String> company = new HashSet<>();
      public Set<String> contact = new HashSet<>();
      public Set<String> deal = new HashSet<>();
    }
  }

  public Salesforce salesforce = new Salesforce();

  public static class Salesforce extends Platform {
    public boolean sandbox = false;
    public String url = "";
    public String forceUrl = "";

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

    public Set<String> supportedContactsReportTypes = new HashSet<>();
    public Set<String> supportedContactReportColumns = new HashSet<>();
  }

  public Donorwrangler donorwrangler = new Donorwrangler();

  public static class Donorwrangler extends Platform {
    public String subdomain = "";
  }

  public Twilio twilio = new Twilio();

  public static class Twilio extends Platform {
    public String senderPn = "";
  }

  public enum EmailListType {
    CONTACTS, DONORS
  }

  public static class EmailTagFilters {
    public Integer majorDonorAmount = null;
    public Integer recentDonorDays = null;
    public Integer frequentDonorCount = null;
  }

  public static class EmailList {
    public String id = "";
    public EmailListType type = EmailListType.CONTACTS;
    public Map<String, String> groups = new HashMap<>(); // <Name, ID>
    public String crmFilter = "";
  }

  public static class EmailPlatform extends Platform {
    public List<EmailList> lists = new ArrayList<>();
    public EmailTagFilters tagFilters = new EmailTagFilters();
    // Transactional email (donation receipts, notifications, etc.) need one of the email platforms to be
    // designated as the conduit!
    public boolean transactionalSender = false;
  }

  public List<EmailPlatform> mailchimp = new ArrayList<>();
  public List<EmailPlatform> sendgrid = new ArrayList<>();

  public Backblaze backblaze = new Backblaze();

  public static class Backblaze extends Platform {
    public String bucketId = "";
  }

  public Recaptcha recaptcha = new Recaptcha();

  public static class Recaptcha {
    public String siteSecret = "";
  }

  // NOTE: ODK is a data collection platform that we're using with a client, supporting offline mobile forms.
  // That code currently lives in the client's unique instance of Nucleus, but we have plans to shift the common
  // pieces here since ODK could be super useful in other nonprofits. For the moment, setting up env.json to support
  // the connectivity.
  public Odk odk = new Odk();
  public static class Odk extends Platform {
    public String url = "";
    public List<OdkProject> projects = new ArrayList<>();
  }
  public static class OdkProject {
    public Integer projectId = null;
    public String form = "";
  }

  public Platform virtuous = new Platform();

  public MetadataKeys metadataKeys = new MetadataKeys();

  public static class MetadataKeys {
    public Set<String> account = new HashSet<>();
    public Set<String> campaign = new HashSet<>();
    public Set<String> fund = new HashSet<>();
    public Set<String> contact = new HashSet<>();
    public Set<String> recordType = new HashSet<>();
  }

  public String currency = "";

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // INITIALIZATION
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private static final boolean IS_PROD = "production".equalsIgnoreCase(System.getenv("PROFILE"));
  private static final boolean IS_SANDBOX = "sandbox".equalsIgnoreCase(System.getenv("PROFILE"));

  private static final ObjectMapper mapper = new ObjectMapper();
  static {
    // Allows nested objects, collections, etc. to be merged together.
    mapper.setDefaultMergeable(true);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  public static EnvironmentConfig init() {
    try (
        InputStream jsonDefault = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("environment-default.json");
        InputStream jsonOrg = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("environment.json");
        InputStream jsonOrgSandbox = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("environment-sandbox.json")
    ) {
      // Start with the default JSON as the foundation.
      EnvironmentConfig envConfig = mapper.readValue(jsonDefault, EnvironmentConfig.class);

      if (IS_PROD && jsonOrg != null) {
        mapper.readerForUpdating(envConfig).readValue(jsonOrg);
      }

      if (IS_SANDBOX && jsonOrgSandbox != null) {
        mapper.readerForUpdating(envConfig).readValue(jsonOrgSandbox);
      }

      return envConfig;
    } catch (IOException e) {
      log.error("Unable to read environment JSON files! Exiting...", e);
      System.exit(1);
      return null;
    }
  }

  // Allow additional env.json files to be added. This cannot be statically defined, due to usages such as integration
  // tests where tests run in parallel and each may need unique setups.
  public void addOtherJson(String otherJsonFilename) {
    if (!Strings.isNullOrEmpty(otherJsonFilename)) {
      try (InputStream otherJson = Thread.currentThread().getContextClassLoader()
          .getResourceAsStream(otherJsonFilename)) {
        if (otherJson != null) {
          mapper.readerForUpdating(this).readValue(otherJson);
        }
      } catch (IOException e) {
        log.error("unable to read {}}", otherJsonFilename, e);
      }
    }
  }

  /**
   * Nucleus Core (and perhaps other use cases) need to dynamically provide the org's JSON from a database lookup.
   * Assume it will always start with environment-default.json (using the static init() method). Overlay the
   * unique JSON on top of what we already have.
   */
  public void init(String jsonOrg) {
    try {
      mapper.readerForUpdating(this).readValue(jsonOrg);
    } catch (JsonProcessingException e) {
      log.error("Unable to read environment JSON!", e);
    }
  }
}
