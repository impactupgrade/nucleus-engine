package com.impactupgrade.nucleus.service.segment;

import com.google.gson.Gson;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.intuit.ipp.core.Context;
import com.intuit.ipp.core.IEntity;
import com.intuit.ipp.core.ServiceType;
import com.intuit.ipp.data.Customer;
import com.intuit.ipp.data.Payment;
import com.intuit.ipp.data.ReferenceType;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.security.OAuth2Authorizer;
import com.intuit.ipp.services.DataService;
import com.intuit.ipp.services.QueryResult;
import com.intuit.ipp.util.Config;
import com.intuit.oauth2.client.OAuth2PlatformClient;
import com.intuit.oauth2.config.OAuth2Config;
import com.intuit.oauth2.data.BearerTokenResponse;
import com.intuit.oauth2.exception.OAuthException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class QuickBooksAccountingPlatformService implements AccountingPlatformService<Customer, Payment> {

    private String clientId;
    private String clientSecret;

    private String accessToken;
    private String refreshToken;
    private Long lastTokenUpdateTimestamp;

    private static final Long ACCESS_TOKEN_EXPIRES_IN = 3600l;
    private static final Gson GSON = new Gson();

    private String realmId;
    private String apiBaseUrl;

    public String name() {
        return "quickbooks";
    }

    public void init(Environment environment) {
        this.clientId = environment.getConfig().quickbooks.clientId;
        this.clientSecret = environment.getConfig().quickbooks.clientSecret;

        this.accessToken = environment.getConfig().quickbooks.accessToken;
        this.refreshToken = environment.getConfig().quickbooks.refreshToken;

        this.realmId = environment.getConfig().quickbooks.realmId;
        this.apiBaseUrl = environment.getConfig().quickbooks.apiBaseUrl;
    }


    @Override
    public List<Payment> getTransactions(Date startDate) throws Exception {
        return getEntities(Query.GET_PAYMENTS);
    }

    @Override
    public Function<Payment, String> getTransactionKeyFunction() {
        return Payment::getPrivateNote;
    }

    @Override
    public List<Customer> getContacts() throws Exception {
        return getEntities(Query.GET_CUTOMERS);
    }

    @Override
    public List<Customer> createContacts(List<CrmContact> crmContacts) throws Exception {
        if (CollectionUtils.isEmpty(crmContacts)) {
            return Collections.emptyList();
        }

        List<Customer> createdCustomers = new ArrayList<>();
        for (CrmContact crmContact : crmContacts) {
            Customer customer = asCustomer(crmContact);
            String displayName = customer.getDisplayName();
            log.info("Creating customer '{}'...", displayName);
            Customer createdCustomer = addEntity(customer);
            log.info("Customer '{}' created!", displayName);
            createdCustomers.add(createdCustomer);
        }
        return createdCustomers;
    }

    @Override
    public Function<CrmContact, String> getCrmContactPrimaryKeyFunction() {
        return this::getCrmContactDisplayName;
    }

    @Override
    public Function<Customer, String> getContactPrimaryKeyFunction() {
        return Customer::getDisplayName;
    }

    @Override
    public Function<CrmContact, String> getCrmContactSecondaryKeyFunction() {
        return crmContact -> crmContact.email;
    }

    @Override
    public Function<Customer, String> getContactSecondaryKeyFunction() {
        return this::getCustomerEmail;
    }

    @Override
    public List<Payment> createTransactions(List<PaymentGatewayEvent> transactions, Map<String, Customer> contactsByPrimaryKey, Map<String, Customer> contactsBySecondaryKey) throws Exception {
        if (CollectionUtils.isEmpty(transactions)) {
            return Collections.emptyList();
        }

        List<Payment> createdPayments = new ArrayList<>();
        for (PaymentGatewayEvent transaction : transactions) {

            CrmContact crmContact = transaction.getCrmContact();
            Customer customer = getContactForCrmContact(crmContact, contactsByPrimaryKey, contactsBySecondaryKey);

            if (Objects.isNull(customer)) {
                // Should be unreachable
                log.info("Failed to get customer for crmContact {}! Skipping transaction {}...", crmContact, transaction.getTransactionId());
                continue;
            }

            // Convert to payment
            Payment payment = asPayment(transaction);
            // Add customer info
            ReferenceType referenceType = new ReferenceType();
            referenceType.setValue(customer.getId());
            payment.setCustomerRef(referenceType);

            Payment createdPayment = addEntity(payment);
            createdPayments.add(createdPayment);
        }
        return createdPayments;
    }

    // Utils
    private boolean isValidToken() {
        if (Objects.isNull(lastTokenUpdateTimestamp)) {
            return false;
        }
        return lastTokenUpdateTimestamp + ACCESS_TOKEN_EXPIRES_IN < System.currentTimeMillis();
    }

    private String getAccessToken() throws OAuthException {
        if (isValidToken()) {
            log.info("Token is valid. No need to refresh.");
        } else {

            OAuth2Config oauth2Config = new OAuth2Config.OAuth2ConfigBuilder(clientId, clientSecret) //set client id, secret
                    .callDiscoveryAPI(com.intuit.oauth2.config.Environment.SANDBOX) // call discovery API to populate urls
                    .buildConfig();
            OAuth2PlatformClient client = new OAuth2PlatformClient(oauth2Config);

            log.info("Token not valid. Refreshing...");
            BearerTokenResponse bearerTokenResponse = client.refreshToken(refreshToken);
            log.info("Refreshing done!");

            lastTokenUpdateTimestamp = System.currentTimeMillis();

            this.accessToken = bearerTokenResponse.getAccessToken();
            this.refreshToken = bearerTokenResponse.getRefreshToken();
        }
        return accessToken;
    }

    private String getCrmContactDisplayName(CrmContact crmContact) {
        if (Objects.isNull(crmContact)) {
            return null;
        }
        return crmContact.firstName + " " + crmContact.lastName;
    }

    private String getCustomerEmail(Customer customer) {
        if (Objects.isNull(customer) || Objects.isNull(customer.getPrimaryEmailAddr())) {
            return null;
        }
        return customer.getPrimaryEmailAddr().getAddress();
    }

    private Customer asCustomer(CrmContact crmContact) {
        if (Objects.isNull(crmContact)) {
            return null;
        }
        Customer customer = new Customer();
        customer.setGivenName(crmContact.firstName);
        customer.setFamilyName(crmContact.lastName);
        String displayName = getCrmContactDisplayName(crmContact);
        customer.setDisplayName(displayName);
        customer.setContactName(displayName);
        return customer;
    }

    private Payment asPayment(PaymentGatewayEvent transaction) {
        if (Objects.isNull(transaction)) {
            return null;
        }
        Payment payment = new Payment();
        // TODO: set all required fields (QB requires only amount and cutomer ref/id)
        BigDecimal transactionAmount = new BigDecimal(transaction.getTransactionOriginalAmountInDollars());
        payment.setTotalAmt(transactionAmount);
        payment.setTxnDate(transaction.getTransactionDate().getTime());
        payment.setPrivateNote(transaction.getTransactionId());

        return payment;
    }

    private <T extends IEntity> List<T> getEntities(Query query) throws FMSException, OAuthException {
        QueryResult queryResult = getQueryResult(query.query);
        List<T> entities = mapQueryResult(queryResult, query.clazz);
        return entities;
    }

    private <T extends IEntity> T addEntity(T entity) throws FMSException, OAuthException {
        Config.setProperty(Config.BASE_URL_QBO, apiBaseUrl + "/v3/company"); // ?

        OAuth2Authorizer oauth = new OAuth2Authorizer(getAccessToken());
        Context context = new Context(oauth, ServiceType.QBO, realmId);
        DataService service = new DataService(context);
        T added = service.add(entity);

        return added;
    }

    private QueryResult getQueryResult(String sqlQuery) throws FMSException, OAuthException {
        Config.setProperty(Config.BASE_URL_QBO, apiBaseUrl + "/v3/company");
        OAuth2Authorizer oauth = new OAuth2Authorizer(getAccessToken());

        Context context = new Context(oauth, ServiceType.QBO, realmId);
        DataService service = new DataService(context);
        QueryResult queryResult = service.executeQuery(sqlQuery);

        return queryResult;
    }

    private <T> List<T> mapQueryResult(QueryResult queryResult, Class<T> clazz) {
        if (Objects.isNull(queryResult) || CollectionUtils.isEmpty(queryResult.getEntities())) {
            return Collections.emptyList();
        }
        return queryResult.getEntities().stream()
                .map(clazz::cast)
                .collect(Collectors.toList());
    }

    @Getter
    @Setter
    @AllArgsConstructor
    private static final class Query {
        private String query;
        private Class clazz;

        public static final Query GET_CUTOMERS =
                new Query("select * from customer", Customer.class);

        public static final Query GET_PAYMENTS =
                new Query("select * from payment", Payment.class);
    }

}
