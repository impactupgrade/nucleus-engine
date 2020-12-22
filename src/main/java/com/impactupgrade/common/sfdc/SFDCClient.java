package com.impactupgrade.common.sfdc;

import com.google.common.base.Strings;
import com.impactupgrade.common.environment.Environment;
import com.impactupgrade.common.util.LoggingUtil;
import com.impactupgrade.integration.sfdc.SFDCPartnerAPIClient;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class SFDCClient extends SFDCPartnerAPIClient {

  private static final Logger log = LogManager.getLogger(SFDCClient.class.getName());

  private static final String AUTH_URL;
  static {
    String profile = System.getenv("PROFILE");
    if ("production".equalsIgnoreCase(profile)) {
      AUTH_URL = "https://login.salesforce.com/services/Soap/u/47.0/";
    } else {
      AUTH_URL = "https://test.salesforce.com/services/Soap/u/47.0/";
    }
  }

  protected static final String SDF_DATE = "yyyy-MM-dd";

  protected final Environment env;

  public SFDCClient(Environment env, String username, String password) {
    super(
        username,
        password,
        AUTH_URL,
        20 // objects are massive, so toning down the batch sizes
    );
    this.env = env;
  }

  public SFDCClient(Environment env) {
    super(
        System.getenv("SFDC_USERNAME"),
        System.getenv("SFDC_PASSWORD"),
        AUTH_URL,
        20 // objects are massive, so toning down the batch sizes
    );
    this.env = env;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // ACCOUNTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String ACCOUNT_FIELDS = "id, OwnerId, name, email__c, phone, npo02__NumberOfClosedOpps__c, npo02__TotalOppAmount__c";
  // TODO: For now, keep this simple and allow apps to statically set custom fields to include. But eventually,
  // this should be config driven!
  public static String CUSTOM_ACCOUNT_FIELDS = "";

  public Optional<SObject> getAccountById(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(ACCOUNT_FIELDS, CUSTOM_ACCOUNT_FIELDS) + " from account where id = '" + accountId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CAMPAIGNS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String CAMPAIGN_FIELDS = "id, name";
  // TODO: For now, keep this simple and allow apps to statically set custom fields to include. But eventually,
  // this should be config driven!
  public static String CUSTOM_CAMPAIGN_FIELDS = "";
  public static String CUSTOM_DEFAULT_CAMPAIGN_ID = "";

  public Optional<SObject> getCampaignById(String campaignId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CAMPAIGN_FIELDS, CUSTOM_CAMPAIGN_FIELDS) + " from campaign where id = '" + campaignId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public Optional<SObject> getCampaignByIdOrDefault(String campaignId) throws ConnectionException, InterruptedException {
    if (!Strings.isNullOrEmpty(campaignId)) {
      Optional<SObject> campaign = getCampaignById(campaignId);
      if (campaign.isPresent()) {
        return campaign;
      }
    }

    if (!Strings.isNullOrEmpty(CUSTOM_DEFAULT_CAMPAIGN_ID)) {
      Optional<SObject> defaultCampaign = getCampaignById(CUSTOM_DEFAULT_CAMPAIGN_ID);
      return defaultCampaign;
    }

    log.warn("SFDC is missing a default campaign");
    return Optional.empty();
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CONTACTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String CONTACT_FIELDS = "Id, AccountId, OwnerId, FirstName, LastName, account.name, account.BillingStreet, account.BillingCity, account.BillingPostalCode, account.BillingState, account.BillingCountry, name, phone, email, npe01__Home_Address__c, mailingstreet, mailingcity, mailingstate, mailingpostalcode, mailingcountry, homephone, mobilephone, npe01__workphone__c, npe01__preferredphone__c";
  // TODO: For now, keep this simple and allow apps to statically set custom fields to include. But eventually,
  // this should be config driven!
  public static String CUSTOM_CONTACT_FIELDS = "";

  public Optional<SObject> getContactById(String contactId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, CUSTOM_CONTACT_FIELDS) + " from contact where id = '" + contactId + "' ORDER BY name";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getContactsByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, CUSTOM_CONTACT_FIELDS) + " from contact where accountId = '" + accountId + "' ORDER BY name";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public Optional<SObject> getContactByEmail(String email) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, CUSTOM_CONTACT_FIELDS) + " from contact where email = '" + email + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getContactsByEmail(String email) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(email)){
      return Collections.emptyList();
    }

    String query = "select " + getFieldsList(CONTACT_FIELDS, CUSTOM_CONTACT_FIELDS) + " from contact where email = '" + email + "' OR npe01__HomeEmail__c = '" + email + "' OR npe01__WorkEmail__c = '" + email + "' OR npe01__AlternateEmail__c = '" + email + "' ORDER BY name";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getContactsByName(String firstName, String lastName) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, CUSTOM_CONTACT_FIELDS) + " from contact where firstName = '" + firstName + "' and lastName = '" + lastName + "' ORDER BY name";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getDupContactsByName(String firstName, String lastName) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(firstName) && Strings.isNullOrEmpty(lastName)){
      return Collections.emptyList();
    }

    List<SObject> contacts = Collections.emptyList();

    if (!Strings.isNullOrEmpty(firstName) && !Strings.isNullOrEmpty(lastName)) {
      String query = "select " + CONTACT_FIELDS + " from contact where firstname = '" + firstName + "' AND lastname = '" + lastName + "'";
      LoggingUtil.verbose(log, query);
      contacts = queryList(query);
    }
    if (contacts.isEmpty()) {
      String query = "select " + CONTACT_FIELDS + " from contact where lastname = '" + lastName + "'";
      LoggingUtil.verbose(log, query);
      contacts = queryList(query);
    }

    return contacts;
  }

  public List<SObject> getDupContactsByPhone(String phone) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(phone)){
      return Collections.emptyList();
    }

    // TODO: Will need to rework this section for international support
    StringBuilder query = new StringBuilder("select " + CONTACT_FIELDS + " from contact where ");
    phone = phone.replaceAll("[\\D.]", "");
    if (phone.matches("\\d{10}")){
      String[] phoneArr = {phone.substring(0, 3), phone.substring(3, 6), phone.substring(6, 10)};
      query
          .append("phone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%'")
          .append(" OR HomePhone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%'")
          .append(" OR MobilePhone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%'")
          .append(" OR OtherPhone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%'");
      LoggingUtil.verbose(log, query.toString());
      return queryList(query.toString());
    } else if (phone.matches("\\d{11}")) {
      String[] phoneArr = {phone.substring(0, 1), phone.substring(1, 4), phone.substring(4, 7), phone.substring(7, 11)};
      query
          .append("phone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%").append(phoneArr[3]).append("%'")
          .append(" OR HomePhone LIKE '").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%").append(phoneArr[3]).append("%'")
          .append(" OR MobilePhone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%").append(phoneArr[3]).append("%'")
          .append(" OR OtherPhone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%").append(phoneArr[3]).append("%'");
      LoggingUtil.verbose(log, query.toString());
      return queryList(query.toString());
    } else {
      return Collections.emptyList();
    }
  }

  public List<SObject> getDupContactsByAddress(String street, String city, String state, String zip, String country) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(street)){
      return Collections.emptyList();
    }

    // TODO: Test and make sure this format actually works for a variety of addresses, or if we need to try several
    String address = street + ", " + city + ", " + state + " " + zip + ", " + country;
    LoggingUtil.verbose(log, address);
    String query = "select " + CONTACT_FIELDS + " from contact where npe01__Home_Address__c LIKE '" + street + "%'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> searchContacts(String searchParam) throws ConnectionException, InterruptedException {
    searchParam = searchParam.replaceAll("\\s+", " ").trim();
    List<String> segments = Arrays.asList(searchParam.split(" "));

    String clauses = segments.stream()
        .map(segment -> {
          // Note: default to NULL as a simple means of ensuring the phone number searches don't return any results...
          String possiblePhoneNumber = segment.replaceAll("\\D+", "").isEmpty()
              ? "NULL" : segment.replaceAll("\\D+", "");

          return "lastname LIKE '" + segment + "%'" +
              " OR firstname LIKE '" + segment + "%'" +
              " OR email LIKE '%" + segment + "%'" +
              " OR npe01__Home_Address__c LIKE '%" + segment + "%'" +
              " OR phone LIKE '%" + possiblePhoneNumber + "%'" +
              " OR MobilePhone LIKE '%" + possiblePhoneNumber + "%'" +
              " OR HomePhone LIKE '%" + possiblePhoneNumber + "%'" +
              " OR OtherPhone LIKE '%" + possiblePhoneNumber + "%'";
        })
        .collect(Collectors.joining(") AND (","(",")"));

    String query = "select " + CONTACT_FIELDS + " from contact where " + clauses + " ORDER BY account.name, name";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // DONATIONS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String DONATION_FIELDS = "id, AccountId, ContactId, amount, name, RecordTypeId, CampaignId, CloseDate, StageName, Type, npe03__Recurring_Donation__c";
  // TODO: For now, keep this simple and allow apps to statically set custom fields to include. But eventually,
  // this should be config driven!
  public static String CUSTOM_DONATION_FIELDS = "";

  public Optional<SObject> getDonationById(String donationId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, CUSTOM_DONATION_FIELDS) + " from Opportunity where id = '" + donationId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public Optional<SObject> getDonationByTransactionId(String transactionIdField, String transactionId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, CUSTOM_DONATION_FIELDS) + " from Opportunity where " + transactionIdField + " = '" + transactionId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getDonationsByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, CUSTOM_DONATION_FIELDS) + " from Opportunity where accountid = '" + accountId + "' AND StageName != 'Pledged' ORDER BY CloseDate DESC";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getFailingDonationsLastMonthByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, CUSTOM_DONATION_FIELDS) + " from Opportunity where stageName = 'Failed Attempt' AND CloseDate = LAST_MONTH AND AccountId = '" + accountId + "'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public Optional<SObject> getLatestPostedDonation(String recurringDonationId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, CUSTOM_DONATION_FIELDS) + " from Opportunity where npe03__Recurring_Donation__c = '" + recurringDonationId + "' and stageName = 'Posted' order by CloseDate desc limit 1";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<com.sforce.soap.partner.sobject.SObject> getDonationsInDeposit(String depositId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, CUSTOM_DONATION_FIELDS) + " from Opportunity where " + env.sfdcFieldOppDepositID() + " = '" + depositId + "'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public Optional<SObject> getNextPledgedDonationByRecurringDonationId(String recurringDonationId) throws ConnectionException, InterruptedException {
    // TODO: Using TOMORROW to account for timezone issues -- we can typically get away with that approach
    // since most RDs are monthly...
    String query = "select id, name, amount, CloseDate, AccountId, ContactId, npe03__Recurring_Donation__c, StageName, campaignid, Type from Opportunity where npe03__Recurring_Donation__c = '" + recurringDonationId + "' AND stageName = 'Pledged' AND CloseDate <= TOMORROW ORDER BY CloseDate Desc LIMIT 1";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // META
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Optional<SObject> getRecordTypeByName(String recordTypeName) throws ConnectionException, InterruptedException {
    String query = "select id from recordtype where name = '" + recordTypeName + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // RECURRING DONATIONS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String RECURRINGDONATION_FIELDS = "id, name, npe03__Recurring_Donation_Campaign__c, npe03__Recurring_Donation_Campaign__r.Name, npe03__Next_Payment_Date__c, npe03__Installment_Period__c, npe03__Amount__c, npe03__Open_Ended_Status__c, npe03__Contact__c, npsp__InstallmentFrequency__c";
  // TODO: For now, keep this simple and allow apps to statically set custom fields to include. But eventually,
  // this should be config driven!
  public static String CUSTOM_RECURRINGDONATION_FIELDS = "";

  public Optional<SObject> getRecurringDonationById(String id) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, CUSTOM_RECURRINGDONATION_FIELDS) + " from npe03__Recurring_Donation__c where id='" + id + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getRecurringDonationsBySubscriptionId(String subscriptionIdFieldName, String subscriptionId) throws ConnectionException, InterruptedException {
    String query = "select id, " + subscriptionIdFieldName + ", npe03__Open_Ended_Status__c, npe03__Amount__c, npe03__Recurring_Donation_Campaign__c, npe03__Installment_Period__c from npe03__Recurring_Donation__c where " + subscriptionIdFieldName + " = '" + subscriptionId + "'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getRecurringDonationsByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, CUSTOM_RECURRINGDONATION_FIELDS) + " from npe03__Recurring_Donation__c where (npe03__Open_Ended_Status__c != 'Closed' or paused_status__c != '') and npe03__Organization__c" + " = '" + accountId + "'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // USERS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String USER_FIELDS = "id, firstName, lastName, email, phone";
  // TODO: For now, keep this simple and allow apps to statically set custom fields to include. But eventually,
  // this should be config driven!
  public static String CUSTOM_USER_FIELDS = "";

  public Optional<SObject> getUserById(String userId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(USER_FIELDS, CUSTOM_USER_FIELDS) + " from user where id = '" + userId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public Optional<SObject> getUserByEmail(String email) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(USER_FIELDS, CUSTOM_USER_FIELDS) + " from user where isActive = true and email = '" + email + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  /**
   * Use with caution, it retrieves ALL active users. Unsuitable for orgs with many users.
   */
  public List<SObject> getActiveUsers() throws ConnectionException, InterruptedException {
    String query = "select id, firstName, lastName from user where isActive = true";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // DRY HELPERS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public SaveResult insert(SObject sObject) throws InterruptedException {
    SaveResult saveResult = super.insert(sObject);

    // for convenience, set the ID back on the sObject so it can be directly reused for further processing
    sObject.setId(saveResult.getId());

    return saveResult;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // INTERNAL
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected String getFieldsList(String list, String customList) {
    return customList.length() > 0 ? list + ", " + customList : list;
  }
}
