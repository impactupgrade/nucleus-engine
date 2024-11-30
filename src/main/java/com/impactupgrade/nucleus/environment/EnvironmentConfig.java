/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.environment;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.google.common.base.Strings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
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
public class EnvironmentConfig implements Serializable {

  private static final Logger log = LogManager.getLogger(EnvironmentConfig.class.getName());

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // HIGH-LEVEL CONFIG
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // NOTE: We use Set for collections of Strings. When the JSON files are merged together, this prevents duplicate values.

  // TODO: Once all orgs are using the DB and not local enviroment.json files, this could be removed and code updated
  //  to use the API Key field on the database row itself.
  public String apiKey = "";

  // Some flows will select a platform by name, when appropriate (especially if kicked off by the Portal).
  // Other flows may be specific to one platform or another, depending on the org. MOST will only have one, in which case
  // primary should be set to true. But some will have a split of something like Salesforce for donors and HubSpot for
  // marketing, the latter used for SMS/email. In that case, primary is still treated as the default, but individual
  // flows can be overridden.
  public String crmPrimary = "";
  public String crmDonations = "";
  public String crmMessaging = "";
  public String accountingPrimary = "";

  public String emailTransactional = "";

  public String currency = "";
  public String timezoneId = "";

  public MetadataKeys metadataKeys = new MetadataKeys();
  public static class MetadataKeys implements Serializable {
    public Set<String> account = new HashSet<>();
    public Set<String> campaign = new HashSet<>();
    public Set<String> fund = new HashSet<>();
    public Set<String> contact = new HashSet<>();
    public Set<String> recordType = new HashSet<>();
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CRM
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // TODO: This currently assumes a CRM only has one single set of fields, agnostic to the specific gateway. But that's
  //  not often the case! Ex: LJI and TER have separate sets of fields for Stripe vs. PaymentSpring vs. Paypal. For now,
  //  methods that need these fields most be overridden case by case in order to tweak the query to check for all the
  //  possible variations. In the future, we should look to make these definitions gateway-specific, but it will take
  //  significant refactoring. It'd be easy enough to do a map lookup, using the gateway name, if we know it ahead of
  //  time (payment gateway webhook controllers, etc). But in some cases, we don't! Ex: PaymentGatewayService isn't
  //  currently told ahead of time what gateway to expect, so that wouldn't know which set of these fields to grab.
  //  Needs careful thought...
  public static class CRMFieldDefinitions implements Serializable {
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
    public String paymentGatewayFailureReason = "";
    public String fund = ""; // donation designation
    public String paymentMetadata = ""; // store all metadata from the triggering event, such as a Stripe charge

    public String emailOptIn = "";
    public boolean listFilterOverridesOptIn = false; // do not automatically factor the field into the communication sync's filter
    public String emailOptOut = "";
    public String emailBounced = "";
    public String emailGroups = "";

    public String accountEmailOptIn = "";
    public boolean accountListFilterOverridesOptIn = false; // do not automatically factor the field into the communication sync's filter
    public String accountEmailOptOut = "";
    public String accountEmailBounced = "";

    public String smsOptIn = "";
    public String smsOptOut = "";

    public String activityExternalReference = "";

    public String accountEmail = "";
    public String contactLanguage = "";

    public String sisContactId = "";
    public String sisHouseholdId = "";

    public String campaignExternalReference = "";

    // TODO: refactor all of the above into record-specific definitions (below)

    public ContactCRMFieldDefinitions contact = new ContactCRMFieldDefinitions();
    public DonationCRMFieldDefinitions donation = new DonationCRMFieldDefinitions();
  }
  public static class ContactCRMFieldDefinitions implements Serializable {
    public String utmSource = "";
    public String utmCampaign = "";
    public String utmMedium = "";
    public String utmTerm = "";
    public String utmContent = "";
  }
  public static class DonationCRMFieldDefinitions implements Serializable {
    public String utmSource = "";
    public String utmCampaign = "";
    public String utmMedium = "";
    public String utmTerm = "";
    public String utmContent = "";
  }
  public enum AccountType {
    HOUSEHOLD, ORGANIZATION
  }
  public enum TransactionType {
    DONATION, TICKET
  }

  public Bloomerang bloomerang = new Bloomerang();
  // For now, don't actually need the CRMFieldDefinitions...
  public static class Bloomerang extends Platform {
    public Integer defaultFundId = null;
    public CRMFieldDefinitions fieldDefinitions = new CRMFieldDefinitions();
  }

  public DynamicsPlatform dynamicsPlatform = new DynamicsPlatform();
  public static class DynamicsPlatform extends Platform {
    public String tenantId = "";
    public String resourceUrl = "";
    public CRMFieldDefinitions fieldDefinitions = new CRMFieldDefinitions();
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
    public CommunicationPlatform email = new CommunicationPlatform();
  }
  public static class HubSpotDonationPipeline implements Serializable {
    public String id = "";
    public String successStageId = "";
    public String failedStageId = "";
    public String refundedStageId = "";
  }
  public static class HubSpotRecurringDonationPipeline implements Serializable {
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
  public static class HubspotCustomFields implements Serializable {
    public Set<String> company = new HashSet<>();
    public Set<String> contact = new HashSet<>();
    public Set<String> deal = new HashSet<>();
  }

  public Salesforce salesforce = new Salesforce();
  public static class Salesforce extends Platform {
    // default this to true since it was the original default if omitted
    // TODO: I've only started wiring this into SfdcCrmService. At the moment, I'm purely focused on getting
    //  SMS tools in Nucleus Portal to work. Still needs to be comprehensively used.
    public boolean npsp = true;
    // some differences in fields/operations
    public boolean enhancedRecurringDonations = false;
    public boolean accountHasRecordTypes = true;
    public boolean campaignHasRecordTypes = false;
    public boolean donationHasRecordTypes = true;

    public boolean sandbox = false;
    public String url = "";
    public String forceUrl = "";

    public CRMFieldDefinitions fieldDefinitions = new CRMFieldDefinitions();
    public SalesforceCustomFields customQueryFields = new SalesforceCustomFields();

    public String defaultCampaignId = "";

    public Map<AccountType, String> accountTypeToRecordTypeIds = new HashMap<>();
    public Map<TransactionType, String> transactionTypeToRecordTypeIds = new HashMap<>();
  }
  public static class SalesforceCustomFields implements Serializable {
    public Set<String> account = new HashSet<>();
    public Set<String> campaign = new HashSet<>();
    public Set<String> contact = new HashSet<>();
    public Set<String> lead = new HashSet<>();
    public Set<String> donation = new HashSet<>();
    public Set<String> recurringDonation = new HashSet<>();
    public Set<String> task = new HashSet<>();
    public Set<String> user = new HashSet<>();
  }

  public SharePointPlatform sharePoint = new SharePointPlatform();
  public static class SharePointPlatform extends Platform {
    public String tenantId = "";
    public String rootSiteHostname = "";
    public String subSiteName = "";
    public List<String> filePaths = new ArrayList<>();
    public String idColumn = "";
    public String ownerColumn = "";
    public String emailColumn = "";
    public String phoneColumn = "";
    public List<String> searchColumnsToSkip = new ArrayList<>();
  }

  public VirtuousPlatform virtuous = new VirtuousPlatform();
  public static class VirtuousPlatform extends Platform {
    public CRMFieldDefinitions fieldDefinitions = new CRMFieldDefinitions();
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // PAYMENT GATEWAY
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Stripe stripe = new Stripe();
  public static class Stripe extends Platform {
    public List<Expression> filteringExpressions = new ArrayList<>();
  }

  public Paypal paypal = new Paypal();
  public static class Paypal extends Platform {
    //TODO: filtering expressions same as for Stripe?
    public String mode;
    public String webhookId;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // ACCOUNTING
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Xero xero = new Xero();
  public static class Xero extends Platform {
    public String tenantId = "";
    public String accountId = "";
    public String accountCode = "";
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // COMMS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public enum CommunicationListType {
    MARKETING, TRANSACTIONAL
  }

  public static class CommunicationList implements Serializable {
    public String id = "";
    public CommunicationListType type = CommunicationListType.MARKETING;
    public Map<String, String> groups = new HashMap<>(); // <Name, ID>
    public String crmFilter = "";
    public String crmLeadFilter = "";
    public String crmAccountFilter = "";
    public String crmCampaignMemberFilter = "";
  }

  public static class CrmFieldToCommunicationField implements Serializable {
    public String crmFieldName;
    public String communicationFieldName;
  }

  public static class CrmFieldToCommunicationTag implements Serializable {
    public String crmFieldName;
    public Operator operator;
    public String value;
    public String communicationTagName;
  }

  public static class CommunicationPlatform extends Platform {
    public List<CommunicationList> lists = new ArrayList<>();

    // The set of tags that this instance of Nucleus controls, which defines which tags we're allowed to remove from
    // the contact once the conditions are no longer met. This ensures any of the client's manually-defined tags
    // are preserved.
    // TODO: Until we introduce something like PIKL, there's not a great way to define this in environment-default.json
    //  since it needs to happen within the list of individual instances.
    public Set<String> defaultControlledTags = new HashSet<>(Arrays.asList("donor", "owner_", "campaign_", "account_type_"));
    public Set<String> customControlledTags = new HashSet<>();

    public List<CrmFieldToCommunicationField> crmFieldToCommunicationFields = new ArrayList<>();
    public List<CrmFieldToCommunicationTag> crmFieldToCommunicationTags = new ArrayList<>();

    // Transactional email (donation receipts, notifications, etc.) need one of the email platforms to be
    // designated as the conduit!
    public boolean transactionalSender = false;

    // TODO: Email assumed to be enabled by default and this is not currently used. If the types of platforms expands,
    //  we may need to work this into the code.
    public Boolean enableEmail = true;
    public Boolean enableSms = false;

    public String country;
    public String countryCode;
  }

  public List<CommunicationPlatform> mailchimp = new ArrayList<>();

  public List<CommunicationPlatform> constantContact = new ArrayList<>();

  public List<MBT> ministrybytext = new ArrayList<>();
  public static class MBT extends CommunicationPlatform {
    public String campusId = "";
  }

  public List<CommunicationPlatform> sendgrid = new ArrayList<>();

  public Twilio twilio = new Twilio();
  public static class Twilio extends Platform {
    public String senderPn = "";
    public String defaultResponse = "";
    public Map<String, TwilioUser> users = new HashMap<>();
  }
  public static class TwilioUser implements Serializable {
    public String senderPn;
    public boolean recordOwnerFilter = true;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // SIS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Facts facts = new Facts();
  public static class Facts extends Platform {
    // For CRMs, we sync parents first -- it helps ensure a cleaner household with the correct primary contacts.
    // But for some purposes (ex: NSU), we need the student first. Allow the logic to be overriden!
    public boolean syncStudentsBeforeParents = false;
    // Sync emergency contacts, in addition to parents/grandparents?
    public boolean syncEmergency = false;
  }

  // secretKey = Eventbrite private token
  public Platform eventBrite = new Platform();

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // FORMS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // NOTE: ODK is a data collection platform that we're using with a client, supporting offline mobile forms.
  // That code currently lives in the client's unique instance of Nucleus, but we have plans to shift the common
  // pieces here since ODK could be super useful in other nonprofits. For the moment, setting up env.json to support
  // the connectivity.
  public Odk odk = new Odk();
  public static class Odk extends Platform {
    public String url = "";
    public List<OdkProject> projects = new ArrayList<>();
  }
  public static class OdkProject implements Serializable {
    public Integer projectId = null;
    public String form = "";
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // OPS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public static class Notification implements Serializable {
    public String from = "";
    public Set<String> to = new HashSet<>();
  }

  public static class Task implements Serializable {
    public String assignTo = "";
  }

  public static class Notifications implements Serializable {
    public Notification email = null;
    public Notification sms = null;
    public Task task = null;
  }

  public Map<String, Notifications> notifications = new HashMap<>();

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // FILES
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Backblaze backblaze = new Backblaze();
  public static class Backblaze extends Platform {
    public String bucketId = "";
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // SECURITY/AUTH
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Recaptcha recaptcha = new Recaptcha();
  public static class Recaptcha implements Serializable {
    @Deprecated public String siteSecret = "";
    public String v2SiteSecret = "";
    public String v3SiteSecret = "";
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // INSTRUMENTATION/MONITORING/LOGGING
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Platform rollbar = new Platform();

  public Set<String> loggers = isDatabaseConnected() ? new HashSet<>(List.of("console", "db")) : new HashSet<>(List.of("console"));

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // COMMON
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public static class Platform implements Serializable {
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
    public Long expiresAt = 0L;
    public String refreshToken = "";
  }

  public enum Operator {
    EQUAL_TO("=="),
    NOT_EQUAL_TO("!="),
    NOT_EMPTY("!=''");

    private final String value;

    Operator(String value) {
      this.value = value;
    }

    @JsonValue
    public String getValue() {
      return value;
    }
  }

  public static class Expression implements Serializable {
    public String key;
    public String operator;
    public String value;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // INITIALIZATION
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private static final String PROFILE = System.getenv("PROFILE");
  public String getProfile() {
    return PROFILE;
  }

  private static final String DATABASE_CONNECTED = System.getenv("DATABASE_CONNECTED");
  public boolean isDatabaseConnected() {
    return "true".equalsIgnoreCase(DATABASE_CONNECTED);
  }

  private static final boolean IS_PROD = "production".equalsIgnoreCase(PROFILE);
  private static final boolean IS_SANDBOX = "sandbox".equalsIgnoreCase(PROFILE);
  private static final String OTHER_JSON_FILENAME = System.getenv("OTHER_JSON_FILENAME");

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
            .getResourceAsStream("environment-sandbox.json");
        InputStream jsonOther = OTHER_JSON_FILENAME == null ? null : Thread.currentThread().getContextClassLoader().getResourceAsStream(OTHER_JSON_FILENAME)
    ) {
      // Start with the default JSON as the foundation.
      EnvironmentConfig envConfig = mapper.readValue(jsonDefault, EnvironmentConfig.class);

      if (IS_PROD && jsonOrg != null) {
        mapper.readerForUpdating(envConfig).readValue(jsonOrg);
      }

      if (IS_SANDBOX && jsonOrgSandbox != null) {
        mapper.readerForUpdating(envConfig).readValue(jsonOrgSandbox);
      }

      if (jsonOther != null) {
        log.debug("Including {} in the environment...", OTHER_JSON_FILENAME);
        mapper.readerForUpdating(envConfig).readValue(jsonOther);
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
    } catch (MismatchedInputException e) {
      // swallow it -- this is the Exception thrown when the org has an empty body
    } catch (JsonProcessingException e) {
      log.error("Unable to read environment JSON! {}", jsonOrg, e);
    }
  }
}
