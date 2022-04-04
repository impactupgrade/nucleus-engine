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
import com.sforce.soap.partner.sobject.SObject;
import com.xero.api.ApiClient;
import com.xero.api.client.AccountingApi;
import com.xero.models.accounting.Contact;
import com.xero.models.accounting.Contacts;
import com.xero.models.accounting.Invoice;
import com.xero.models.accounting.Invoices;
import com.xero.models.accounting.LineItem;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.threeten.bp.LocalDate;
import org.threeten.bp.LocalDateTime;
import org.threeten.bp.OffsetDateTime;
import org.threeten.bp.ZoneOffset;

import java.io.IOException;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class XeroAccountingPlatformService implements AccountingPlatformService<Contact, Invoice> {

    private static final Logger log = LogManager.getLogger(XeroAccountingPlatformService.class);

    private static final String SUPPORTER_ID_FIELD_NAME = "Supporter_ID__c";
    // If false return 200 OK and mix of successfully created objects and any with validation errors
    private static Boolean summarizeErrors = Boolean.TRUE;
    // e.g. unitdp=4 – (Unit Decimal Places) You can opt in to use four decimal places for unit amounts
    private static Integer unitdp = 4;

    private String clientId;
    private String clientSecret;
    private String tokenServerUrl;

    private static String accessToken;
    private static String refreshToken;

    private String xeroTenantId;
    private String xeroAccountId;
    private String xeroLineItemCode;

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
    public boolean isConfigured(Environment env) {
        return env.getConfig().xero != null;
    }

    @Override
    public void init(Environment environment) {
        this.environment = environment;
        this.clientId = environment.getConfig().xero.clientId;
        this.clientSecret = environment.getConfig().xero.clientSecret;
        this.tokenServerUrl = environment.getConfig().xero.tokenServerUrl;

        this.accessToken = environment.getConfig().xero.accessToken;
        this.refreshToken = environment.getConfig().xero.refreshToken;
        this.xeroTenantId = environment.getConfig().xero.tenantId;
        this.xeroAccountId = environment.getConfig().xero.accountId;
        this.xeroLineItemCode = environment.getConfig().xero.lineItemCode;
    }

    @Override
    public List<Invoice> getTransactions(Date startDate) throws Exception {
        try {
            OffsetDateTime ifModifiedSince = OffsetDateTime.of(asLocalDateTime(startDate), ZoneOffset.UTC);
            Invoices invoicesResponse = accountingApi.getInvoices(getAccessToken(), xeroTenantId, ifModifiedSince,
                    //String where,
                    null,
                    // String order,
                    null,
                    // List<UUID> ids,
                    null,
                    //List<String> invoiceNumbers,
                    null,
                    //List<UUID> contactIDs,
                    null,
                    //List<String> statuses,
                    List.of(
                            Invoice.StatusEnum.DRAFT.name(),
                            Invoice.StatusEnum.SUBMITTED.name(),
                            Invoice.StatusEnum.AUTHORISED.name(),
                            Invoice.StatusEnum.VOIDED.name()),
                    //Integer page,
                    null,
                    //Boolean includeArchived,
                    null,
                    //Boolean createdByMyApp,
                    null,
                    // Integer unitdp, Boolean summaryOnly
                    null, null);
            List<Invoice> invoices = invoicesResponse.getInvoices();
            return invoices;
        } catch (Exception e) {
            log.error("Failed to get existing transactions info! {}", getExceptionDetails(e));
            throw e;
        }
    }

    @Override
    public Function<Invoice, String> getTransactionKeyFunction() {
        return Invoice::getReference;
    }

    @Override
    public List<Contact> getContacts() throws Exception {
        try {
            log.info("Getting existing contacts...");
            Contacts contactsResponse = accountingApi.getContacts(getAccessToken(), xeroTenantId, null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
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
        if (CollectionUtils.isEmpty(crmContacts)) {
            return Collections.emptyList();
        }
        Set<String> ids = crmContacts.stream().map(c -> c.id).collect(Collectors.toSet());
        List<SObject> contactObjects;
        Map<String, String> supporterIds = new HashMap<>();
        try {
            contactObjects = environment.sfdcClient().getContactsByIds(ids);
            contactObjects.forEach(c -> {
                supporterIds.put(c.getId(), (String) c.getField(SUPPORTER_ID_FIELD_NAME));
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to get contacts info from SF!", e);

        }

        List<Contact> contacts = crmContacts.stream()
                .map(crmContact -> asContact(crmContact, supporterIds.get(crmContact.id)))
                .collect(Collectors.toList());

        // We create contacts in create invoices call
        // so here we just return mapped list
        return contacts;
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

    @Override
    public List<Invoice> createTransactions(List<PaymentGatewayEvent> transactions, Map<String, Contact> contactsByPrimaryKey, Map<String, Contact> contactsBySecondaryKey) throws Exception {
        log.info("Input transactions: {}", transactions.size());

        Invoices invoices = asInvoices(transactions, contactsByPrimaryKey, contactsBySecondaryKey);
        log.info("Invoices to create: {}", invoices.getInvoices().size());

        try {
            Invoices createdInvoices = accountingApi.createInvoices(getAccessToken(), xeroTenantId, invoices, summarizeErrors, unitdp);
            List<Invoice> createdItems = createdInvoices.getInvoices();

            log.info("Invoices created: {}", createdItems.size());
            return createdItems;

        } catch (Exception e) {
            log.error("Failed to create invoices! {}", getExceptionDetails(e));
            throw e;
        }
    }

    private String getAccessToken() throws IOException, JwkException {
        DecodedJWT jwt = JWT.decode(accessToken);

        if (jwt.getExpiresAt().getTime() < System.currentTimeMillis()) {
            log.info("Access token expired. Refreshing tokens...");
            try {
                TokenResponse tokenResponse = new RefreshTokenRequest(new NetHttpTransport(), new JacksonFactory(),
                        new GenericUrl(tokenServerUrl), refreshToken)
                        .setClientAuthentication(new BasicAuthentication(this.clientId, this.clientSecret))
                        .execute();

                DecodedJWT verifiedJWT = apiClient.verify(tokenResponse.getAccessToken());

                log.info("Tokens refreshed!");

                accessToken = verifiedJWT.getToken();
                refreshToken = tokenResponse.getRefreshToken();

                environment.getConfig().xero.accessToken = accessToken;
                environment.getConfig().xero.refreshToken = refreshToken;

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

    private Contact asContact(CrmContact crmContact, String supporterId) {
        if (Objects.isNull(crmContact)) {
            return null;
        }
        Contact contact = new Contact();
        contact.setFirstName(crmContact.firstName);
        contact.setLastName(crmContact.lastName);
        contact.setName(getContactName(crmContact));
        contact.setAccountNumber(supporterId);
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
        lineItem.setTaxType("NONE");
        // TODO: DR TEST (https://developer.xero.com/documentation/api/accounting/types/#tax-rates -- country specific)
        //lineItem.setTaxType("EXEMPTOUTPUT");

        // TODO: DR TEST -- need to be able to override with code
        if (paymentGatewayEvent.isTransactionRecurring()) {
            lineItem.setAccountCode("122");
        } else if (paymentGatewayEvent.getTransactionAmountInDollars() > 1500.0) {
            lineItem.setAccountCode("117");
        } else {
            lineItem.setAccountCode("116");
        }

        lineItem.setItemCode(xeroLineItemCode);

        return Collections.singletonList(lineItem);
    }

    private LocalDateTime asLocalDateTime(Date date) {
        if (Objects.isNull(date)) {
            return null;
        }
        return LocalDateTime.ofEpochSecond(date.toInstant().getEpochSecond(), 0, ZoneOffset.UTC);
    }

    private Invoices asInvoices(List<PaymentGatewayEvent> transactions,
                                Map<String, Contact> contactsMappedByPrimaryKey,
                                Map<String, Contact> contactsMappedBySecondaryKey) {
        Invoices invoices = new Invoices();
        if (CollectionUtils.isNotEmpty(transactions)) {
            transactions.stream()
                    .map(transaction -> {
                        CrmContact crmContact = transaction.getCrmContact();
                        Contact contact = getContactForCrmContact(crmContact,
                                contactsMappedByPrimaryKey, contactsMappedBySecondaryKey);
                        Invoice invoice = asInvoice(transaction, contact);
                        return invoice;
                    }).forEach(invoice -> invoices.addInvoicesItem(invoice));
        }
        return invoices;
    }

    private Invoice asInvoice(PaymentGatewayEvent paymentGatewayEvent, Contact contact) {
        if (Objects.isNull(paymentGatewayEvent)) {
            return null;
        }
        Invoice invoice = new Invoice();

        Calendar transactionDate = paymentGatewayEvent.getTransactionDate();
        LocalDate localDate = LocalDate.of(
                transactionDate.get(Calendar.YEAR),
                transactionDate.get(Calendar.MONTH),
                transactionDate.get(Calendar.DATE)
        );
        invoice.setDate(localDate);
        invoice.setDueDate(localDate);
        invoice.setContact(contact);

        invoice.setLineItems(getLineItems(paymentGatewayEvent));
        invoice.setType(Invoice.TypeEnum.ACCREC); // Receive

        invoice.setReference(paymentGatewayEvent.getTransactionId());

        return invoice;
    }

}
