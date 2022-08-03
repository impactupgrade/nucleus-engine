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
import org.apache.commons.collections.CollectionUtils;
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
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.util.HttpClient.post;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

public class SfdcClient extends SFDCPartnerAPIClient {

  private static final Logger log = LogManager.getLogger(SfdcClient.class.getName());

  public static final String AUTH_URL_PRODUCTION = "https://login.salesforce.com/services/Soap/u/55.0/";
  public static final String AUTH_URL_SANDBOX = "https://test.salesforce.com/services/Soap/u/55.0/";

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

  protected static final String ACCOUNT_FIELDS = "id, OwnerId, name, phone, BillingStreet, BillingCity, BillingPostalCode, BillingState, BillingCountry, npo02__NumberOfClosedOpps__c, npo02__TotalOppAmount__c, npo02__LastCloseDate__c, RecordTypeId, RecordType.Id, RecordType.Name";

  public Optional<SObject> getAccountById(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account) + " from account where id = '" + accountId + "'";
    return querySingle(query);
  }

  public List<SObject> getAccountsByIds(Set<String> accountIds) throws ConnectionException, InterruptedException {
    if (CollectionUtils.isEmpty(accountIds)) {
      return Collections.emptyList();
    }
    String ids = accountIds.stream()
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(","));
    String query = "select " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account) + " from account where id in (" + ids + ") ORDER BY name";
    return queryList(query);
  }

  public List<SObject> getAccountsByName(String name) throws ConnectionException, InterruptedException {
    String escapedName = name.replaceAll("'", "\\\\'");
    // Note the formal greeting -- super important, as that's often used in numerous imports/exports
    String query = "select " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account) + " from account where name like '%" + escapedName + "%' or npo02__Formal_Greeting__c='%" + escapedName + "%'";
    return queryList(query);
  }

  public Optional<SObject> getAccountByCustomerId(String customerId) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(customerId) || Strings.isNullOrEmpty(env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId)) {
      return Optional.empty();
    }
    String query = "select " + getFieldsList(ACCOUNT_FIELDS, env.getConfig().salesforce.customQueryFields.account) + " from account where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewayCustomerId + " = '" + customerId + "'";
    return querySingle(query);
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CAMPAIGNS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String CAMPAIGN_FIELDS = "id, name, parentid, ownerid, RecordTypeId";

  public Optional<SObject> getCampaignById(String campaignId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CAMPAIGN_FIELDS, env.getConfig().salesforce.customQueryFields.campaign) + " from campaign where id = '" + campaignId + "'";
    return querySingle(query);
  }

  public Optional<SObject> getCampaignByName(String campaignName) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CAMPAIGN_FIELDS, env.getConfig().salesforce.customQueryFields.campaign) + " from campaign where name = '" + campaignName.replaceAll("'", "\\\\'") + "'";
    return querySingle(query);
  }

  // See note on CrmService.getActiveCampaignsByContactIds. Retrieve in batches to preserve API limits!
  public List<SObject> getActiveCampaignsByContactIds(List<String> contactIds) throws ConnectionException, InterruptedException {
    // TODO: Note the use of CampaignMember -- currently need the name only, but could refactor to use CAMPAIGN_FIELDS on the child object.

    List<String> page;
    List<String> more;
    // SOQL has a 100k char limit for queries, so we're arbitrarily defining the page sizes...
    if (contactIds.size() > 1000) {
      page = contactIds.subList(0, 1000);
      more = contactIds.subList(1000, contactIds.size());
    } else {
      page = contactIds;
      more = Collections.emptyList();
    }

    String contactIdsJoin = page.stream().map(contactId -> "'" + contactId + "'").collect(Collectors.joining(","));
    String query = "select ContactId, Campaign.Name from CampaignMember where ContactId in (" + contactIdsJoin + ") and Campaign.IsActive=true";
    List<SObject> results = queryList(query);

    if (!more.isEmpty()) {
      results.addAll(getActiveCampaignsByContactIds(more));
    }

    return results;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // CONTACTS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // TODO: Finding a few clients with no homephone, so taking that out for now.
  protected static final String CONTACT_FIELDS = "Id, AccountId, OwnerId, Owner.Id, Owner.Name, FirstName, LastName, account.id, account.name, account.BillingStreet, account.BillingCity, account.BillingPostalCode, account.BillingState, account.BillingCountry, account.npo02__NumberOfClosedOpps__c, account.npo02__TotalOppAmount__c, account.npo02__FirstCloseDate__c, account.npo02__LastCloseDate__c, name, phone, email, npe01__Home_Address__c, mailingstreet, mailingcity, mailingstate, mailingpostalcode, mailingcountry, mobilephone, npe01__workphone__c, npe01__preferredphone__c";

  public Optional<SObject> getContactById(String contactId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where id = '" + contactId + "' ORDER BY name";
    return querySingle(query);
  }

  public List<SObject> getContactsByIds(Collection<String> contactIds) throws ConnectionException, InterruptedException {
    String ids = contactIds.stream()
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(","));
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where id in (" + ids + ") ORDER BY name";
    return queryList(query);
  }

  public List<SObject> getContactsByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where accountId = '" + accountId + "' ORDER BY name";
    return queryList(query);
  }

  // the context map allows overrides to be given additional hints (such as DR's FNs)
  public List<SObject> getContactsByName(String name, Map<String, String> context) throws ConnectionException, InterruptedException {
    String escapedName = name.replaceAll("'", "\\\\'");
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where name like '%" + escapedName + "%' ORDER BY name";
    return queryList(query);
  }

  // the context map allows overrides to be given additional hints (such as DR's FNs)
  public List<SObject> getContactsByName(String firstName, String lastName, Map<String, String> context) throws ConnectionException, InterruptedException {
    String escapedFirstName = firstName.replaceAll("'", "\\\\'");
    String escapedLastName = lastName.replaceAll("'", "\\\\'");
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where firstName = '" + escapedFirstName + "' and lastName = '" + escapedLastName + "' ORDER BY name";
    return queryList(query);
  }

  public List<SObject> getDupContactsByName(String firstName, String lastName) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(firstName) && Strings.isNullOrEmpty(lastName)){
      return Collections.emptyList();
    }

    List<SObject> contacts = Collections.emptyList();

    if (!Strings.isNullOrEmpty(firstName) && !Strings.isNullOrEmpty(lastName)) {
      String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where firstname = '" + firstName.replaceAll("'", "\\\\'") + "' AND lastname = '" + lastName.replaceAll("'", "\\\\'") + "'";
      contacts = queryList(query);
    }
    if (contacts.isEmpty()) {
      String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where lastname = '" + lastName.replaceAll("'", "\\\\'") + "'";
      contacts = queryList(query);
    }

    return contacts;
  }

  public List<SObject> getContactsByAddress(String street, String city, String state, String zip, String country) throws ConnectionException, InterruptedException {
    if (Strings.isNullOrEmpty(street)){
      return Collections.emptyList();
    }

    // TODO: Test and make sure this format actually works for a variety of addresses, or if we need to try several
    String address = street + ", " + city + ", " + state + " " + zip + ", " + country;
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where npe01__Home_Address__c LIKE '" + street + "%'";
    return queryList(query);
  }

  public List<SObject> getContactsByCampaignId(String campaignId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where id in (select contactid from CampaignMember where id='" + campaignId + "' and contactid != null)";
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

  public List<SObject> getContactsByOpportunityName(String opportunityName) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where id in (select contactid from Opportunity where name='" + opportunityName.replaceAll("'", "\\\\'") + "' and contactid != null)";
    return queryListAutoPaged(query);
  }

  public Collection<SObject> getEmailContacts(Calendar updatedSince, String filter) throws ConnectionException, InterruptedException {
    String updatedSinceClause = "";
    if (updatedSince != null) {
      updatedSinceClause = " and SystemModStamp >= " + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(updatedSince.getTime());
    }

    if (!Strings.isNullOrEmpty(filter)) {
      filter = " and " + filter;
    }

    String query = "select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where Email != null" + updatedSinceClause + filter;
    List<SObject> contacts = queryListAutoPaged(query);

    // SOQL has no DISTINCT clause, and GROUP BY has tons of caveats, so we're filtering out duplicates in-mem.
    Map<String, SObject> uniqueContacts = contacts.stream().collect(Collectors.toMap(
        so -> so.getField("Email").toString(),
        Function.identity(),
        // FIFO
        (so1, so2) -> so1
    ));
    return uniqueContacts.values();
  }


  public PagedResults<SObject> searchContacts(ContactSearch contactSearch)
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
          .append(" OR MobilePhone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%'")
          .append(" OR OtherPhone LIKE '%").append(phoneArr[0]).append("%").append(phoneArr[1]).append("%").append(phoneArr[2]).append("%'");
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
    String query ="select " + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where " + fullClause + " ORDER BY LastName, FirstName";

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

  public Collection<SObject> getContactsDeletedSince(Calendar deletedSince, String filter) throws InterruptedException, ConnectionException {
    String deletedClause = "and isDeleted = TRUE";
    if (deletedSince!= null) {
      deletedClause += " and LastModifiedDate >= " + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(deletedSince.getTime());
    }

    if (!Strings.isNullOrEmpty(filter)) {
      filter = " and " + filter;
    }

    String query = "select isDeleted" + getFieldsList(CONTACT_FIELDS, env.getConfig().salesforce.customQueryFields.contact) + " from contact where Email != null" + deletedClause + filter;
    List<SObject> contacts = queryListAutoPaged(query);

    // SOQL has no DISTINCT clause, and GROUP BY has tons of caveats, so we're filtering out duplicates in-mem.
    //TODO added this from the get updated email contats method, might not be necessary
    Map<String, SObject> uniqueContacts = contacts.stream().collect(Collectors.toMap(
            so -> so.getField("Email").toString(),
            Function.identity(),
            // FIFO
            (so1, so2) -> so1
    ));
    return uniqueContacts.values();
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // DONATIONS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String DONATION_FIELDS = "id, AccountId, Account.Id, Account.Name, Account.RecordTypeId, Account.RecordType.Id, Account.RecordType.Name, ContactId, Amount, Name, RecordTypeId, RecordType.Id, RecordType.Name, CampaignId, Campaign.ParentId, CloseDate, StageName, Type, npe03__Recurring_Donation__c";

  public Optional<SObject> getDonationById(String donationId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where id = '" + donationId + "'";
    return querySingle(query);
  }

  // For processes like payout handling, we need to retrieve a lot of donations at once. Retrieve in batches to preserve API limits!
  public List<SObject> getDonationsByIds(List<String> ids) throws ConnectionException, InterruptedException {
    List<String> page;
    List<String> more;
    // SOQL has a 100k char limit for queries, so we're arbitrarily defining the page sizes...
    if (ids.size() > 1000) {
      page = ids.subList(0, 1000);
      more = ids.subList(1000, ids.size());
    } else {
      page = ids;
      more = Collections.emptyList();
    }

    String idsJoin = page.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where id in (" + idsJoin + ")";
    List<SObject> results = queryList(query);

    if (!more.isEmpty()) {
      results.addAll(getDonationsByIds(more));
    }

    return results;
  }

  public Optional<SObject> getDonationByTransactionId(String transactionId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewayTransactionId + " = '" + transactionId + "'";
    return querySingle(query);
  }

  // For processes like payout handling, we need to retrieve a lot of donations at once. Retrieve in batches to preserve API limits!
  public List<SObject> getDonationsByTransactionIds(List<String> transactionIds) throws ConnectionException, InterruptedException {
    List<String> page;
    List<String> more;
    // SOQL has a 100k char limit for queries, so we're arbitrarily defining the page sizes...
    if (transactionIds.size() > 1000) {
      page = transactionIds.subList(0, 1000);
      more = transactionIds.subList(1000, transactionIds.size());
    } else {
      page = transactionIds;
      more = Collections.emptyList();
    }

    String transactionIdsJoin = page.stream().map(transactionId -> "'" + transactionId + "'").collect(Collectors.joining(","));
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewayTransactionId + " in (" + transactionIdsJoin + ")";
    List<SObject> results = queryList(query);

    if (!more.isEmpty()) {
      results.addAll(getDonationsByTransactionIds(more));
    }

    return results;
  }

  public List<SObject> getDonationsByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where accountid = '" + accountId + "' AND StageName != 'Pledged' ORDER BY CloseDate DESC";
    return queryListAutoPaged(query);
  }

  public List<SObject> getFailingDonationsLastMonthByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where stageName = 'Failed Attempt' AND CloseDate = LAST_MONTH AND AccountId = '" + accountId + "'";
    return queryList(query);
  }

  public Optional<SObject> getLatestPostedDonation(String recurringDonationId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where npe03__Recurring_Donation__c = '" + recurringDonationId + "' and stageName = 'Posted' order by CloseDate desc limit 1";
    return querySingle(query);
  }

  public List<SObject> getDonationsInDeposit(String depositId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewayDepositId + " = '" + depositId + "'";
    return queryListAutoPaged(query);
  }

  public List<SObject> getRefundsInDeposit(String depositId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewayRefundDepositId + " = '" + depositId + "'";
    return queryListAutoPaged(query);
  }

  public Optional<SObject> getNextPledgedDonationByRecurringDonationId(String recurringDonationId) throws ConnectionException, InterruptedException {
    // TODO: Using TOMORROW to account for timezone issues -- we can typically get away with that approach
    // since most RDs are monthly...
    String query = "select " + getFieldsList(DONATION_FIELDS, env.getConfig().salesforce.customQueryFields.donation) + " from Opportunity where npe03__Recurring_Donation__c = '" + recurringDonationId + "' AND stageName = 'Pledged' AND CloseDate <= TOMORROW ORDER BY CloseDate Desc LIMIT 1";
    return querySingle(query);
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

  protected static final String RECURRINGDONATION_FIELDS = "id, name, npe03__Recurring_Donation_Campaign__c, npe03__Recurring_Donation_Campaign__r.Name, npe03__Next_Payment_Date__c, npe03__Installment_Period__c, npe03__Amount__c, npe03__Open_Ended_Status__c, npe03__Contact__c, npe03__Contact__r.Id, npe03__Contact__r.Name, npe03__Contact__r.Email, npe03__Contact__r.Phone, npsp__InstallmentFrequency__c, npe03__Schedule_Type__c, npe03__Date_Established__c, npe03__Organization__c, npe03__Organization__r.Id, npe03__Organization__r.Name";

  public Optional<SObject> getRecurringDonationById(String id) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation) + " from npe03__Recurring_Donation__c where id='" + id + "'";
    return querySingle(query);
  }

  public Optional<SObject> getRecurringDonationByName(String name) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation) + " from npe03__Recurring_Donation__c where name='" + name.replaceAll("'", "\\\\'") + "'";
    return querySingle(query);
  }

  public Optional<SObject> getRecurringDonationBySubscriptionId(String subscriptionId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation) + " from npe03__Recurring_Donation__c where " + env.getConfig().salesforce.fieldDefinitions.paymentGatewaySubscriptionId + " = '" + subscriptionId + "'";
    return querySingle(query);
  }

  public List<SObject> getRecurringDonationsByAccountId(String accountId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation) + " from npe03__Recurring_Donation__c where Npe03__Organization__c = '" + accountId + "'";
    return queryList(query);
  }

  public List<SObject> searchOpenRecurringDonations(List<String> clauses) throws InterruptedException, ConnectionException {
    String query = "select " + getFieldsList(RECURRINGDONATION_FIELDS, env.getConfig().salesforce.customQueryFields.recurringDonation) + " from npe03__Recurring_Donation__c where npe03__Open_Ended_Status__c = 'Open' and ((" + String.join(") AND (", clauses) + "))";
    return queryList(query);
  }

  public void refreshRecurringDonation(String donationId) throws ConnectionException {
    // TODO: set up 'FORCE_URL' env var and the appropriate apex enpoint for orgs so they this will work
    log.info("refreshing opportunities on {}...", donationId);

    String data = "{\"recurringDonationId\": \"" + donationId + "\"}";
    String sessionId = login().getSessionId();

    String sfdcEndpoint = env.getConfig().salesforce.forceUrl + "/services/apexrest/refreshrecurringdonation";

    post(sfdcEndpoint, data, APPLICATION_JSON, HttpClient.HeaderBuilder.builder().authBearerToken(sessionId));
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // USERS
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  protected static final String USER_FIELDS = "id, name, firstName, lastName, email, phone";

  public Optional<SObject> getUserById(String userId) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(USER_FIELDS, env.getConfig().salesforce.customQueryFields.user) + " from user where id = '" + userId + "'";
    return querySingle(query);
  }

  public Optional<SObject> getUserByEmail(String email) throws ConnectionException, InterruptedException {
    String query = "select " + getFieldsList(USER_FIELDS, env.getConfig().salesforce.customQueryFields.user) + " from user where isActive = true and email = '" + email + "'";
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

  protected String getFieldsList(String list, Collection<String> customList) {
    return customList.size() > 0 ? list + ", " + Joiner.on(", ").join(customList) : list;
  }
}
