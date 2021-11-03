package com.impactupgrade.nucleus.service.segment;

import com.auth0.jwk.JwkException;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.api.client.auth.oauth2.RefreshTokenRequest;
import com.google.api.client.auth.oauth2.TokenErrorResponse;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.http.BasicAuthentication;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.xero.api.ApiClient;
import com.xero.api.client.AccountingApi;
import com.xero.models.accounting.Account;
import com.xero.models.accounting.BankTransaction;
import com.xero.models.accounting.BankTransactions;
import com.xero.models.accounting.Contact;
import com.xero.models.accounting.Contacts;
import com.xero.models.accounting.CurrencyCode;
import com.xero.models.accounting.LineItem;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.threeten.bp.LocalDateTime;
import org.threeten.bp.OffsetDateTime;
import org.threeten.bp.ZoneOffset;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.xero.models.accounting.BankTransaction.TypeEnum.RECEIVE;

public class XeroAccountingPlatformService implements AccountingPlatformService<Contact, BankTransaction> {

    private static final Logger log = LogManager.getLogger(XeroAccountingPlatformService.class);

    // If false return 200 OK and mix of successfully created objects and any with validation errors
    private static Boolean summarizeErrors = Boolean.TRUE;
    // e.g. unitdp=4 – (Unit Decimal Places) You can opt in to use four decimal places for unit amounts
    private static Integer unitdp = 4;

    private String clientId;
    private String clientSecret;
    private String tokenServerUrl;

    private String accessToken;
    private String refreshToken;

    private String xeroTenantId;
    private String xeroAccountId;
    private String xeroAccountCode;

    private ApiClient apiClient;
    private AccountingApi accountingApi;

    protected Environment environment;

    public XeroAccountingPlatformService() {
        this.apiClient = new ApiClient();
        this.accountingApi = AccountingApi.getInstance(apiClient);
    }

    @Override
    public String name() {
        return "xero";
    }

    @Override
    public void init(Environment environment) {
        this.clientId = environment.getConfig().xero.clientId;
        this.clientSecret = environment.getConfig().xero.clientSecret;
        this.tokenServerUrl = environment.getConfig().xero.tokenServerUrl;

        this.accessToken = environment.getConfig().xero.accessToken;
        this.refreshToken = environment.getConfig().xero.refreshToken;
        this.xeroTenantId = environment.getConfig().xero.tenantId;
        this.xeroAccountId = environment.getConfig().xero.accountId;
        this.xeroAccountCode = environment.getConfig().xero.accountCode;

    }

    @Override
    public List<BankTransaction> getTransactions(Date startDate) throws Exception {
        try {
            OffsetDateTime ifModifiedSince = OffsetDateTime.of(asLocalDateTime(startDate), ZoneOffset.UTC);
            BankTransactions bankTransactionsResponse = accountingApi.getBankTransactions(getAccessToken(), xeroTenantId,
                    ifModifiedSince,
                    //String where = 'Status=="AUTHORISED"';
                    //String order = 'Type ASC';
                    //Integer page = 1;
                    //nteger unitdp = 4;
                    null, null, null, null);

            List<BankTransaction> bankTransactions = bankTransactionsResponse.getBankTransactions();

            // TODO: can we do it in where clause?
            if (Objects.nonNull(xeroAccountId)) {
                bankTransactions = bankTransactions.stream()
                        .filter(t -> xeroAccountId.equalsIgnoreCase(t.getBankAccount().getAccountID().toString()))
                        .collect(Collectors.toList());
            }

            return bankTransactions;
        } catch (Exception e) {
            log.error("Failed to get existing transactions info! {}", getExceptionDetails(e));
            throw e;
        }
    }

    @Override
    public Function<BankTransaction, String> getTransactionKeyFunction() {
        return BankTransaction::getReference;
    }

    @Override
    public List<Contact> getContacts() throws Exception {
        try {
            log.info("Getting existing contacts...");
            Contacts contactsResponse = accountingApi.getContacts(getAccessToken(), xeroTenantId, null, null, null, null, null, null, null);
            List<Contact> contacts = contactsResponse.getContacts();
            log.info("Contacts found: {}", contacts.size());
            return contacts;
        } catch (Exception e) {
            log.error("Failed to get contacts info: {}", getExceptionDetails(e));
            // Don't know if anything exist already - can NOT process further
            throw e;
        }
    }

    @Override
    public List<Contact> createContacts(List<CrmContact> crmContacts) {
        // We create contacts in create transaction call
        // so here we just return empty list
        return Collections.emptyList();
    }

    @Override
    public List<BankTransaction> createTransactions(List<PaymentGatewayEvent> transactions, Map<String, Contact> contactsByPrimaryKey, Map<String, Contact> contactsBySecondaryKey) throws Exception {
        log.info("Input transactions: {}", transactions.size());
        // Map transactions
        BankTransactions bankTransactions = asBankTransactions(transactions, contactsByPrimaryKey, contactsBySecondaryKey);
        log.info("Bank transactions to create: {}", bankTransactions.getBankTransactions().size());
        try {
            BankTransactions createdTransactions = accountingApi.createBankTransactions(getAccessToken(), xeroTenantId, bankTransactions, summarizeErrors, unitdp);
            log.info("Bank transactions created.");
            return createdTransactions.getBankTransactions();
        } catch (Exception e) {
            log.error("Failed to create bank transactions! {}", getExceptionDetails(e));
            throw e;
        }
    }

    @Override
    public Function<CrmContact, String> getCrmContactPrimaryKeyFunction() {
        return crmContact -> crmContact.email;
    }

    @Override
    public Function<Contact, String> getContactPrimaryKeyFunction() {
        return Contact::getEmailAddress;
    }

    @Override
    public Function<CrmContact, String> getCrmContactSecondaryKeyFunction() {
        return crmContact -> getContactName(crmContact);
    }

    @Override
    public Function<Contact, String> getContactSecondaryKeyFunction() {
        return Contact::getName;
    }

    private String getAccessToken() throws IOException, JwkException {
        DecodedJWT jwt = JWT.decode(accessToken);

        if (jwt.getExpiresAt().getTime() > System.currentTimeMillis()) {
        } else {
            log.info("Refreshing tokens...");
            try {
                TokenResponse tokenResponse = new RefreshTokenRequest(new NetHttpTransport(), new JacksonFactory(),
                        new GenericUrl(tokenServerUrl), refreshToken)
                        .setClientAuthentication(new BasicAuthentication(this.clientId, this.clientSecret))
                        .execute();

                DecodedJWT verifiedJWT = apiClient.verify(tokenResponse.getAccessToken());

                log.info("Tokens refreshed!");
                // TODO: temp -- need to store this in the org's env.json
                log.info("new refresh token: " + tokenResponse.getRefreshToken());

                accessToken = verifiedJWT.getToken();

            } catch (IOException | JwkException e) {
                log.error("Failed to refresh access token!");
                if (e instanceof TokenResponseException) {
                    TokenErrorResponse tokenErrorResponse = ((TokenResponseException) e).getDetails();
                    if (Objects.nonNull(tokenErrorResponse)) {
                        log.error(tokenErrorResponse.getError());
                        if (Objects.nonNull(tokenErrorResponse.getErrorDescription())) {
                            log.error(tokenErrorResponse.getErrorDescription());
                        }
                        if (Objects.nonNull(tokenErrorResponse.getErrorUri())) {
                            log.error(tokenErrorResponse.getErrorUri());
                        }
                    }
                }
                throw e;
            }
        }
        return accessToken;
    }

    // Utils
    private String getExceptionDetails(Exception e) {
        return Objects.nonNull(e) ? e.getClass() + ":" + e : null;
    }

    private BankTransactions asBankTransactions(List<PaymentGatewayEvent> transactions,
                                                Map<String, Contact> contactsMappedByPrimaryKey,
                                                Map<String, Contact> contactsMappedBySecondaryKey) {
        BankTransactions bankTransactions = new BankTransactions();

        if (CollectionUtils.isNotEmpty(transactions)) {
            transactions.stream()
                    .map(transaction -> {
                        BankTransaction bankTransaction = asBankTransaction(transaction);

                        CrmContact crmContact = transaction.getCrmContact();
                        Contact contact = getContactForCrmContact(crmContact, contactsMappedByPrimaryKey, contactsMappedBySecondaryKey);

                        if (Objects.nonNull(contact)) {
                            // Reuse existing contact
                            bankTransaction.setContact(contact);
                        } else {
                            // Create new contact
                            bankTransaction.setContact(asContact(crmContact));
                        }

                        return bankTransaction;
                    }).forEach(bankTransaction -> bankTransactions.addBankTransactionsItem(bankTransaction));
        }

        return bankTransactions;
    }

    private BankTransaction asBankTransaction(PaymentGatewayEvent paymentGatewayEvent) {
        if (Objects.isNull(paymentGatewayEvent)) {
            return null;
        }

        BankTransaction bankTransaction = new BankTransaction();

        //    private Account bankAccount;
        Account account = new Account();
        account.setAccountID(UUID.fromString(xeroAccountId));
        bankTransaction.setBankAccount(account);

        //    private BankTransaction.TypeEnum type;
        bankTransaction.setType(getBankTransactionType(paymentGatewayEvent));

        //    private Contact contact;
        //bankTransaction.setContact(asContact(paymentGatewayEvent.getCrmContact()));

        //    private List<LineItem> lineItems = new ArrayList();
        bankTransaction.setLineItems(getLineItems(paymentGatewayEvent));

        //bankTransaction.setIsReconciled(Boolean.FALSE); // ?
        Date paymentGatewayEventDate = paymentGatewayEvent.getTransactionDate().getTime();
        LocalDateTime transactionDateTime = asLocalDateTime(paymentGatewayEventDate);
        if (Objects.nonNull(transactionDateTime)) {
            bankTransaction.setDate(transactionDateTime.toLocalDate());
        }
        bankTransaction.setReference(paymentGatewayEvent.getTransactionId());
        //    private CurrencyCode currencyCode;
        // TODO: needs to be configurable
//        bankTransaction.setCurrencyCode(CurrencyCode.USD);
        bankTransaction.setCurrencyCode(CurrencyCode.AUD);
        //    private Double currencyRate;
        // TODO: likely not needed
//        bankTransaction.setCurrencyRate(paymentGatewayEvent.getTransactionExchangeRate());
        //    private String url;
        bankTransaction.setUrl(paymentGatewayEvent.getTransactionUrl());
        //    private BankTransaction.StatusEnum status;
        bankTransaction.setStatus(getStatusEnum(paymentGatewayEvent));
        //    private LineAmountTypes lineAmountTypes;
        //LineAmountTypes lineAmountTypes = paymentGatewayEvent.get // ?
        //    private Double subTotal;
        //bankTransaction.setSubTotal(paymentGatewayEvent.get); // ?
        //    private Double totalTax;
        //bankTransaction.setTotalTax(paymentGatewayEvent.get); // ?
        //    private Double total;
        bankTransaction.setTotal(paymentGatewayEvent.getTransactionAmountInDollars());

        //    private UUID bankTransactionID;
        //    private UUID prepaymentID;
        //    private UUID overpaymentID;
        //    private String updatedDateUTC;
        //    private Boolean hasAttachments = false;
        //    private String statusAttributeString;

        return bankTransaction;
    }

    private BankTransaction.TypeEnum getBankTransactionType(PaymentGatewayEvent paymentGatewayEvent) {
        if (Objects.isNull(paymentGatewayEvent)) {
            return null;
        }
        // TODO: send or receive?
        return RECEIVE;
    }

    private Contact asContact(CrmContact crmContact) {
        if (Objects.isNull(crmContact)) {
            return null;
        }
        Contact contact = new Contact();
        contact.setFirstName(crmContact.firstName);
        contact.setLastName(crmContact.lastName);
        contact.setName(getContactName(crmContact));
        contact.setAccountNumber(crmContact.accountId);
        contact.setEmailAddress(crmContact.email);
        return contact;
    }

    private String getContactName(CrmContact crmContact) {
        if (Objects.isNull(crmContact)) {
            return null;
        }
        return crmContact.firstName + " " + crmContact.lastName;
    }

    private List<LineItem> getLineItems(PaymentGatewayEvent paymentGatewayEvent) {
        if (Objects.isNull(paymentGatewayEvent)) {
            return Collections.emptyList();
        }

        LineItem lineItem = new LineItem();
        lineItem.setDescription(paymentGatewayEvent.getTransactionDescription());
        lineItem.setQuantity(1.0);
        lineItem.setUnitAmount(paymentGatewayEvent.getTransactionAmountInDollars());
//        lineItem.setTaxType("NONE");
        // TODO: DR TEST (https://developer.xero.com/documentation/api/accounting/types/#tax-rates -- country specific)
        lineItem.setTaxType("EXEMPTOUTPUT");

        // TODO: define correct account code for transaction
        // (can NOT be the same as contact we import to)
//        lineItem.setAccountCode(xeroAccountCode);
        // TODO: DR TEST -- need to be able to override with code
        if (paymentGatewayEvent.isTransactionRecurring()) {
            lineItem.setAccountCode("122");
        } else if (paymentGatewayEvent.getTransactionAmountInDollars() > 1500.0) {
            lineItem.setAccountCode("117");
        } else {
            lineItem.setAccountCode("116");
        }

        return Collections.singletonList(lineItem);
    }

    private BankTransaction.StatusEnum getStatusEnum(PaymentGatewayEvent paymentGatewayEvent) {
        if (Objects.isNull(paymentGatewayEvent)) {
            return null;
        }
        return paymentGatewayEvent.isTransactionSuccess() ?
                BankTransaction.StatusEnum.AUTHORISED : BankTransaction.StatusEnum.VOIDED;
    }

    private LocalDateTime asLocalDateTime(Date date) {
        if (Objects.isNull(date)) {
            return null;
        }
        return LocalDateTime.ofEpochSecond(date.toInstant().getEpochSecond(), 0, ZoneOffset.UTC);
    }

}
