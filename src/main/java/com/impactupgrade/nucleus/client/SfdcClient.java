/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.impactupgrade.integration.sfdc.SFDCPartnerAPIClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.HttpClient;
import com.impactupgrade.nucleus.util.LoggingUtil;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jruby.embed.LocalContextScope;
import org.jruby.embed.PathType;
import org.jruby.embed.ScriptingContainer;

import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class SfdcClient extends SFDCPartnerAPIClient {

  private static final Logger log = LogManager.getLogger(SfdcClient.class.getName());

  public static final String AUTH_URL_PRODUCTION = "https://login.salesforce.com/services/Soap/u/47.0/";
  public static final String AUTH_URL_SANDBOX = "https://test.salesforce.com/services/Soap/u/47.0/";

  private static final String AUTH_URL;
  static {
    String profile = System.getenv("PROFILE");
    if ("production".equalsIgnoreCase(profile)) {
      AUTH_URL = AUTH_URL_PRODUCTION;
    } else {
      AUTH_URL = AUTH_URL_SANDBOX;
    }
  }

  protected final Environment env;

  public SfdcClient(Environment env, String username, String password, boolean isSandbox) {
    super(
        username,
        password,
        isSandbox ? AUTH_URL_SANDBOX : AUTH_URL_PRODUCTION,
        20 // objects are massive, so toning down the batch sizes
    );
    this.env = env;
  }

  public SfdcClient(Environment env, String username, String password) {
    this(
        env,
        username,
        password,
        env.getConfig().salesforce.sandbox
    );
  }

  public SfdcClient(Environment env) {
    this(
        env,
        env.getConfig().salesforce.username,
        env.getConfig().salesforce.password,
        env.getConfig().salesforce.sandbox
    );
  }
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // ACCOUNTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String ACCOUNT_FIELDS = "id, OwnerId, name, phone, BillingStreet, BillingCity, BillingPostalCode, BillingState, BillingCountry, npo02__NumberOfClosedOpps__c, npo02__TotalOppAmount__c, RecordTypeId";

  public Optional<SObject> getAccountById(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account) + " from account where id = '" + accountId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getAccountsByName(String name) throws ConnectionException, InterruptedException {
    String escapedName = name.replaceAll("'", "\\\\'");
    // Note the formal greeting -- super important, as that's often used in numerous imports/exports
    String query = "select " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account) + " from account where name like '%" + escapedName + "%' or npo02__Formal_Greeting__c='%" + escapedName + "%'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public Optional<SObject> getAccountByCustomerId(String customerId) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(customerId) || Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId)) {
      return Optional.empty();
    }
    String query = "select " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account) + " from account where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId + " = '" + customerId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CAMPAIGNS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String CAMPAIGN_FIELDS = "id, name, parentid, ownerid, RecordTypeId";

  public Optional<SObject> getCampaignById(String campaignId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CAMPAIGN_FIELDS, env.getConfig().salesforce.customQueryFields.campaign) + " from campaign where id = '" + campaignId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public Optional<SObject> getCampaignByName(String campaignName) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CAMPAIGN_FIELDS, env.getConfig().salesforce.customQueryFields.campaign) + " from campaign where name = '" + campaignName + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CONTACTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String CONTACT_FIELDS = "Id, AccountId, OwnerId, FirstName, LastName, account.id, account.name, account.BillingStreet, account.BillingCity, account.BillingPostalCode, account.BillingState, account.BillingCountry, name, phone, email, npe01__Home_Address__c, mailingstreet, mailingcity, mailingstate, mailingpostalcode, mailingcountry, homephone, mobilephone, npe01__workphone__c, npe01__preferredphone__c";

  public Optional<SObject> getContactById(String contactId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where id = '" + contactId + "' ORDER BY name";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getContactsByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where accountId = '" + accountId + "' ORDER BY name";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public Optional<SObject> getContactByEmail(String email) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(email)){
      return Optional.empty();
    }

    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where email = '" + email + "' OR npe01__HomeEmail__c = '" + email + "' OR npe01__WorkEmail__c = '" + email + "' OR npe01__AlternateEmail__c = '" + email + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  // the context map allows overrides to be given additional hints (such as DR's FNs)
  public List<SObject> getContactsByName(String name, Map<String, String> context) throws ConnectionException, InterruptedException {
    String escapedName = name.replaceAll("'", "\\\\'");
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where name like '%" + escapedName + "%' ORDER BY name";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  // the context map allows overrides to be given additional hints (such as DR's FNs)
  public List<SObject> getContactsByName(String firstName, String lastName, Map<String, String> context) throws ConnectionException, InterruptedException {
    String escapedFirstName = firstName.replaceAll("'", "\\\\'");
    String escapedLastName = lastName.replaceAll("'", "\\\\'");
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where firstName = '" + escapedFirstName + "' and lastName = '" + escapedLastName + "' ORDER BY name";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getDupContactsByName(String firstName, String lastName) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(firstName) && Strings.isNullOrEmpty(lastName)){
      return Collections.emptyList();
    }

    List<SObject> contacts = Collections.emptyList();

    if (!Strings.isNullOrEmpty(firstName) && !Strings.isNullOrEmpty(lastName)) {
      String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where firstname = '" + firstName + "' AND lastname = '" + lastName + "'";
      LoggingUtil.verbose(log, query);
      contacts = queryList(query);
    }
    if (contacts.isEmpty()) {
      String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where lastname = '" + lastName + "'";
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

    phone = phone.replaceAll("[\\D.]", "");
    if (phone.length() == 11) {
      phone = phone.substring(1);
    }

    String[] phoneArr = {phone.substring(0, 3), phone.substring(3, 6), phone.substring(6, 10)};

    StringBuilder query = new StringBuilder("select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where ")
        .append("phone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%'")
        .append(" OR HomePhone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%'")
        .append(" OR MobilePhone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%'")
        .append(" OR OtherPhone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%'");
    LoggingUtil.verbose(log, query.toString());
    return queryList(query.toString());
  }

  public List<SObject> getContactsByAddress(String street, String city, String state, String zip, String country) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(street)){
      return Collections.emptyList();
    }

    // TODO: Test and make sure this format actually works for a variety of addresses, or if we need to try several
    String address = street + ", " + city + ", " + state + " " + zip + ", " + country;
    LoggingUtil.verbose(log, address);
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where npe01__Home_Address__c LIKE '" + street + "%'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getContactsByCampaignId(String campaignId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where id in (select contactid from CampaignMember where id='" + campaignId + "' and contactid != null)";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

//  public List<SObject> getContactsByReportId(String reportId) throws ConnectionException, InterruptedException, IOException {
//    if (Strings.isNullOrEmpty(reportId)) {
//      return Collections.emptyList();
//    }
//
//    String reportDescription = getReportDescription(reportId);
//    if (Strings.isNullOrEmpty(reportDescription)) {
//      log.error("Failed to get report description! {}", reportId);
//      return Collections.emptyList();
//    }
//
//    // Check report type is supported
//    String reportType = getReportType(reportDescription);
//    Set<String> supportedTypes = env.getConfig().salesforce.supportedContactsReportTypes;
//    if (!supportedTypes.contains(reportType)) {
//      log.warn("Report type {} is not supported! Supported types: {}.", reportType, supportedTypes);
//      log.warn("Can NOT load contacts from report {}!", reportId);
//      return Collections.emptyList();
//    }
//
//    // Get report columns as a map (csv column label -> report column key)
//    Map<String, String> reportColumns = getReportColumns(reportDescription);
//    if (CollectionUtils.isEmpty(reportColumns.keySet())) {
//      // Should be unreachable
//      log.error("Report {} does not have any columns defined!", reportId);
//      return Collections.emptyList();
//    }
//    Set<String> supportedColumns = env.getConfig().salesforce.supportedContactReportColumns;
//    // Check if report contains supported columns
//    Set<String> filteredColumnsLabels = reportColumns.keySet().stream()
//            .filter(reportColumnLabel -> supportedColumns.contains(reportColumns.get(reportColumnLabel)))
//            .collect(Collectors.toSet());
//
//    if (CollectionUtils.isEmpty(filteredColumnsLabels)) {
//      log.warn("Report {} does not contain any of supported columns: {}", reportId, supportedColumns);
//      log.warn("Can NOT load contacts from report {}!", reportId);
//      return Collections.emptyList();
//    }
//
//    // Get the report as csv
//    String reportContent = downloadReportAsString(reportId);
//
//    // Get report content as a map of (columnLabel -> columnValues)
//    Map<String, List<String>> columnValues = new HashMap<>();
//    CsvMapper mapper = new CsvMapper();
//    CsvSchema schema = CsvSchema.emptySchema().withHeader();
//    MappingIterator<Map<String, String>> iterator = mapper.readerFor(Map.class).with(schema).readValues(reportContent);
//    while (iterator.hasNext()) {
//      Map<String, String> row = iterator.next();
//      row.entrySet().stream()
//        // Collect only values for filtered (searchable) columns
//        .filter(e -> filteredColumnsLabels.contains(e.getKey()))
//        .filter(e -> !Strings.isNullOrEmpty(e.getValue()))
//        .forEach(e -> {
//          columnValues.computeIfAbsent(e.getKey(), c -> new ArrayList<>());
//          columnValues.get(e.getKey()).add(e.getValue());
//        });
//    }
//
//    log.info("column values: {}", columnValues);
//
//    // Select one of supported columns values (getting one that has the most values defined in csv)
//    String searchColumnLabel = null;
//    int biggestSize = 0;
//    for (Map.Entry<String, List<String>> e: columnValues.entrySet()) {
//      int valuesSize = e.getValue().size();
//      if (valuesSize > biggestSize) {
//        biggestSize = valuesSize;
//        searchColumnLabel = e.getKey();
//      }
//    }
//
//    String searchColumn = reportColumns.get(searchColumnLabel);
//    String searchColumnValues = String.join(",", columnValues.get(searchColumnLabel).stream().map(label -> "'" + label + "'").collect(Collectors.toList()));
//
//    // Get contacts using column key and csv values
//    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where " + searchColumn + " in (" + searchColumnValues + ")";
//    LoggingUtil.verbose(log, query);
//    return queryList(query);
//  }

  public List<SObject> getContactsByReportId(String reportId) throws ConnectionException, InterruptedException, IOException {
    if (Strings.isNullOrEmpty(reportId)) {
      return Collections.emptyList();
    }

    // Get the report as csv
    String reportContent = downloadReportAsString(reportId);

    // NOTE: Rather than return a Map of the results, we instead require the report to include an ID column, then
    // use that to run a normal query to grab all fields we care about.

    // Get report content as a map of (columnLabel -> columnValues)
    List<String> resultIds = new ArrayList<>();
    CsvMapper mapper = new CsvMapper();
    CsvSchema schema = CsvSchema.emptySchema().withHeader();
    MappingIterator<Map<String, String>> iterator = mapper.readerFor(Map.class).with(schema).readValues(reportContent);
    while (iterator.hasNext()) {
      Map<String, String> row = iterator.next();
      row.entrySet().stream()
          // Collect only values for filtered (searchable) columns
          .filter(e -> "id".equalsIgnoreCase(e.getKey()) || "contact id".equalsIgnoreCase(e.getKey()))
          .filter(e -> !Strings.isNullOrEmpty(e.getValue()))
          .forEach(e -> {
            resultIds.add(e.getValue());
          });
    }

    log.info("report contained {} contacts", resultIds.size());
    if (resultIds.isEmpty()) {
      return Collections.emptyList();
    } else {
      // Get contacts using ID column
      String where = resultIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
      String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where id in (" + where + ")";
      LoggingUtil.verbose(log, query);
      return queryList(query);
    }
  }

//  public String getReportDescription(String reportId) {
//    if (Strings.isNullOrEmpty(reportId)) {
//      return null;
//    }
//    // TODO: no secretKeys for SFDC -- needs to use a login call to get a sessionId, like SfdcBulkClient
//    String accessToken = env.getConfig().salesforce.secretKey;
//    String baseUrl = env.getConfig().salesforce.url;
//    // TODO: make version config param?
//    String describeReportUrl = "https://" + baseUrl + "/services/data/v53.0/analytics/reports/" + reportId + "/describe";
//    return HttpClient.getAsString(describeReportUrl, MediaType.APPLICATION_JSON, accessToken);
//  }
//
//  private String getReportType(String reportDescription) {
//    if (Strings.isNullOrEmpty(reportDescription)) {
//      return null;
//    }
//    JSONObject jsonObject = new JSONObject(reportDescription);
//    String reportType = jsonObject
//            .getJSONObject("reportMetadata")
//            .getJSONObject("reportType")
//            .getString("type");
//    log.info("report type: {}", reportType);
//    return reportType;
//  }
//
//  private Map<String, String> getReportColumns(String reportDescription) {
//    if (Strings.isNullOrEmpty(reportDescription)) {
//      return null;
//    }
//    JSONObject jsonObject = new JSONObject(reportDescription);
//    Map<String, String> reportColumns = new HashMap<>();
//    JSONArray categories = jsonObject.getJSONObject("reportTypeMetadata").getJSONArray("categories");
//    for (int i = 0 ; i < categories.length(); i++) {
//      JSONObject categoryColumns = categories.getJSONObject(i).getJSONObject("columns");
//      categoryColumns.keySet().forEach(columnKey -> {
//        reportColumns.put(categoryColumns.getJSONObject(columnKey).getString("label"), columnKey);
//      });
//    }
//    return reportColumns;
//  }

  private String downloadReportAsString(String reportId) throws IOException {
    log.info("downloading report file from SFDC...");

    // using jruby to kick off the ruby script -- see https://github.com/carojkov/salesforce-export-downloader
    ScriptingContainer container = new ScriptingContainer(LocalContextScope.THREADSAFE);
    container.getEnvironment().put("SFDC_USERNAME", env.getConfig().salesforce.username);
    container.getEnvironment().put("SFDC_PASSWORD", env.getConfig().salesforce.password);
    container.getEnvironment().put("SFDC_URL", env.getConfig().salesforce.url);
    container.getEnvironment().put("SFDC_REPORT_ID", reportId);
    container.runScriptlet(PathType.CLASSPATH, "salesforce-downloader/salesforce-report.rb");

    log.info("report downloaded!");

    File reportFile = new File("report-salesforce/" + reportId + ".csv");
    return Files.readString(reportFile.toPath(), StandardCharsets.UTF_8);
  }

  public List<SObject> getContactsByOpportunityName(String opportunityName) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where id in (select contactid from Opportunity where name='" + opportunityName + "' and contactid != null)";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getContactsUpdatedSince(Calendar calendar) throws ConnectionException, InterruptedException {
    String dateTimeString = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(calendar.getTime());
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where LastModifiedDate >= " + dateTimeString;
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getDonorContactsSince(Calendar calendar) throws ConnectionException, InterruptedException {
    String dateTimeString = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(calendar.getTime());
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where id in (select ContactId from Opportunity where CreatedDate >= " + dateTimeString + " and Amount >= 0.0 and (StageName='posted' or StageName='closed won'))";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getAllContacts() throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getAllDonorContacts() throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where id in (select ContactId from Opportunity where Amount >= 0.0 and (StageName='posted' or StageName='closed won'))";
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

    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where " + clauses + " ORDER BY account.name, name";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // DONATIONS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String DONATION_FIELDS = "id, AccountId, ContactId, amount, name, RecordTypeId, CampaignId, Campaign.ParentId, CloseDate, StageName, Type, npe03__Recurring_Donation__c";

  public Optional<SObject> getDonationById(String donationId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where id = '" + donationId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public Optional<SObject> getDonationByTransactionId(String transactionId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewayTransactionId + " = '" + transactionId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getDonationsByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where accountid = '" + accountId + "' AND StageName != 'Pledged' ORDER BY CloseDate DESC";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getFailingDonationsLastMonthByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where stageName = 'Failed Attempt' AND CloseDate = LAST_MONTH AND AccountId = '" + accountId + "'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public Optional<SObject> getLatestPostedDonation(String recurringDonationId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where npe03__Recurring_Donation__c = '" + recurringDonationId + "' and stageName = 'Posted' order by CloseDate desc limit 1";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getDonationsInDeposit(String depositId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositId + " = '" + depositId + "'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public List<SObject> getRefundsInDeposit(String depositId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDepositId + " = '" + depositId + "'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public Optional<SObject> getNextPledgedDonationByRecurringDonationId(String recurringDonationId) throws ConnectionException, InterruptedException {
    // TODO: Using TOMORROW to account for timezone issues -- we can typically get away with that approach
    // since most RDs are monthly...
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where npe03__Recurring_Donation__c = '" + recurringDonationId + "' AND stageName = 'Pledged' AND CloseDate <= TOMORROW ORDER BY CloseDate Desc LIMIT 1";
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

  protected static final String RECURRINGDONATION_FIELDS = "id, name, npe03__Recurring_Donation_Campaign__c, npe03__Recurring_Donation_Campaign__r.Name, npe03__Next_Payment_Date__c, npe03__Installment_Period__c, npe03__Amount__c, npe03__Open_Ended_Status__c, npe03__Contact__c, npsp__InstallmentFrequency__c, npe03__Schedule_Type__c, npe03__Date_Established__c";

  public Optional<SObject> getRecurringDonationById(String id) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation) + " from npe03__Recurring_Donation__c where id='" + id + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public Optional<SObject> getRecurringDonationByName(String name) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation) + " from npe03__Recurring_Donation__c where name='" + name + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public Optional<SObject> getRecurringDonationBySubscriptionId(String subscriptionId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation) + " from npe03__Recurring_Donation__c where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewaySubscriptionId + " = '" + subscriptionId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public List<SObject> getRecurringDonationsByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation) + " from npe03__Recurring_Donation__c where Npe03__Organization__c = '" + accountId + "'";
    LoggingUtil.verbose(log, query);
    return queryList(query);
  }

  public void refreshRecurringDonation(String donationId) throws ConnectionException {
    // TODO: set up 'FORCE_URL' env var and the appropriate apex enpoint for orgs so they this will work
    log.info("refreshing opportunities on {}...", donationId);

    String data = "{\"recurringDonationId\": \"" + donationId + "\"}";
    String sessionId = login().getSessionId();

    String sfdcEndpoint = env.getConfig().salesforce.forceUrl + "/services/apexrest/refreshrecurringdonation";

    Response response = HttpClient.postJson(data, sessionId, sfdcEndpoint);
    log.info("SFDC refresh recurring donation response: {}", response.getStatus());
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // USERS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String USER_FIELDS = "id, name, firstName, lastName, email, phone";

  public Optional<SObject> getUserById(String userId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(USER_FIELDS, env.getConfig().salesforce.customQueryFields.user) + " from user where id = '" + userId + "'";
    LoggingUtil.verbose(log, query);
    return querySingle(query);
  }

  public Optional<SObject> getUserByEmail(String email) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(USER_FIELDS, env.getConfig().salesforce.customQueryFields.user) + " from user where isActive = true and email = '" + email + "'";
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

  protected String getFieldsList(String list, Collection<String> customList) {
    return customList.size() > 0 ? list + ", " + Joiner.on(", ").join(customList) : list;
  }
}
