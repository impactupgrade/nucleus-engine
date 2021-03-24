package com.impactupgrade.common.crm.sfdc;

import com.google.common.base.Strings;
import com.impactupgrade.common.environment.Environment;
import com.impactupgrade.common.util.LoggingUtil;
import com.impactupgrade.integration.sfdc.SFDCPartnerAPIClient;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class SfdcClient extends SFDCPartnerAPIClient {

  private static final Logger log = LogManager.getLogger(SfdcClient.class.getName());

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

  public SfdcClient(Environment env, String username, String password) {
    super(
        username,
        password,
        AUTH_URL,
        20 // objects are massive, so toning down the batch sizes
    );
    this.env = env;
  }

  public SfdcClient(Environment env) {
    super(
        System.getenv("SFDC_USERNAME"),
        System.getenv("SFDC_PASSWORD"),
        AUTH_URL,
        20 // objects are massive, so toning down the batch sizes
    );
    this.env = env;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CUSTOM COLUMNS TO RETRIEVE IN QUERIES, TO BE OVERRIDDEN AS NEEDED
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected String accountFields() { return ""; }
  protected String campaignFields() { return ""; }
  protected String contactFields() { return ""; }
  protected String donationFields() { return ""; }
  protected String recurringDonationFields() { return ""; }
  protected String userFields() { return ""; }

  // Some clients needlessly split these into gateway-specific fields (one for Stripe, one for PS, etc). For now,
  // allow each to be a simple list. We'll include all of them in the queries.
  protected List<String> paymentGatewayDepositIdFields() {
    log.error("NOT IMPLEMENTED");
    return Collections.emptyList();
  }
  protected List<String> paymentGatewaySubscriptionIdFields() {
    log.error("NOT IMPLEMENTED");
    return Collections.emptyList();
  }
  protected List<String> paymentGatewayTransactionIdFields() {
    log.error("NOT IMPLEMENTED");
    return Collections.emptyList();
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // ACCOUNTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String ACCOUNT_FIELDS = "id, OwnerId, name, email__c, phone, npo02__NumberOfClosedOpps__c, npo02__TotalOppAmount__c";

  public Optional<SObject> getAccountById(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(ACCOUNT_FIELDS, accountFields()) + " from account where id = '" + accountId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getAccountsByName(String name) throws ConnectionException, InterruptedException {
    // Note the formal greeting -- super important, as that's often used in numerous imports/exports
    String query = "select " + getFieldsList(ACCOUNT_FIELDS, accountFields()) + " from account where name like '" + name + "' or npo02__Formal_Greeting__c='" + name + "'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CAMPAIGNS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String CAMPAIGN_FIELDS = "id, name, parentid, ownerid";

  public Optional<SObject> getCampaignById(String campaignId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CAMPAIGN_FIELDS, campaignFields()) + " from campaign where id = '" + campaignId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CONTACTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String CONTACT_FIELDS = "Id, AccountId, OwnerId, FirstName, LastName, account.id, account.name, account.BillingStreet, account.BillingCity, account.BillingPostalCode, account.BillingState, account.BillingCountry, name, phone, email, npe01__Home_Address__c, mailingstreet, mailingcity, mailingstate, mailingpostalcode, mailingcountry, homephone, mobilephone, npe01__workphone__c, npe01__preferredphone__c";

  public Optional<SObject> getContactById(String contactId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, contactFields()) + " from contact where id = '" + contactId + "' ORDER BY name";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getContactsByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, contactFields()) + " from contact where accountId = '" + accountId + "' ORDER BY name";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public Optional<SObject> getContactByEmail(String email) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(email)){
      return Optional.empty();
    }

    String query = "select " + getFieldsList(CONTACT_FIELDS, contactFields()) + " from contact where email = '" + email + "' OR npe01__HomeEmail__c = '" + email + "' OR npe01__WorkEmail__c = '" + email + "' OR npe01__AlternateEmail__c = '" + email + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getContactsByName(String name) throws ConnectionException, InterruptedException {
    String escapedName = name.replaceAll("'", "\\\\'");
    String query = "select " + getFieldsList(CONTACT_FIELDS, contactFields()) + " from contact where name like '%" + escapedName + "%' ORDER BY name";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getContactsByName(String firstName, String lastName) throws ConnectionException, InterruptedException {
    String escapedFirstName = firstName.replaceAll("'", "\\\\'");
    String escapedLastName = lastName.replaceAll("'", "\\\\'");
    String query = "select " + getFieldsList(CONTACT_FIELDS, contactFields()) + " from contact where firstName = '" + escapedFirstName + "' and lastName = '" + escapedLastName + "' ORDER BY name";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getDupContactsByName(String firstName, String lastName) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(firstName) && Strings.isNullOrEmpty(lastName)){
      return Collections.emptyList();
    }

    List<SObject> contacts = Collections.emptyList();

    if (!Strings.isNullOrEmpty(firstName) && !Strings.isNullOrEmpty(lastName)) {
      String query = "select " + getFieldsList(CONTACT_FIELDS, contactFields()) + " from contact where firstname = '" + firstName + "' AND lastname = '" + lastName + "'";
      LoggingUtil.verbose(log, query);
      contacts = queryList(query);
    }
    if (contacts.isEmpty()) {
      String query = "select " + getFieldsList(CONTACT_FIELDS, contactFields()) + " from contact where lastname = '" + lastName + "'";
      LoggingUtil.verbose(log, query);
      contacts = queryList(query);
    }

    return contacts;
  }

  public List<SObject> getContactsByPhone(String phone) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(phone)){
      return Collections.emptyList();
    }

    // TODO: Will need to rework this section for international support
    StringBuilder query = new StringBuilder("select " + getFieldsList(CONTACT_FIELDS, contactFields()) + " from contact where ");
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

  public List<SObject> getContactsByAddress(String street, String city, String state, String zip, String country) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(street)){
      return Collections.emptyList();
    }

    // TODO: Test and make sure this format actually works for a variety of addresses, or if we need to try several
    String address = street + ", " + city + ", " + state + " " + zip + ", " + country;
    LoggingUtil.verbose(log, address);
    String query = "select " + getFieldsList(CONTACT_FIELDS, contactFields()) + " from contact where npe01__Home_Address__c LIKE '" + street + "%'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> searchContacts(String firstName, String lastName, String email, String phone, String address)
      throws ConnectionException, InterruptedException {
    ArrayList<String> searchParams = new ArrayList<>();

    if (!Strings.isNullOrEmpty(firstName) && !Strings.isNullOrEmpty(lastName)) {
      searchParams.add("(firstname LIKE '%" + firstName + "%' AND lastname LIKE '%" + lastName + "%')");
    } else {
      if (!Strings.isNullOrEmpty(firstName)) {
        searchParams.add("firstname LIKE '%" + firstName + "%'");
      }
      if (!Strings.isNullOrEmpty(lastName)) {
        searchParams.add("lastname LIKE '%" + lastName + "%'");
      }
    }

    if (!Strings.isNullOrEmpty(email)) {
      searchParams.add("email LIKE '%" + email + "%'");
    }
    if (!Strings.isNullOrEmpty(phone)) {
      String phoneClean = phone.replaceAll("\\D+", "");
      phoneClean = phoneClean.replaceAll("", "%");
      if (!phoneClean.isEmpty()) {
        searchParams.add("phone LIKE '" + phoneClean + "'");
        searchParams.add("MobilePhone LIKE '" + phoneClean + "'");
        searchParams.add("HomePhone LIKE '" + phoneClean + "'");
        searchParams.add("OtherPhone LIKE '" + phoneClean + "'");
      }
    }
    if (!Strings.isNullOrEmpty(address)) {
      searchParams.add("npe01__Home_Address__c LIKE '%" + address + "%'");
    }

    String clauses = String.join( " OR ", searchParams);

    String query = "select " + getFieldsList(CONTACT_FIELDS, contactFields()) + " from contact where " + clauses + " ORDER BY account.name, name";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // DONATIONS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String DONATION_FIELDS = "id, AccountId, ContactId, amount, name, RecordTypeId, CampaignId, Campaign.ParentId, CloseDate, StageName, Type, npe03__Recurring_Donation__c";

  public Optional<SObject> getDonationById(String donationId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, donationFields()) + " from Opportunity where id = '" + donationId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public Optional<SObject> getDonationByTransactionId(String transactionId) throws ConnectionException, InterruptedException {
    String transactionIdClauses = paymentGatewayTransactionIdFields().stream().map(t -> t + " = '" + transactionId + "'").collect(Collectors.joining(" OR "));
    String query = "select " + getFieldsList(DONATION_FIELDS, donationFields()) + " from Opportunity where " + transactionIdClauses;
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getDonationsByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, donationFields()) + " from Opportunity where accountid = '" + accountId + "' AND StageName != 'Pledged' ORDER BY CloseDate DESC";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getFailingDonationsLastMonthByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, donationFields()) + " from Opportunity where stageName = 'Failed Attempt' AND CloseDate = LAST_MONTH AND AccountId = '" + accountId + "'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public Optional<SObject> getLatestPostedDonation(String recurringDonationId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, donationFields()) + " from Opportunity where npe03__Recurring_Donation__c = '" + recurringDonationId + "' and stageName = 'Posted' order by CloseDate desc limit 1";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getDonationsInDeposit(String depositId) throws ConnectionException, InterruptedException {
    String depositIdClauses = paymentGatewayDepositIdFields().stream().map(d -> d + " = '" + depositId + "'").collect(Collectors.joining(" OR "));
    String query = "select " + getFieldsList(DONATION_FIELDS, donationFields()) + " from Opportunity where " + depositIdClauses;
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public Optional<SObject> getNextPledgedDonationByRecurringDonationId(String recurringDonationId) throws ConnectionException, InterruptedException {
    // TODO: Using TOMORROW to account for timezone issues -- we can typically get away with that approach
    // since most RDs are monthly...
    String query = "select " + getFieldsList(DONATION_FIELDS, donationFields()) + " from Opportunity where npe03__Recurring_Donation__c = '" + recurringDonationId + "' AND stageName = 'Pledged' AND CloseDate <= TOMORROW ORDER BY CloseDate Desc LIMIT 1";
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

  public Optional<SObject> getRecurringDonationById(String id) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, recurringDonationFields()) + " from npe03__Recurring_Donation__c where id='" + id + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public Optional<SObject> getRecurringDonationBySubscriptionId(String subscriptionId) throws ConnectionException, InterruptedException {
    String subscriptionIdClauses = paymentGatewaySubscriptionIdFields().stream().map(s -> s + " = '" + subscriptionId + "'").collect(Collectors.joining(" OR "));
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, recurringDonationFields()) + " from npe03__Recurring_Donation__c where " + subscriptionIdClauses;
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // USERS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String USER_FIELDS = "id, name, firstName, lastName, email, phone";

  public Optional<SObject> getUserById(String userId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(USER_FIELDS, userFields()) + " from user where id = '" + userId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public Optional<SObject> getUserByEmail(String email) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(USER_FIELDS, userFields()) + " from user where isActive = true and email = '" + email + "'";
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
  // INTERNAL
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected String getFieldsList(String list, String customList) {
    return customList.length() > 0 ? list + ", " + customList : list;
  }
}
