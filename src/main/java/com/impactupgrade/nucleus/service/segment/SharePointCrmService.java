package com.impactupgrade.nucleus.service.segment;

import com.google.api.client.util.Charsets;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.io.ByteSource;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SharePointCrmService implements CrmService {

    private static final Logger log = LogManager.getLogger(SharePointCrmService.class);

    private static final String CACHE_KEY_DELIMITER = "::";

    protected Environment env;
    protected MSGraphClient msGraphClient;
    protected LoadingCache<String, List<Map<String, String>>> sharepointCsvCache;

    @Override
    public String name() {
        return "sharepoint";
    }

    @Override
    public boolean isConfigured(Environment env) {
        return env.getConfig().sharePoint != null;
    }

    @Override
    public void init(Environment env) {
        this.env = env;
        this.msGraphClient = new MSGraphClient(env.getConfig().sharePoint);
        this.sharepointCsvCache = CacheBuilder.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build(new CacheLoader<>() {
                    @Override
                    public List<Map<String, String>> load(String cacheKey) {
                        String[] split = cacheKey.split(CACHE_KEY_DELIMITER);
                        return downloadCsvData(split[0], split[1]);
                    }
                });
    }

    private List<Map<String, String>> downloadCsvData(String siteUrl, String pathToFile) {
        log.info("downloading data for {}/{}...", siteUrl, pathToFile);
        InputStream inputStream = msGraphClient.getSiteDriveItemByPath(siteUrl, pathToFile);
        ByteSource byteSource = new ByteSource() {
            @Override
            public InputStream openStream() {
                return inputStream;
            }
        };
        List<Map<String, String>> csvData = Collections.emptyList();
        try {
            String inputStreamString = byteSource.asCharSource(Charsets.UTF_8).read();
            csvData = Utils.getCsvData(inputStreamString);
        } catch (IOException e) {
            log.error("Failed to get csv data! {}", e.getMessage());

        }
        return csvData;
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
        List<Map<String, String>> csvData = getCsvData();
        List<CrmContact> foundContacts = csvData.stream()
                .filter(csvRow -> StringUtils.equals(id, csvRow.get("id"))) // TODO: define correct csv header/map key
                .map(this::toCrmContact)
                .collect(Collectors.toList());
        return CollectionUtils.isNotEmpty(foundContacts) ?
                Optional.of(foundContacts.get(0)) : Optional.empty();
    }

    @Override
    public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) throws Exception {
        List<Map<String, String>> csvData = getCsvData();
        String csvColumn;
        String searchValue;
        if (!Strings.isNullOrEmpty(contactSearch.email)) {
            csvColumn = "email"; // ?
            searchValue = contactSearch.email;

        } else if (!Strings.isNullOrEmpty(contactSearch.phone)) {
            csvColumn = "Mobile #";
            searchValue = contactSearch.phone;
        } else {
            csvColumn = null; // any column
            searchValue = contactSearch.keywords;
        }

        List<CrmContact> foundContacts = new ArrayList<>();
        for (Map<String, String> csvRow : csvData) {
            boolean matches;
            if (!StringUtils.isEmpty(csvColumn)) {
                matches = StringUtils.equals(csvRow.get(csvColumn), searchValue);
            } else {
                matches = csvRow.values().stream()
                        .filter(csvValue -> csvValue.contains(searchValue))
                        .findFirst()
                        .isPresent();
            }
            if (matches) foundContacts.add(toCrmContact(csvRow));
        }
        List<CrmContact> searchResults = foundContacts.stream()
                .skip(Long.parseLong(contactSearch.pageToken))
                .limit(contactSearch.pageSize)
                .collect(Collectors.toList());
        return getPagedResults(searchResults, contactSearch);
    }

    private List<Map<String, String>> getCsvData() {
        String username1 = "username1"; // TODO: pass this from outside
        EnvironmentConfig.SharePointUserConfiguration userConfiguration = env.getConfig().sharePoint.userConfigurations.get(username1);
        String siteUrl = userConfiguration.siteUrl;
        String pathToFile = userConfiguration.pathToFile;
        return sharepointCsvCache.getUnchecked(toCacheKey(siteUrl, pathToFile));
    }

    private String toCacheKey(String siteUrl, String pathToFile) {
        return siteUrl + CACHE_KEY_DELIMITER + pathToFile;
    }

    private PagedResults<CrmContact> getPagedResults(List<CrmContact> crmContacts, ContactSearch contactSearch) {
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
        return null;
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
    public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
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
        CrmContact crmContact = new CrmContact();
        crmContact.firstName = map.get("First Name");
        crmContact.lastName = map.get("Last Name");
        crmContact.mobilePhone = map.get("Mobile #");
        return crmContact;
    }

}
