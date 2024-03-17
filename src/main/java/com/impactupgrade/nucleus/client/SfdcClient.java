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
import com.impactupgrade.nucleus.model.AccountSearch;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.util.HttpClient;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
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

  public static final String AUTH_URL_PRODUCTION = "https://login.salesforce.com/services/Soap/u/55.0/";
  public static final String AUTH_URL_SANDBOX = "https://test.salesforce.com/services/Soap/u/55.0/";

  // SOQL has a 100k char limit for queries, so we're arbitrarily defining the page sizes...
  protected static final int MAX_ID_QUERY_LIST_SIZE = 500;

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
  protected String TASK_FIELDS;

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
        isSandbox ? AUTH_URL_SANDBOX : AUTH_URL_PRODUCTION
    );
    this.env = env;

    boolean npsp = env.getConfig().salesforce.npsp;

    ACCOUNT_FIELDS = "id, OwnerId, Owner.Id, Owner.IsActive, name, phone, BillingStreet, BillingCity, BillingPostalCode, BillingState, BillingCountry, ShippingStreet, ShippingCity, ShippingPostalCode, ShippingState, ShippingCountry";
    CAMPAIGN_FIELDS = "id, name, parentid, ownerid, owner.id, owner.isactive";
    CONTACT_FIELDS = "Id, AccountId, OwnerId, Owner.Id, Owner.Name, Owner.IsActive, FirstName, LastName, Title, Account.Id, Account.Name, Account.BillingStreet, Account.BillingCity, Account.BillingPostalCode, Account.BillingState, Account.BillingCountry, Account.ShippingStreet, Account.ShippingCity, Account.ShippingPostalCode, Account.ShippingState, Account.ShippingCountry, name, email, mailingstreet, mailingcity, mailingstate, mailingpostalcode, mailingcountry, CreatedDate, HomePhone, MobilePhone, Phone";
    LEAD_FIELDS = "Id, FirstName, LastName, Email, OwnerId, Owner.Id, Owner.Name, Owner.IsActive";
    DONATION_FIELDS = "id, AccountId, Account.Id, Account.Name, ContactId, Amount, Name, RecordTypeId, RecordType.Id, RecordType.Name, CampaignId, Campaign.ParentId, CloseDate, StageName, Type, Description, OwnerId, Owner.Id, Owner.IsActive";
    USER_FIELDS = "id, name, firstName, lastName, email, phone";
    REPORT_FIELDS = "Id, Name";
    TASK_FIELDS = "Id, WhoId, OwnerId, Subject, description, status, priority, activityDate";

    if (npsp) {
      // TODO: Record Types are not NPSP specific, but we have yet to see them in a commercial context. IE, NPSP always
      //  has them due to the Household vs. Organization default, but commercial entities have a flatter setup.
      //  IF we need these commercially, needs to become a configurable option in env.json. Unfortunately, SFDC
      //  returns a query error if you try to query them but no record types exist for that object.
      ACCOUNT_FIELDS += ", RecordTypeId, RecordType.Id, RecordType.Name, npo02__NumberOfClosedOpps__c, npo02__TotalOppAmount__c, npo02__LastCloseDate__c, npo02__LargestAmount__c, npo02__OppsClosedThisYear__c, npo02__OppAmountThisYear__c, npo02__FirstCloseDate__c";
      // TODO: Same point about NPSP.
      CONTACT_FIELDS += ", Account.RecordTypeId, Account.RecordType.Id, Account.RecordType.Name, account.npo02__NumberOfClosedOpps__c, account.npo02__TotalOppAmount__c, account.npo02__FirstCloseDate__c, account.npo02__LastCloseDate__c, account.npo02__LargestAmount__c, account.npo02__OppsClosedThisYear__c, account.npo02__OppAmountThisYear__c, npe01__Home_Address__c, npe01__WorkPhone__c, npe01__PreferredPhone__c, npe01__HomeEmail__c, npe01__WorkEmail__c, npe01__AlternateEmail__c, npe01__Preferred_Email__c";
      DONATION_FIELDS += ", npe03__Recurring_Donation__c, Account.RecordTypeId, Account.RecordType.Id, Account.RecordType.Name";
      RECURRINGDONATION_FIELDS = "id, name, npe03__Recurring_Donation_Campaign__c, npe03__Recurring_Donation_Campaign__r.Name, npe03__Next_Payment_Date__c, npe03__Installment_Period__c, npe03__Amount__c, npe03__Open_Ended_Status__c, npe03__Contact__c, npe03__Contact__r.Id, npe03__Contact__r.Name, npe03__Contact__r.Email, npe03__Contact__r.Phone, npe03__Schedule_Type__c, npe03__Date_Established__c, npe03__Organization__c, npe03__Organization__r.Id, npe03__Organization__r.Name, OwnerId, Owner.Id, Owner.IsActive";
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

  public List<SObject> getAccountsByName(String name, String... extraFields) throws ConnectionException, InterruptedException {
    String escapedName = name.replaceAll("'", "\\\\'");
    // Note the formal greeting -- super important, as that's often used in numerous imports/exports
    String query = "select " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account, extraFields) + " from account where name like '%" + escapedName + "%' or npo02__Formal_Greeting__c='%" + escapedName + "%'";
    return queryList(query);
  }
  public List<SObject> getAccountsByNames(List<String> names, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(names, "Name", "Account", ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account, extraFields);
  }

  public List<SObject> getAccountsByEmails(List<String> emails, String... extraFields) throws ConnectionException, InterruptedException {
    return this.getBulkResults(emails, List.of(env.getConfig().salesforce.fieldDefinitions.accountEmail), "Account", this.ACCOUNT_FIELDS, this.env.getConfig().salesforce.customQueryFields.account, extraFields);
  }

  public Optional<SObject> getAccountByUniqueField(String fieldName, String value, String... extraFields)
      throws ConnectionException, InterruptedException {
    String query = "SELECT " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account, extraFields) +  " FROM Account WHERE " + fieldName + "='" + value + "'";
    return querySingle(query);
  }

  public List<SObject> getAccountsByUniqueField(String fieldName, List<String> values, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(values, fieldName, "Account", ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account, extraFields);
  }

  public PagedResults<SObject> searchAccounts(AccountSearch accountSearch, String... extraFields)
      throws ConnectionException, InterruptedException {
    List<String> clauses = new ArrayList<>();

    if (!Strings.isNullOrEmpty(accountSearch.ownerId)) {
      clauses.add("OwnerId = '" + accountSearch.ownerId + "'");
    }

    if (!Strings.isNullOrEmpty(accountSearch.keywords)) {
      String[] keywordSplit = accountSearch.keywords.trim().split("\\s+");
      for (String keyword : keywordSplit) {
        clauses.add("(Name LIKE '%" + keyword + "%' OR BillingAddress LIKE '%" + keyword + "%' OR ShippingAddress LIKE '%" + keyword + "%')");
      }
    }

    String fullClause = String.join( " AND ", clauses);
    if (!Strings.isNullOrEmpty(fullClause)) {
      fullClause = "where " + fullClause;
    }

    String select;
    if (accountSearch.basicSearch) {
      select = "Id, Name";
    } else {
      select = getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account, extraFields);
    }

    String query ="select " + select +  " from account " + fullClause + " ORDER BY Name";

    if (accountSearch.pageSize != null && accountSearch.pageSize > 0) {
      query += " LIMIT " + accountSearch.pageSize;
    }
    Integer offset = accountSearch.getPageOffset();
    if (offset != null && offset > 0) {
      query += " OFFSET " + offset;
    }

    List<SObject> results = queryList(query);
    return PagedResults.getPagedResultsFromCurrentOffset(results, accountSearch);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CAMPAIGNS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public List<SObject> getCampaigns(String... extraFields) throws ConnectionException, InterruptedException {
    String query = "SELECT " + getFieldsList(CAMPAIGN_FIELDS, env.getConfig().salesforce.customQueryFields.campaign, extraFields) + " FROM Campaign ORDER BY Name ASC";
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
    return getBulkResults(names, "Name", "Campaign", CAMPAIGN_FIELDS, env.getConfig().salesforce.customQueryFields.campaign, extraFields);
  }
  public Optional<SObject> getCampaignByExternalReference(String externalReference, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CAMPAIGN_FIELDS, env.getConfig().salesforce.customQueryFields.campaign, extraFields) +  " from campaign where " + env.getConfig().salesforce.fieldDefinitions.campaignExternalReference +  " = '" + externalReference.replaceAll("'", "\\\\'") + "'";
    return querySingle(query);
  }

  // See note on CrmService.getEmailCampaignsByContactIds. Retrieve in batches to preserve API limits!
  public List<SObject> getEmailCampaignsByContactIds(List<String> contactIds, String filter) throws ConnectionException, InterruptedException {
    // TODO: Note the use of CampaignMember -- currently need the name only, but could refactor to use CAMPAIGN_FIELDS on the child object.

    List<String> page;
    List<String> more;
    int size = contactIds.size();
    if (size > MAX_ID_QUERY_LIST_SIZE) {
      page = contactIds.subList(0, MAX_ID_QUERY_LIST_SIZE);
      more = contactIds.subList(MAX_ID_QUERY_LIST_SIZE, size);
    } else {
      page = contactIds;
      more = Collections.emptyList();
    }

    String contactIdsJoin = page.stream().map(contactId -> "'" + contactId + "'").collect(Collectors.joining(","));
    String query = "SELECT ContactId, Campaign.Name FROM CampaignMember WHERE ContactId IN (" + contactIdsJoin + ")";
    if (!Strings.isNullOrEmpty(filter)) {
      query += " AND " + filter;
    } else {
      query += " AND Campaign.IsActive=TRUE";
    }
    List<SObject> results = queryListAutoPaged(query);

    if (!more.isEmpty()) {
      results.addAll(getEmailCampaignsByContactIds(more, filter));
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
  public List<SObject> getContactsByIds(List<String> ids, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(ids, "Id", "Contact", CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields);
  }

  public Optional<SObject> getContactByUniqueField(String fieldName, String value, String... extraFields)
      throws ConnectionException, InterruptedException {
    String query = "SELECT " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " FROM Contact WHERE " + fieldName + "='" + value + "'";
    return querySingle(query);
  }

  public List<SObject> getContactsByUniqueField(String fieldName, List<String> values, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(values, fieldName, "Contact", CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields);
  }

  public List<SObject> getContactsByNames(List<String> names, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(names, "Name", "Contact", CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields);
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
//      env.logJobError("Failed to get report description! {}", reportId);
//      return Collections.emptyList();
//    }
//
//    // Check report type is supported
//    String reportType = getReportType(reportDescription);
//    Set<String> supportedTypes = env.getConfig().salesforce.supportedContactsReportTypes;
//    if (!supportedTypes.contains(reportType)) {
//      env.logJobWarn("Report type {} is not supported! Supported types: {}.", reportType, supportedTypes);
//      env.logJobWarn("Can NOT load contacts from report {}!", reportId);
//      return Collections.emptyList();
//    }
//
//    // Get report columns as a map (csv column label -> report column key)
//    Map<String, String> reportColumns = getReportColumns(reportDescription);
//    if (CollectionUtils.isEmpty(reportColumns.keySet())) {
//      // Should be unreachable
//      env.logJobError("Report {} does not have any columns defined!", reportId);
//      return Collections.emptyList();
//    }
//    Set<String> supportedColumns = env.getConfig().salesforce.supportedContactReportColumns;
//    // Check if report contains supported columns
//    Set<String> filteredColumnsLabels = reportColumns.keySet().stream()
//            .filter(reportColumnLabel -> supportedColumns.contains(reportColumns.get(reportColumnLabel)))
//            .collect(Collectors.toSet());
//
//    if (CollectionUtils.isEmpty(filteredColumnsLabels)) {
//      env.logJobWarn("Report {} does not contain any of supported columns: {}", reportId, supportedColumns);
//      env.logJobWarn("Can NOT load contacts from report {}!", reportId);
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
//    env.logJobInfo("column values: {}", columnValues);
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

  public List<SObject> getContactsByReportId(String reportId) throws ConnectionException, InterruptedException, IOException {
    if (Strings.isNullOrEmpty(reportId)) {
      return Collections.emptyList();
    }

    // Get the report as csv
    String reportContent = downloadReportAsString(reportId);

    // NOTE: Rather than return a Map of the results, we instead require the report to include an ID column, then
    // use that to run a normal query to grab all fields we care about.

    // Get report content as a map of (columnLabel -> columnValues)
    List<SObject> results = new ArrayList<>();
    CsvMapper mapper = new CsvMapper();
    CsvSchema schema = CsvSchema.emptySchema().withHeader();
    MappingIterator<Map<String, String>> iterator = mapper.readerFor(Map.class).with(schema).readValues(reportContent);
    while (iterator.hasNext()) {
      SObject sobject = new SObject("Contact");
      final boolean[] hasValues = {false};

      iterator.next().forEach((key, value) -> {
        if (Strings.isNullOrEmpty(value)) {
          return;
        }

        // Why endsWith? The field names can take multiple variations depending on the report type used. Ex: if it's
        // a simple Contact report, it might only have Email and Phone. If it's a with/and report, where Contact
        // is mixed in with other objects, you could see it prefixed with something like Contact: Email and Contact: Phone.
        if ((key.equalsIgnoreCase("Id") || key.equalsIgnoreCase("Contact Id") || key.equalsIgnoreCase("Contact: Id")) && value.startsWith("003")) {
          hasValues[0] = true;
          sobject.setId(value);
        } else if (key.endsWith("Email")) {
          hasValues[0] = true;
          sobject.setField("Email", value);
        } else if (key.endsWith("MobilePhone") || key.endsWith("Mobile")) {
          hasValues[0] = true;
          sobject.setField("MobilePhone", value);
        } else if (key.endsWith("Phone")) {
          hasValues[0] = true;
          sobject.setField("Phone", value);
        }
      });

      // Important to check this, as some CSV exports include footer details that get picked up as one column rows.
      if (hasValues[0]) {
        results.add(sobject);
      }
    }

    return results;
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
//    env.logJobInfo("report type: {}", reportType);
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
    env.logJobInfo("downloading report file from SFDC...");

    // using jruby to kick off the ruby script -- see https://github.com/carojkov/salesforce-export-downloader
    ScriptingContainer container = new ScriptingContainer(LocalContextScope.THREADSAFE);
    container.getEnvironment().put("SFDC_USERNAME", env.getConfig().salesforce.username);
    container.getEnvironment().put("SFDC_PASSWORD", env.getConfig().salesforce.password);
    container.getEnvironment().put("SFDC_URL", env.getConfig().salesforce.url);
    container.getEnvironment().put("SFDC_REPORT_ID", reportId);
    container.runScriptlet(PathType.CLASSPATH, "salesforce-downloader/salesforce-report.rb");
    container.terminate();

    env.logJobInfo("report downloaded!");

    File reportFile = new File("report-salesforce/" + reportId + ".csv");
    return Files.readString(reportFile.toPath(), StandardCharsets.UTF_8);
  }

  public List<SObject> getContactsByOpportunityName(String opportunityName, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where id in (select contactid from Opportunity where name='" + opportunityName.replaceAll("'", "\\\\'") + "' and contactid != null)";
    return queryListAutoPaged(query);
  }

  public Collection<SObject> getEmailContacts(Calendar updatedSince, String filter, String... extraFields) throws ConnectionException, InterruptedException {
    String updatedSinceClause = "";

    String ts = updatedSince == null ? "" : new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(updatedSince.getTime());

    if (updatedSince != null) {
      updatedSinceClause = " and SystemModStamp >= " + ts;
    }

    List<SObject> contacts = queryEmailContacts(updatedSinceClause, filter, extraFields);

    if (updatedSince != null) {
      updatedSinceClause = " and Id IN (SELECT ContactId FROM CampaignMember WHERE SystemModStamp >= " + ts + " OR Campaign.SystemModStamp >= " + ts + ")";
      contacts.addAll(queryEmailContacts(updatedSinceClause, filter, extraFields));
    }

    return contacts;
  }

  protected List<SObject> queryEmailContacts(String updatedSinceClause, String filter, String... extraFields) throws ConnectionException, InterruptedException {
    if (!Strings.isNullOrEmpty(filter)) {
      filter = " AND " + filter;
    }

    // If env.json defines an emailOptIn, automatically factor that into the query.
    // IMPORTANT: If env.json defines emailOptOut/emailBounced, also include those contacts in this query! This might seem backwards,
    // but we need them in the results so that we can archive them in Mailchimp.
    List<String> clauses = new ArrayList<>();
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.emailOptIn)) {
      clauses.add(env.getConfig().salesforce.fieldDefinitions.emailOptIn + "=TRUE");

      // ONLY ADD THESE IF WE'RE INCLUDING THE ABOVE OPT-IN FILTER! Otherwise, some orgs only have opt-out defined,
      // and we'd effectively be syncing ONLY unsubscribes.
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.emailOptOut)) {
        clauses.add(env.getConfig().salesforce.fieldDefinitions.emailOptOut + "=TRUE");
      }
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.emailBounced)) {
        clauses.add(env.getConfig().salesforce.fieldDefinitions.emailBounced + "=TRUE");
      }
    }
    String optInOutFilters = clauses.isEmpty() ? "" : " AND (" + String.join(" OR ", clauses) + ")";

    // IMPORTANT: Order by CreatedDate ASC, ensuring this is FIFO for contacts sharing the same email address.
    // The oldest record is typically the truth.
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where Email!=''" + updatedSinceClause + filter + optInOutFilters + " ORDER BY CreatedDate ASC";
    return queryListAutoPaged(query);
  }

  public Collection<SObject> getEmailAccounts(Calendar updatedSince, String filter, String... extraFields)
      throws ConnectionException, InterruptedException {
    String emailField = env.getConfig().salesforce.fieldDefinitions.accountEmail;
    if (Strings.isNullOrEmpty(emailField)) {
      return Collections.emptyList();
    }

    String updatedSinceClause = "";
    if (updatedSince != null) {
      updatedSinceClause = " and SystemModStamp >= " + (new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")).format(updatedSince.getTime());
    }

    if (!Strings.isNullOrEmpty(filter)) {
      filter = " AND " + filter;
    }

    // If env.json defines an emailOptIn, automatically factor that into the query.
    // IMPORTANT: If env.json defines emailOptOut/emailBounced, also include those contacts in this query! This might seem backwards,
    // but we need them in the results so that we can archive them in Mailchimp.
    List<String> clauses = new ArrayList<>();
    if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.accountEmailOptIn)) {
      clauses.add(env.getConfig().salesforce.fieldDefinitions.accountEmailOptIn + "=TRUE");

      // ONLY ADD THESE IF WE'RE INCLUDING THE ABOVE OPT-IN FILTER! Otherwise, some orgs only have opt-out defined,
      // and we'd effectively be syncing ONLY unsubscribes.
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.accountEmailOptOut)) {
        clauses.add(env.getConfig().salesforce.fieldDefinitions.accountEmailOptOut + "=TRUE");
      }
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.accountEmailBounced)) {
        clauses.add(env.getConfig().salesforce.fieldDefinitions.accountEmailBounced + "=TRUE");
      }
    }
    String optInOutFilters = clauses.isEmpty() ? "" : " AND (" + String.join(" OR ", clauses) + ")";

    String query = "select " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account, extraFields) + " from account where " + emailField + "!=''" + updatedSinceClause + filter + optInOutFilters + " ORDER BY CreatedDate ASC";
    return queryListAutoPaged(query);
  }

  public Collection<SObject> getSmsContacts(Calendar updatedSince, String filter, String... extraFields) throws ConnectionException, InterruptedException {
    String updatedSinceClause = "";

    if (updatedSince != null) {
      updatedSinceClause = " and SystemModStamp >= " + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(updatedSince.getTime());
    }
    List<SObject> contacts = querySmsContacts(updatedSinceClause, filter, extraFields);

    if (updatedSince != null) {
      updatedSinceClause = " and Id IN (SELECT ContactId FROM CampaignMember WHERE SystemModStamp >= " + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(updatedSince.getTime()) + ")";
    }
    contacts.addAll(querySmsContacts(updatedSinceClause, filter, extraFields));

    // SOQL has no DISTINCT clause, and GROUP BY has tons of caveats, so we're filtering out duplicates in-mem.
    Map<String, SObject> uniqueContacts = contacts.stream().collect(Collectors.toMap(
        so -> {
          String phone = (String) so.getField("MobilePhone");
          if (Strings.isNullOrEmpty(phone)) {
            phone = (String) so.getField("HomePhone");
          }
          if (Strings.isNullOrEmpty(phone)) {
            phone = (String) so.getField("Phone");
          }
          return phone.replaceAll("[\\D]", "");
        },
        Function.identity(),
        // FIFO
        (so1, so2) -> so1
    ));
    return uniqueContacts.values();
  }

  protected List<SObject> querySmsContacts(String updatedSinceClause, String filter, String... extraFields) throws ConnectionException, InterruptedException {
    if (!Strings.isNullOrEmpty(filter)) {
      filter = " AND " + filter;
    }

    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields) +  " from contact where (Phone != '' OR MobilePhone != '')" + updatedSinceClause + filter;
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

    if (!Strings.isNullOrEmpty(contactSearch.firstName)) {
      clauses.add("FirstName = '" + contactSearch.firstName + "'");
    }
    if (!Strings.isNullOrEmpty(contactSearch.lastName)) {
      clauses.add("LastName = '" + contactSearch.lastName + "'");
    }

    if (!Strings.isNullOrEmpty(contactSearch.accountId)) {
      clauses.add("AccountId = '" + contactSearch.accountId + "'");
    }

    if (!Strings.isNullOrEmpty(contactSearch.ownerId)) {
      clauses.add("OwnerId = '" + contactSearch.ownerId + "'");
    }

    if (!contactSearch.keywords.isEmpty()) {
      for (String keyword : contactSearch.keywords) {
        keyword = keyword.replaceAll("'", "\\\\'");
        keyword = keyword.trim();
        // TODO: Finding a few clients with no homephone, so taking that out for now.
        clauses.add("(FirstName LIKE '%" + keyword + "%' OR LastName LIKE '%" + keyword + "%' OR Email LIKE '%" + keyword + "%' OR Phone LIKE '%" + keyword + "%' OR MobilePhone LIKE '%" + keyword + "%' OR MailingStreet LIKE '%" + keyword + "%' OR MailingCity LIKE '%" + keyword + "%' OR MailingState LIKE '%" + keyword + "%' OR MailingPostalCode LIKE '%" + keyword + "%' OR Account.ShippingStreet LIKE '%" + keyword + "%' OR Account.ShippingCity LIKE '%" + keyword + "%' OR Account.ShippingState LIKE '%" + keyword + "%' OR Account.ShippingPostalCode LIKE '%" + keyword + "%' OR Account.BillingStreet LIKE '%" + keyword + "%' OR Account.BillingCity LIKE '%" + keyword + "%' OR Account.BillingState LIKE '%" + keyword + "%' OR Account.BillingPostalCode LIKE '%" + keyword + "%')");
      }
    }

    String fullClause = String.join( " AND ", clauses);
    if (!Strings.isNullOrEmpty(fullClause)) {
      fullClause = "where " + fullClause;
    }

    String select;
    if (contactSearch.basicSearch) {
      select = "Id, FirstName, LastName";
    } else {
      select = getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields);
    }

    String query ="select " + select +  " from contact " + fullClause + " ORDER BY LastName, FirstName";

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
    return getBulkResults(emails, List.of("Email", "npe01__HomeEmail__c", "npe01__WorkEmail__c", "npe01__AlternateEmail__c"), "Contact", CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact, extraFields);
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
      filter = " AND " + filter;
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

  public Optional<SObject> getDonationById(String id, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields) +  " from Opportunity where id='" + id + "'";
    return querySingle(query);
  }

  public List<SObject> getDonationsByIds(List<String> ids, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(ids, "Id", "Opportunity", DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields);
  }

  public Optional<SObject> getDonationByUniqueField(String fieldName, String value, String... extraFields)
      throws ConnectionException, InterruptedException {
    String query = "SELECT " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields) +  " FROM Opportunity WHERE " + fieldName + "='" + value + "'";
    return querySingle(query);
  }

  public List<SObject> getDonationsByUniqueField(String fieldName, List<String> values, String... extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(values, fieldName, "Opportunity", DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation, extraFields);
  }

  // For processes like payout handling, we need to retrieve a lot of donations at once. Retrieve in batches to preserve API limits!
  public List<SObject> getDonationsByTransactionIds(List<String> transactionIds, String... extraFields) throws ConnectionException, InterruptedException {
    List<String> page;
    List<String> more;
    int size = transactionIds.size();
    // SOQL has a 100k char limit for queries, so we're arbitrarily defining the page sizes...
    if (size > MAX_ID_QUERY_LIST_SIZE) {
      page = transactionIds.subList(0, MAX_ID_QUERY_LIST_SIZE);
      more = transactionIds.subList(MAX_ID_QUERY_LIST_SIZE, size);
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
    env.logJobInfo("refreshing opportunities on {}...", donationId);

    String data = "{\"recurringDonationId\": \"" + donationId + "\"}";
    String sessionId = login().getSessionId();

    String sfdcEndpoint = "https://" + env.getConfig().salesforce.forceUrl + "/services/apexrest/refreshrecurringdonation";

    post(sfdcEndpoint, data, APPLICATION_JSON, HttpClient.HeaderBuilder.builder().authBearerToken(sessionId));
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // USERS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public Optional<SObject> getUserByEmail(String email, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(USER_FIELDS, env.getConfig().salesforce.customQueryFields.user, extraFields) +  " from user where isActive = true and email = '" + email + "'";
    return querySingle(query);
  }

  public Optional<SObject> getActivityByExternalReference(String externalReference, String... extraFields) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(TASK_FIELDS, env.getConfig().salesforce.customQueryFields.task, extraFields) + " from task where " + env.getConfig().salesforce.fieldDefinitions.activityExternalReference + " = '" + externalReference + "'";
    return querySingle(query);
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

  protected List<SObject> getBulkResults(List<String> values, String conditionFieldName, String objectType,
      String fields, Set<String> _customFields, String[] extraFields) throws ConnectionException, InterruptedException {
    return getBulkResults(values, List.of(conditionFieldName), objectType, fields, _customFields, extraFields);
  }

  protected List<SObject> getBulkResults(List<String> values, List<String> conditionFieldNames, String objectType,
      String fields, Set<String> _customFields, String[] extraFields) throws ConnectionException, InterruptedException {

    values = values.stream().filter(v -> !Strings.isNullOrEmpty(v)).toList();
    conditionFieldNames = conditionFieldNames.stream().filter(f -> !Strings.isNullOrEmpty(f)).toList();

    if (values.isEmpty() || conditionFieldNames.isEmpty()) {
      return List.of();
    }

    List<String> page;
    List<String> more;
    int size = values.size();
    // SOQL has a 100k char limit for queries, so we're arbitrarily defining the page sizes...
    if (size > MAX_ID_QUERY_LIST_SIZE) {
      page = values.subList(0, MAX_ID_QUERY_LIST_SIZE);
      more = values.subList(MAX_ID_QUERY_LIST_SIZE, size);
    } else {
      page = values;
      more = Collections.emptyList();
    }

    // the provided Set might be immutable
    Set<String> customFields = new HashSet<>(_customFields);
    // sometimes searching using external ref IDs or another unique fields -- make sure results include it
    for (String conditionFieldName : conditionFieldNames) {
      if (!"id".equalsIgnoreCase(conditionFieldName) && !"email".equalsIgnoreCase(conditionFieldName)) {
        customFields.add(conditionFieldName);
      }
    }

    String valuesJoin = page.stream().map(condition -> "'" + condition.replaceAll("'", "\\\\'") + "'").collect(Collectors.joining(","));
    List<String> conditions = new ArrayList<>();
    for (String conditionFieldName : conditionFieldNames) {
      conditions.add(conditionFieldName + " in (" + valuesJoin + ")");
    }
    String conditionsJoin = String.join(" OR ", conditions);
    String query = "select " + getFieldsList(fields, customFields, extraFields) + " from " + objectType + " where " + conditionsJoin;
    List<SObject> results = queryListAutoPaged(query);

    if (!more.isEmpty()) {
      results.addAll(getBulkResults(more, conditionFieldNames, objectType, fields, customFields, extraFields));
    }

    return results;
  }
}
