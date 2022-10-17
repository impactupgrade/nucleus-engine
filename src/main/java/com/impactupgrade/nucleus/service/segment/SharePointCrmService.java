package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.impactupgrade.nucleus.client.MSGraphClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.OpportunityEvent;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.util.Utils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// TODO: Note that this class is super specific to LLS' Excel/Sharepoint service and needs to be more configuration driven!
public class SharePointCrmService implements CrmService {

    private static final Logger log = LogManager.getLogger(SharePointCrmService.class);

    private static final String CACHE_KEY = "csvData";

    // The Map<String, List<Map<String, String>>> datatype is bonkers, but here's what's happening.
    // Map<FILENAME, List<ROWS>>
    // IE, we're storing a map of spreadsheet data, keyed by the original filename it came from.
    // For complex rules, like filters based on directories/files, we need to know the origin and prevent flattening.
    // TODO: If this gets worse, wrap it in a class...
    protected static LoadingCache<String, Map<String, List<Map<String, String>>>> sharepointCsvCache;

    protected Environment env;
    protected MSGraphClient msGraphClient;

    @Override
    public String name() {
        return "sharepoint";
    }

    @Override
    public boolean isConfigured(Environment env) {
        return !Strings.isNullOrEmpty(env.getConfig().sharePoint.clientId);
    }

    @Override
    public void init(Environment env) {
        this.env = env;
        msGraphClient = new MSGraphClient(env.getConfig().sharePoint);
        if (sharepointCsvCache == null) {
            sharepointCsvCache = CacheBuilder.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build(new CacheLoader<>() {
                    @Override
                    public Map<String, List<Map<String, String>>> load(String cacheKey) throws IOException {
                        return downloadCsvDataMap();
                    }
                });
            // warm the cache
            getCsvDataMap();
        }
    }

    protected Map<String, List<Map<String, String>>> downloadCsvDataMap() throws IOException {
        EnvironmentConfig.SharePointPlatform sharepoint = env.getConfig().sharePoint;
        String siteId = sharepoint.siteId;

        Map<String, List<Map<String, String>>> dataMap = new HashMap<>();

        for (String filePath : sharepoint.filePaths) {
            log.info("downloading data for {}/{}...", siteId, filePath);

            List<Map<String, String>> csvData = downloadCsvData(siteId, filePath);
            dataMap.put(filePath, csvData);
        }
        return dataMap;
    }

    protected List<Map<String, String>> downloadCsvData(String siteId, String filePath) throws IOException {
        List<Map<String, String>> csvData = new ArrayList<>();

        try (InputStream inputStream = msGraphClient.getSiteDriveItemByPath(siteId, filePath)) {
            if (filePath.endsWith("csv")) {
                csvData.addAll(Utils.getCsvData(inputStream));
            } else if (filePath.endsWith("xlsx")) {
                csvData.addAll(Utils.getExcelData(inputStream));
            } else {
                throw new RuntimeException("unexpected file extension for filePath " + filePath);
            }
        }

        return csvData;
    }

    protected Map<String, List<Map<String, String>>> getCsvDataMap() {
        return sharepointCsvCache.getUnchecked(CACHE_KEY);
    }

    // for the times when we don't care about the origin and just need one big flattened list
    protected List<Map<String, String>> getFlattenedCsvData() {
        return getCsvDataMap().values().stream().flatMap(Collection::stream).collect(Collectors.toList());
    }

    @Override
    public Optional<CrmAccount> getAccountById(String id) throws Exception {
        return Optional.empty();
    }

    @Override
    public Optional<CrmAccount> getAccountByCustomerId(String customerId) throws Exception {
        return Optional.empty();
    }

    @Override
    public Optional<CrmContact> getContactById(String id) throws Exception {
        String idColumn = env.getConfig().sharePoint.idColumn;

        // Ignore the origin here and flatten the map.
        List<Map<String, String>> csvData = getFlattenedCsvData();
        List<CrmContact> foundContacts = csvData.stream()
                .filter(csvRow -> StringUtils.equals(id, csvRow.get(idColumn)))
                .map(this::toCrmContact)
                .collect(Collectors.toList());
        return CollectionUtils.isNotEmpty(foundContacts) ?
                Optional.of(foundContacts.get(0)) : Optional.empty();
    }
    @Override
    public Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception {
        //Not currently implemented
        return Optional.empty();
    }

    @Override
    public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) throws Exception {
        EnvironmentConfig.SharePointPlatform sharepoint = env.getConfig().sharePoint;
        String emailColumn = sharepoint.emailColumn;
        String phoneColumn = sharepoint.phoneColumn;

        List<Map<String, String>> csvData = getFilteredCsvData(contactSearch);

        List<CrmContact> foundContacts = new ArrayList<>();
        for (Map<String, String> csvRow : csvData) {
            if (!Strings.isNullOrEmpty(contactSearch.email)) {
                if (!Strings.isNullOrEmpty(csvRow.get(emailColumn)) && csvRow.get(emailColumn).toLowerCase(Locale.ROOT).equals(contactSearch.email.toLowerCase(Locale.ROOT))) {
                    foundContacts.add(toCrmContact(csvRow));
                }
            } else if (!Strings.isNullOrEmpty(contactSearch.phone)) {
                // TODO: We need to country codes to either be present or not present on BOTH sides, so for now we're
                //  simply removing them. However, this needs rethought, especially for non-US numbers.
                if (!Strings.isNullOrEmpty(csvRow.get(phoneColumn)) && csvRow.get(phoneColumn).replace("+1", "").replaceAll("[^\\d]", "").equals(contactSearch.phone.replace("+1", "").replaceAll("[^\\d]", ""))) {
                    foundContacts.add(toCrmContact(csvRow));
                }
            } else if (!Strings.isNullOrEmpty(contactSearch.keywords)) {
                if (keywordMatch(contactSearch.keywords, csvRow)) {
                    foundContacts.add(toCrmContact(csvRow));
                }
            } else {
                // no search parameters -- return all
                foundContacts.add(toCrmContact(csvRow));
            }
        }

        long skip = contactSearch.pageToken == null ? 0L : Long.parseLong(contactSearch.pageToken);
        long limit = contactSearch.pageSize == null ? 100L : (long) contactSearch.pageSize;
        List<CrmContact> searchResults = foundContacts.stream()
                .skip(skip)
                .limit(limit)
                .collect(Collectors.toList());
        return getPagedResults(searchResults, contactSearch);
    }

    // Separate method, since some orgs will need to fully customize the filtering rules.
    protected List<Map<String, String>> getFilteredCsvData(ContactSearch contactSearch) {
        String ownerColumn = env.getConfig().sharePoint.ownerColumn;

        // By default, return 1) all rows, if there is now owner in the search or 2) rows filtered by the owner.
        return getFlattenedCsvData().stream()
            .filter(e -> Strings.isNullOrEmpty(contactSearch.ownerId) || contactSearch.ownerId.toLowerCase(Locale.ROOT).equals(e.get(ownerColumn).toLowerCase(Locale.ROOT)))
        .collect(Collectors.toList());
    }

    // Separate method, since some orgs will want to skip specific columns.
    protected boolean keywordMatch(String keywords, Map<String, String> csvRow) {
        String[] keywordSplit = keywords.split("[^\\w]+");
        // TODO: Not a super performant way of doing this...
        for (String keyword : keywordSplit) {
            boolean found = csvRow.entrySet().stream()
                .filter(entry -> !env.getConfig().sharePoint.searchColumnsToSkip.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(Objects::nonNull)
                .anyMatch(csvValue -> csvValue.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)));
            if (!found) {
                return false;
            }
        }
        return true;
    }

    protected PagedResults<CrmContact> getPagedResults(List<CrmContact> crmContacts, ContactSearch contactSearch) {
        Stream<CrmContact> contactStream = crmContacts.stream();
        if (contactSearch.pageToken != null) {
            long pageToken = 0;
            try {
                pageToken = Long.parseLong(contactSearch.pageToken);
            } catch (NumberFormatException nfe) {
                log.warn("Failed to parse long from string {}!", contactSearch.pageToken);
                // Ignore
            }
            contactStream = contactStream.skip(pageToken);
        }
        if (contactSearch.pageSize != null) {
            contactStream = contactStream.limit(contactSearch.pageSize);
        }
        List<CrmContact> results = contactStream.collect(Collectors.toList());
        return new PagedResults<>(results, contactSearch.pageSize, contactSearch.pageToken);
    }

    @Override
    public String insertAccount(CrmAccount crmAccount) throws Exception {
        return null;
    }

    @Override
    public void updateAccount(CrmAccount crmAccount) throws Exception {

    }

    @Override
    public void deleteAccount(String accountId) throws Exception {

    }

    @Override
    public String insertContact(CrmContact crmContact) throws Exception {
        return null;
    }

    @Override
    public void updateContact(CrmContact crmContact) throws Exception {

    }

    @Override
    public void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception {

    }

    @Override
    public List<CrmContact> getContactsFromList(String listId) throws Exception {
        // listId is assumed to be the file/path of a one-off sheet
        return downloadCsvData(env.getConfig().sharePoint.siteId, listId).stream()
            .map(this::toCrmContact).collect(Collectors.toList());
    }

    @Override
    public void addContactToList(CrmContact crmContact, String listId) throws Exception {

    }

    @Override
    public void removeContactFromList(CrmContact crmContact, String listId) throws Exception {

    }

    @Override
    public Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
        return Optional.empty();
    }

    @Override
    public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId) throws Exception {
        return Optional.empty();
    }

    @Override
    public List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception {
        return null;
    }

    @Override
    public List<CrmRecurringDonation> searchOpenRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception {
        return null;
    }
    @Override
    public List<CrmRecurringDonation> searchAllRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception {
        return null;
    }
    @Override
    public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        return null;
    }

    @Override
    public void insertDonationReattempt(PaymentGatewayEvent paymentGatewayEvent) throws Exception {

    }

    @Override
    public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {

    }

    @Override
    public void insertDonationDeposit(List<PaymentGatewayEvent> paymentGatewayEvents) throws Exception {

    }

    @Override
    public String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        return null;
    }

    @Override
    public void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {

    }

    @Override
    public Optional<CrmRecurringDonation> getRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
        return Optional.empty();
    }

    @Override
    public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {

    }

    @Override
    public void closeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {

    }

    @Override
    public String insertOpportunity(OpportunityEvent opportunityEvent) throws Exception {
        return null;
    }

    @Override
    public List<CrmDonation> getDonationsByAccountId(String accountId) throws Exception {
        return null;
    }

    @Override
    public List<CrmContact> getEmailContacts(Calendar updatedSince, String filter) throws Exception {
        return null;
    }

    @Override
    public Map<String, List<String>> getActiveCampaignsByContactIds(List<String> contactIds) throws Exception {
        return null;
    }

    @Override
    public double getDonationsTotal(String filter) throws Exception {
        return 0;
    }

    @Override
    public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {

    }

    @Override
    public void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception {

    }

    @Override
    public Optional<CrmUser> getUserById(String id) throws Exception {
        return Optional.empty();
    }

    @Override
    public Optional<CrmUser> getUserByEmail(String email) throws Exception {
        return Optional.empty();
    }

    @Override
    public String insertTask(CrmTask crmTask) throws Exception {
        return null;
    }

    @Override
    public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
        return null;
    }

    protected CrmContact toCrmContact(Map<String, String> map) {
        EnvironmentConfig.SharePointPlatform sharepoint = env.getConfig().sharePoint;
        String ownerColumn = sharepoint.ownerColumn;
        String phoneColumn = sharepoint.phoneColumn;

        CrmContact crmContact = new CrmContact();
        crmContact.firstName = map.get("First Name");
        crmContact.lastName = map.get("Last Name");
        crmContact.mobilePhone = map.get(phoneColumn);
        crmContact.ownerId = map.get(ownerColumn);
        crmContact.rawObject = map;
        crmContact.fieldFetcher = map::get;
        return crmContact;
    }

}
