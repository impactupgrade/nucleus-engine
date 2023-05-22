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
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.util.HttpClient;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jruby.embed.LocalContextScope;
import org.jruby.embed.PathType;
import org.jruby.embed.ScriptingContainer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.util.HttpClient.post;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

public class SfdcClient extends SFDCPartnerAPIClient {

  private static final Logger log = LogManager.getLogger(SfdcClient.class.getName());

  public static final String AUTH_URL_PRODUCTION = "https://login.salesforce.com/services/Soap/u/55.0/";
  public static final String AUTH_URL_SANDBOX = "https://test.salesforce.com/services/Soap/u/55.0/";

  // SOQL has a 100k char limit for queries, so we're arbitrarily defining the page sizes...
  protected static final int MAX_ID_QUERY_LIST_SIZE = 1000;

  protected static final String AUTH_URL;
  static {
    String profile = System.getenv("PROFILE");
    if ("production".equalsIgnoreCase(profile)) {
      AUTH_URL = AUTH_URL_PRODUCTION;
    } else {
      AUTH_URL = AUTH_URL_SANDBOX;
    }
  }

  protected final Environment env;

  protected String ACCOUNT_FIELDS;
  protected String CAMPAIGN_FIELDS;
  protected String CONTACT_FIELDS;
  protected String LEAD_FIELDS;
  protected String DONATION_FIELDS;
  protected String RECURRINGDONATION_FIELDS;
  protected String USER_FIELDS;
  protected String REPORT_FIELDS;

  public SfdcClient(Environment env) {
    this(
        env,
        env.getConfig().salesforce.username,
        env.getConfig().salesforce.password,
        env.getConfig().salesforce.sandbox
    );
  }

  public SfdcClient(Environment env, String username, String password) {
    this(
        env,
        username,
        password,
        env.getConfig().salesforce.sandbox
    );
  }

  public SfdcClient(Environment env, String username, String password, boolean isSandbox) {
    super(
        username,
        password,
        isSandbox ? AUTH_URL_SANDBOX : AUTH_URL_PRODUCTION,
        200
    );
    this.env = env;

    boolean npsp = env.getConfig().salesforce.npsp;

    ACCOUNT_FIELDS = "id, OwnerId, name, phone, BillingStreet, BillingCity, BillingPostalCode, BillingState, BillingCountry";
    CAMPAIGN_FIELDS = "id, name, parentid, ownerid";
    // TODO: Finding a few clients with no homephone, so taking that out for now.
    CONTACT_FIELDS = "Id, AccountId, OwnerId, Owner.Id, Owner.Name, FirstName, LastName, Account.Id, Account.Name, Account.BillingStreet, Account.BillingCity, Account.BillingPostalCode, Account.BillingState, Account.BillingCountry, name, email, mailingstreet, mailingcity, mailingstate, mailingpostalcode, mailingcountry, CreatedDate, MobilePhone, Phone";
    LEAD_FIELDS = "Id, FirstName, LastName, Email";
    DONATION_FIELDS = "id, AccountId, Account.Id, Account.Name, Account.RecordTypeId, Account.RecordType.Id, Account.RecordType.Name, ContactId, Amount, Name, RecordTypeId, RecordType.Id, RecordType.Name, CampaignId, Campaign.ParentId, CloseDate, StageName, Type, Description, OwnerId";
    USER_FIELDS = "id, name, firstName, lastName, email, phone";
    REPORT_FIELDS = "Id, Name";

    if (npsp) {
      // TODO: Record Types are not NPSP specific, but we have yet to see them in a commercial context. IE, NPSP always
      //  has them due to the Household vs. Organization default, but commercial entities have a flatter setup.
      //  IF we need these commercially, needs to become a configurable option in env.json. Unfortunately, SFDC
      //  returns a query error if you try to query them but no record types exist for that object.
      ACCOUNT_FIELDS += ", RecordTypeId, RecordType.Id, RecordType.Name, npo02__NumberOfClosedOpps__c, npo02__TotalOppAmount__c, npo02__LastCloseDate__c, npo02__LargestAmount__c, npo02__OppsClosedThisYear__c, npo02__OppAmountThisYear__c";
      // TODO: Same point about NPSP.
      CONTACT_FIELDS += ", Account.RecordTypeId, Account.RecordType.Id, Account.RecordType.Name, account.npo02__NumberOfClosedOpps__c, account.npo02__TotalOppAmount__c, account.npo02__FirstCloseDate__c, account.npo02__LastCloseDate__c, account.npo02__LargestAmount__c, account.npo02__OppsClosedThisYear__c, account.npo02__OppAmountThisYear__c, npe01__Home_Address__c, npe01__workphone__c, npe01__preferredphone__c";
      DONATION_FIELDS += ", npe03__Recurring_Donation__c";
      RECURRINGDONATION_FIELDS = "id, name, npe03__Recurring_Donation_Campaign__c, npe03__Recurring_Donation_Campaign__r.Name, npe03__Next_Payment_Date__c, npe03__Installment_Period__c, npe03__Amount__c, npe03__Open_Ended_Status__c, npe03__Contact__c, npe03__Contact__r.Id, npe03__Contact__r.Name, npe03__Contact__r.Email, npe03__Contact__r.Phone, npe03__Schedule_Type__c, npe03__Date_Established__c, npe03__Organization__c, npe03__Organization__r.Id, npe03__Organization__r.Name";
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // ACCOUNTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Optional<SObject> getAccountById(String accountId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account, extraFields) + " from account where id = '" + accountId + "'";
    return querySingle(query);
  }
  public List<SObject> getAccountsByIds(List<String> ids, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(ids, "Id", "Account", ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account, extraFields);
  }
  public List<SObject> getAccountsByUniqueField(String fieldName, List<String> values, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(values, fieldName, "Account", ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account, extraFields);
  }

  public List<SObject> getAccountsByName(String name, String... extraFields) throws ConnectionException, InterruptedException {
    String escapedName = name.replaceAll("'", "\\\\'");
    // Note the formal greeting -- super important, as that's often used in numerous imports/exports
    String query = "select " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account, extraFields) + " from account where name like '%" + escapedName + "%' or npo02__Formal_Greeting__c='%" + escapedName + "%'";
    return queryList(query);
  }
  public List<SObject> getAccountsByNames(List<String> names, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(names, "Name", "Account", ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account, extraFields);
  }

  public List<SObject> getAllOrganizations(String... extraFields) throws ConnectionException, InterruptedException {
    String query;
    if (env.getConfig().salesforce.npsp) {
      query = "select " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account, extraFields) + " from account where RecordType.Name!='Household Account'";
    } else {
      query = "select " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account, extraFields) + " from account";
    }
    return queryListAutoPaged(query);
  }

  public Optional<SObject> getAccountByCustomerId(String customerId, String... extraFields) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(customerId) || Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId)) {
      return Optional.empty();
    }
    String query = "select " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account, extraFields) + " from account where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId + " = '" + customerId + "'";
    return querySingle(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CAMPAIGNS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public List<SObject> getCampaigns(String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CAMPAIGN_FIELDS, env.getConfig().salesforce.customQueryFields.campaign, extraFields) + " from campaign";
    return queryListAutoPaged(query);
  }

  public Optional<SObject> getCampaignById(String campaignId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CAMPAIGN_FIELDS, env.getConfig().salesforce.customQueryFields.campaign, extraFields) + " from campaign where id = '" + campaignId + "'";
    return querySingle(query);
  }
  public List<SObject> getCampaignsByIds(List<String> ids, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(ids, "Id", "Campaign", CAMPAIGN_FIELDS, env.getConfig().salesforce.customQueryFields.campaign, extraFields);
  }

  public Optional<SObject> getCampaignByName(String campaignName, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CAMPAIGN_FIELDS, env.getConfig().salesforce.customQueryFields.campaign, extraFields) +  " from campaign where name = '" + campaignName.replaceAll("'", "\\\\'") + "'";
    return querySingle(query);
  }
  public List<SObject> getCampaignsByNames(List<String> names, String... extraFields) throws ConnectionException, InterruptedException {
    names = names.stream().map(n -> n.replaceAll("'", "\\\\'")).collect(Collectors.toList());
    return getBulkResults(names, "Name", "Campaign", CAMPAIGN_FIELDS, env.getConfig().salesforce.customQueryFields.campaign, extraFields);
  }

  // See note on CrmService.getEmailCampaignsByContactIds. Retrieve in batches to preserve API limits!
  public List<SObject> getEmailCampaignsByContactIds(List<String> contactIds) throws ConnectionException, InterruptedException {
    // TODO: Note the use of CampaignMember -- currently need the name only, but could refactor to use CAMPAIGN_FIELDS on the child object.

    List<String> page;
    List<String> more;
    if (contactIds.size() > MAX_ID_QUERY_LIST_SIZE) {
      page = contactIds.subList(0, MAX_ID_QUERY_LIST_SIZE);
      more = contactIds.subList(MAX_ID_QUERY_LIST_SIZE, contactIds.size());
    } else {
      page = contactIds;
      more = Collections.emptyList();
    }

    String contactIdsJoin = page.stream().map(contactId -> "'" + contactId + "'").collect(Collectors.joining(","));
    String query = "select ContactId, Campaign.Name from CampaignMember where ContactId in (" + contactIdsJoin + ") and Campaign.IsActive=true";
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.emailCampaignInclusion)) {
      query += " AND Campaign." + env.getConfig().salesforce.fieldDefinitions.emailCampaignInclusion + "=TRUE";
    }
    List<SObject> results = queryListAutoPaged(query);

    if (!more.isEmpty()) {
      results.addAll(getEmailCampaignsByContactIds(more));
    }

    return results;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CONTACTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Optional<SObject> getContactById(String contactId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where id = '" + contactId + "' ORDER BY name";
    return querySingle(query);
  }
  public Optional<SObject> getFilteredContactById(String contactId, String filter, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where id = '" + contactId + "' and " + filter + " ORDER BY name";
    return querySingle(query);
  }
  // TODO: If we need another one of these, think through a better pattern to apply filters.
  public Optional<SObject> getFilteredContactByEmail(String email, String filter, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where email = '" + email + "' and " + filter + " ORDER BY name";
    return querySingle(query);
  }
  public List<SObject> getContactsByIds(List<String> ids, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(ids, "Id", "Contact", CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields);
  }
  public List<SObject> getContactsByUniqueField(String fieldName, List<String> values, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(values, fieldName, "Contact", CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields);
  }

  public List<SObject> getContactsByAccountId(String accountId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where accountId = '" + accountId + "' ORDER BY name";
    return queryList(query);
  }

  // the context map allows overrides to be given additional hints (such as DR's FNs)
  public List<SObject> getContactsByName(String name, Map<String, String> context, String... extraFields) throws ConnectionException, InterruptedException {
    String escapedName = name.replaceAll("'", "\\\\'");
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where name like '%" + escapedName + "%' ORDER BY name";
    return queryList(query);
  }

  // the context map allows overrides to be given additional hints (such as DR's FNs)
  public List<SObject> getContactsByName(String firstName, String lastName, Map<String, String> context, String... extraFields) throws ConnectionException, InterruptedException {
    String escapedFirstName = firstName.replaceAll("'", "\\\\'");
    String escapedLastName = lastName.replaceAll("'", "\\\\'");
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where firstName = '" + escapedFirstName + "' and lastName = '" + escapedLastName + "' ORDER BY name";
    return queryList(query);
  }

  public List<SObject> getContactsByNames(List<String> names, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(names, "Name", "Contact", CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields);
  }

  public List<SObject> getDupContactsByName(String firstName, String lastName, String... extraFields) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(firstName) && Strings.isNullOrEmpty(lastName)){
      return Collections.emptyList();
    }

    List<SObject> contacts = Collections.emptyList();

    if (!Strings.isNullOrEmpty(firstName) && !Strings.isNullOrEmpty(lastName)) {
      String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where firstname = '" + firstName.replaceAll("'", "\\\\'") + "' AND lastname = '" + lastName.replaceAll("'", "\\\\'") + "'";
      contacts = queryList(query);
    }
    if (contacts.isEmpty()) {
      String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where lastname = '" + lastName.replaceAll("'", "\\\\'") + "'";
      contacts = queryList(query);
    }

    return contacts;
  }

  public List<SObject> getContactsByAddress(String street, String city, String state, String zip, String country, String... extraFields) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(street)){
      return Collections.emptyList();
    }

    // TODO: Test and make sure this format actually works for a variety of addresses, or if we need to try several
    String address = street + ", " + city + ", " + state + " " + zip + ", " + country;
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where npe01__Home_Address__c LIKE '" + street + "%'";
    return queryList(query);
  }

  public List<SObject> getContactsByCampaignId(String campaignId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where id in (select contactid from CampaignMember where id='" + campaignId + "' and contactid != null)";
    return queryListAutoPaged(query);
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
//    return queryListAutoPaged(query);
//  }

  public List<SObject> getContactsByReportId(String reportId, String... extraFields) throws ConnectionException, InterruptedException, IOException {
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
          // Important to include this, as some CSV exports include footer details that get picked up as one column rows.
          .filter(e -> e.getValue().startsWith("003"))
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
      String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where id in (" + where + ")";
      return queryListAutoPaged(query);
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

  public List<SObject> getContactsByOpportunityName(String opportunityName, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where id in (select contactid from Opportunity where name='" + opportunityName.replaceAll("'", "\\\\'") + "' and contactid != null)";
    return queryListAutoPaged(query);
  }

  public Collection<SObject> getEmailContacts(Calendar updatedSince, String filter, String... extraFields) throws ConnectionException, InterruptedException {
    String updatedSinceClause = "";

    if (updatedSince != null) {
      updatedSinceClause = " and SystemModStamp >= " + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(updatedSince.getTime());
    }
    List<SObject> contacts = getEmailContacts(updatedSinceClause, filter, extraFields);

    if (updatedSince != null) {
      updatedSinceClause = " and Id IN (SELECT ContactId FROM CampaignMember WHERE SystemModStamp >= " + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(updatedSince.getTime()) + ")";
    }
    contacts.addAll(getEmailContacts(updatedSinceClause, filter, extraFields));

    // SOQL has no DISTINCT clause, and GROUP BY has tons of caveats, so we're filtering out duplicates in-mem.
    Map<String, SObject> uniqueContacts = contacts.stream().collect(Collectors.toMap(
        so -> so.getField("Email").toString(),
        Function.identity(),
        // FIFO
        (so1, so2) -> so1
    ));
    return uniqueContacts.values();
  }

  protected List<SObject> getEmailContacts(String updatedSinceClause, String filter, String... extraFields) throws ConnectionException, InterruptedException {
    if (!Strings.isNullOrEmpty(filter)) {
      filter = " and " + filter;
    }

    String optInOutFilters = "";
    // If env.json defines an emailOptIn, automatically factor that into the query.
    // IMPORTANT: If env.json defines emailOptOut, also include those contacts in this query! This might seem backwards,
    // but we need them in the results so that we can archive them in Mailchimp.
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.emailOptIn) && !Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.emailOptOut)) {
      optInOutFilters = " AND (" + env.getConfig().salesforce.fieldDefinitions.emailOptIn + "=TRUE OR " + env.getConfig().salesforce.fieldDefinitions.emailOptOut + "=TRUE)";
    } else if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.emailOptIn)) {
      optInOutFilters = " AND " + env.getConfig().salesforce.fieldDefinitions.emailOptIn + "=TRUE";
    }

    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where Email != null" + updatedSinceClause + filter + optInOutFilters;
    return queryListAutoPaged(query);
  }

  public PagedResults<SObject> searchContacts(ContactSearch contactSearch, String... extraFields)
      throws ConnectionException, InterruptedException {
    List<String> clauses = new ArrayList<>();

    if (contactSearch.hasEmail != null) {
      if (contactSearch.hasEmail) {
        clauses.add("email != null AND email != ''");
      } else {
        clauses.add("email = null OR email = ''");
      }
    }

    if (!Strings.isNullOrEmpty(contactSearch.email)) {
      clauses.add("email = '" + contactSearch.email + "' OR npe01__HomeEmail__c = '" + contactSearch.email + "' OR npe01__WorkEmail__c = '" + contactSearch.email + "' OR npe01__AlternateEmail__c = '" + contactSearch.email + "'");
    }

    if (!Strings.isNullOrEmpty(contactSearch.phone)) {
      String phone = contactSearch.phone.replaceAll("[\\D.]", "");
      if (phone.length() == 11) {
        phone = phone.substring(1);
      }
      String[] phoneArr = {phone.substring(0, 3), phone.substring(3, 6), phone.substring(6, 10)};
      // TODO: Finding a few clients with no homephone, so taking that out for now.
      StringBuilder phoneClause = new StringBuilder()
          .append("Phone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%'")
          .append(" OR MobilePhone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%'");
      clauses.add(phoneClause.toString());
    }

    // TODO: Finding a few clients with no homephone, so taking that out for now.
    if (contactSearch.hasPhone != null) {
      if (contactSearch.hasPhone) {
        clauses.add("((Phone != null AND Phone != '') OR (MobilePhone != null AND MobilePhone != ''))");
      } else {
        clauses.add("(Phone = null OR Phone = '') AND (MobilePhone = null OR MobilePhone = '')");
      }
    }

    if (!Strings.isNullOrEmpty(contactSearch.accountId)) {
      clauses.add("AccountId = '" + contactSearch.accountId + "'");
    }

    if (!Strings.isNullOrEmpty(contactSearch.ownerId)) {
      clauses.add("OwnerId = '" + contactSearch.ownerId + "'");
    }

    if (!Strings.isNullOrEmpty(contactSearch.keywords)) {
      String[] keywordSplit = contactSearch.keywords.trim().split("\\s+");
      for (String keyword : keywordSplit) {
        // TODO: Finding a few clients with no homephone, so taking that out for now.
        clauses.add("(FirstName LIKE '%" + keyword + "%' OR LastName LIKE '%" + keyword + "%' OR Email LIKE '%" + keyword + "%' OR Phone LIKE '%" + keyword + "%' OR MobilePhone LIKE '%" + keyword + "%' OR npe01__Home_Address__c LIKE '%" + keyword + "%')");
      }
    }

    String fullClause = String.join( " AND ", clauses);
    String query ="select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where " + fullClause + " ORDER BY LastName, FirstName";

    if (contactSearch.pageSize != null && contactSearch.pageSize > 0) {
      query += " LIMIT " + contactSearch.pageSize;
    }
    Integer offset = contactSearch.getPageOffset();
    if (offset != null && offset > 0) {
      query += " OFFSET " + offset;
    }

    List<SObject> results = queryList(query);
    return PagedResults.getPagedResultsFromCurrentOffset(results, contactSearch);
  }

  public List<SObject> getContactsByEmails(List<String> emails, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(emails, "Email", "Contact", CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // LEADS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Collection<SObject> getEmailLeads(Calendar updatedSince, String filter, String... extraFields) throws ConnectionException, InterruptedException {
    String updatedSinceClause = "";
    if (updatedSince != null) {
      updatedSinceClause = " and SystemModStamp >= " + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(updatedSince.getTime());
    }

    if (!Strings.isNullOrEmpty(filter)) {
      filter = " and " + filter;
    }

    String query = "select " + getFieldsList(LEAD_FIELDS, env.getConfig().salesforce.customQueryFields.lead, extraFields) +  " from lead where Email != null" + updatedSinceClause + filter;
    List<SObject> leads = queryListAutoPaged(query);

    // SOQL has no DISTINCT clause, and GROUP BY has tons of caveats, so we're filtering out duplicates in-mem.
    Map<String, SObject> uniqueLeads = leads.stream().collect(Collectors.toMap(
        so -> so.getField("Email").toString(),
        Function.identity(),
        // FIFO
        (so1, so2) -> so1
    ));
    return uniqueLeads.values();
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // DONATIONS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Optional<SObject> getDonationById(String donationId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields) +  " from Opportunity where id = '" + donationId + "'";
    return querySingle(query);
  }

  public List<SObject> getDonationsByIds(List<String> ids, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(ids, "Id", "Opportunity", DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields);
  }


  public List<SObject> getDonationsByUniqueField(String fieldName, List<String> values, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(values, fieldName, "Opportunity", DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields);
  }

  public Optional<SObject> getDonationByTransactionId(String transactionId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields) +  " from Opportunity where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewayTransactionId + " = '" + transactionId + "'";
    return querySingle(query);
  }

  // For processes like payout handling, we need to retrieve a lot of donations at once. Retrieve in batches to preserve API limits!
  public List<SObject> getDonationsByTransactionIds(List<String> transactionIds, String... extraFields) throws ConnectionException, InterruptedException {
    List<String> page;
    List<String> more;
    // SOQL has a 100k char limit for queries, so we're arbitrarily defining the page sizes...
    if (transactionIds.size() > MAX_ID_QUERY_LIST_SIZE) {
      page = transactionIds.subList(0, MAX_ID_QUERY_LIST_SIZE);
      more = transactionIds.subList(MAX_ID_QUERY_LIST_SIZE, transactionIds.size());
    } else {
      page = transactionIds;
      more = Collections.emptyList();
    }

    String transactionIdsJoin = page.stream().map(transactionId -> "'" + transactionId + "'").collect(Collectors.joining(","));
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields) +  " from Opportunity where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewayTransactionId + " in (" + transactionIdsJoin + ")";
    List<SObject> results = queryList(query);

    if (!more.isEmpty()) {
      results.addAll(getDonationsByTransactionIds(more));
    }

    return results;
  }

  public List<SObject> getDonationsByAccountId(String accountId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields) +  " from Opportunity where accountid = '" + accountId + "' AND StageName != 'Pledged' ORDER BY CloseDate DESC";
    return queryListAutoPaged(query);
  }

  public List<SObject> searchDonations(String accountId, String contactId, Calendar date, double amount, String... extraFields) throws ConnectionException, InterruptedException {
    String accountClause = Strings.isNullOrEmpty(accountId) ? "" : "accountid='" + accountId + "' AND ";
    String contactClause = Strings.isNullOrEmpty(contactId) ? "" : "contactid='" + contactId + "' AND ";

    String dateString = new SimpleDateFormat("yyyy-MM-dd").format(date.getTime());

    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields) +  " from Opportunity where " + accountClause + contactClause + "closedate=" + dateString + " and amount=" + amount;
    return queryListAutoPaged(query);
  }

  public List<SObject> getFailingDonationsLastMonthByAccountId(String accountId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields) +  " from Opportunity where stageName = 'Failed Attempt' AND CloseDate = LAST_MONTH AND AccountId = '" + accountId + "'";
    return queryList(query);
  }

  public Optional<SObject> getLatestPostedDonation(String recurringDonationId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields) +  " from Opportunity where npe03__Recurring_Donation__c = '" + recurringDonationId + "' and stageName = 'Posted' order by CloseDate desc limit 1";
    return querySingle(query);
  }

  public List<SObject> getDonationsInDeposit(String depositId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields) +  " from Opportunity where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositId + " = '" + depositId + "'";
    return queryListAutoPaged(query);
  }

  public List<SObject> getRefundsInDeposit(String depositId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields) +  " from Opportunity where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDepositId + " = '" + depositId + "'";
    return queryListAutoPaged(query);
  }

  public Optional<SObject> getNextPledgedDonationByRecurringDonationId(String recurringDonationId, String... extraFields) throws ConnectionException, InterruptedException {
    // TODO: Using TOMORROW to account for timezone issues -- we can typically get away with that approach
    // since most RDs are monthly...
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields) +  " from Opportunity where npe03__Recurring_Donation__c = '" + recurringDonationId + "' AND stageName = 'Pledged' AND CloseDate <= TOMORROW ORDER BY CloseDate Desc LIMIT 1";
    return querySingle(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // REPORTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  public List<SObject> getReports() throws InterruptedException, ConnectionException {
    String query = "select " + REPORT_FIELDS + " FROM Report";
    return queryListAutoPaged(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // META
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Optional<SObject> getRecordTypeByName(String recordTypeName) throws ConnectionException, InterruptedException {
    String query = "select id from recordtype where name = '" + recordTypeName + "'";
    return querySingle(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // RECURRING DONATIONS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Optional<SObject> getRecurringDonationById(String id, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation, extraFields) +  " from npe03__Recurring_Donation__c where id='" + id + "'";
    return querySingle(query);
  }
  public List<SObject> getRecurringDonationsByIds(List<String> ids, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(ids, "Id", "npe03__Recurring_Donation__c", RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation, extraFields);
  }

  public Optional<SObject> getRecurringDonationByName(String name, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation, extraFields) +  " from npe03__Recurring_Donation__c where name='" + name.replaceAll("'", "\\\\'") + "'";
    return querySingle(query);
  }

  public Optional<SObject> getRecurringDonationBySubscriptionId(String subscriptionId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation, extraFields) +  " from npe03__Recurring_Donation__c where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewaySubscriptionId + " = '" + subscriptionId + "'";
    return querySingle(query);
  }

  public List<SObject> getRecurringDonationsByAccountId(String accountId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation, extraFields) +  " from npe03__Recurring_Donation__c where Npe03__Organization__c = '" + accountId + "'";
    return queryList(query);
  }

  public List<SObject> searchRecurringDonations(List<String> clauses, String... extraFields) throws InterruptedException, ConnectionException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation, extraFields) +  " from npe03__Recurring_Donation__c where ((" + String.join(") AND (", clauses) + "))";
    return queryList(query);
  }
  public void refreshRecurringDonation(String donationId) throws ConnectionException {
    // TODO: set up 'FORCE_URL' env var and the appropriate apex enpoint for orgs so they this will work
    log.info("refreshing opportunities on {}...", donationId);

    String data = "{\"recurringDonationId\": \"" + donationId + "\"}";
    String sessionId = login().getSessionId();

    String sfdcEndpoint = "https://" + env.getConfig().salesforce.forceUrl + "/services/apexrest/refreshrecurringdonation";

    post(sfdcEndpoint, data, APPLICATION_JSON, HttpClient.HeaderBuilder.builder().authBearerToken(sessionId));
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // USERS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Optional<SObject> getUserById(String userId, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(USER_FIELDS, env.getConfig().salesforce.customQueryFields.user, extraFields) +  " from user where id = '" + userId + "'";
    return querySingle(query);
  }

  public Optional<SObject> getUserByEmail(String email, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(USER_FIELDS, env.getConfig().salesforce.customQueryFields.user, extraFields) +  " from user where isActive = true and email = '" + email + "'";
    return querySingle(query);
  }

  /**
   * Use with caution, it retrieves ALL active users. Unsuitable for orgs with many users.
   */
  public List<SObject> getActiveUsers() throws ConnectionException, InterruptedException {
    String query = "select id, firstName, lastName from user where isActive = true";
    return queryList(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // INTERNAL
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected String getFieldsList(String fields, Collection<String> customFields, String[] extraFields) {
    // deal with duplicates
    Set<String> fieldsDeduped = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    fieldsDeduped.addAll(Arrays.stream(fields.split("[,\\s]+")).toList());
    fieldsDeduped.addAll(customFields);
    fieldsDeduped.addAll(Arrays.stream(extraFields).toList());

    return Joiner.on(", ").join(fieldsDeduped);
  }

  protected List<SObject> getBulkResults(List<String> conditions, String conditionFieldName, String objectType,
      String fields, Set<String> _customFields, String[] extraFields) throws ConnectionException, InterruptedException {
    if (conditions.isEmpty()) {
      return List.of();
    }

    List<String> page;
    List<String> more;
    // SOQL has a 100k char limit for queries, so we're arbitrarily defining the page sizes...
    if (conditions.size() > MAX_ID_QUERY_LIST_SIZE) {
      page = conditions.subList(0, MAX_ID_QUERY_LIST_SIZE);
      more = conditions.subList(MAX_ID_QUERY_LIST_SIZE, conditions.size());
    } else {
      page = conditions;
      more = Collections.emptyList();
    }

    // the provided Set might be immutable
    Set<String> customFields = new HashSet<>(_customFields);
    // sometimes searching using external ref IDs or another unique fields -- make sure results include it
    if (!"id".equalsIgnoreCase(conditionFieldName) && !"email".equalsIgnoreCase(conditionFieldName)) {
      customFields.add(conditionFieldName);
    }

    String conditionsJoin = page.stream().map(condition -> "'" + condition.replaceAll("'", "\\\\'") + "'").collect(Collectors.joining(","));
    String query = "select " + getFieldsList(fields, customFields, extraFields) + " from " + objectType + " where " + conditionFieldName + " in (" + conditionsJoin + ")";
    List<SObject> results = queryListAutoPaged(query);

    if (!more.isEmpty()) {
      results.addAll(getBulkResults(more, conditionFieldName, objectType, fields, customFields, extraFields));
    }

    return results;
  }
}
