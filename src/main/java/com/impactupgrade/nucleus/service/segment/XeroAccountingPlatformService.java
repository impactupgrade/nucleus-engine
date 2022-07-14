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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class XeroAccountingPlatformService implements AccountingPlatformService<Contact, Invoice> {

    private static final Logger log = LogManager.getLogger(XeroAccountingPlatformService.class);

    private static final String SUPPORTER_ID_FIELD_NAME = "Supporter_ID__c";
    // If false return 200 OK and mix of successfully created objects and any with validation errors
    private static final Boolean SUMMARIZE_ERRORS = Boolean.TRUE;
    // e.g. unitdp=4 – (Unit Decimal Places) You can opt in to use four decimal places for unit amounts
    private static final Integer UNITDP = 4;

    private final ApiClient apiClient;
    private final AccountingApi xeroApi;

    protected Environment env;

    protected String clientId;
    protected String clientSecret;
    protected String tokenServerUrl;

    protected static String accessToken;
    protected static String refreshToken;

    protected String xeroTenantId;

    public XeroAccountingPlatformService() {
        this.apiClient = new ApiClient();
        this.xeroApi = AccountingApi.getInstance(apiClient);
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
        this.env = environment;
        this.clientId = environment.getConfig().xero.clientId;
        this.clientSecret = environment.getConfig().xero.clientSecret;
        this.tokenServerUrl = environment.getConfig().xero.tokenServerUrl;
        //TODO: find a better way of saving access token
        if (this.accessToken == null) {
            this.accessToken = environment.getConfig().xero.accessToken;
        }
        if (this.refreshToken == null) {
            this.refreshToken = environment.getConfig().xero.refreshToken;
        }
        this.xeroTenantId = environment.getConfig().xero.tenantId;
    }

    @Override
    public List<Invoice> getTransactions(Date startDate) throws Exception {
        try {
            OffsetDateTime ifModifiedSince = OffsetDateTime.of(asLocalDateTime(startDate), ZoneOffset.UTC);
            Invoices invoicesResponse = xeroApi.getInvoices(getAccessToken(), xeroTenantId, ifModifiedSince,
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
            return invoicesResponse.getInvoices();
        } catch (Exception e) {
            log.error("Failed to get existing transactions info! {}", getExceptionDetails(e));
            // throw, since returning empty list here would be a bad idea -- likely implies reinserting duplicates
            throw e;
        }
    }

    @Override
    public String getCrmContactPrimaryKey(CrmContact crmContact) {
        return crmContact.email;
    }

    @Override
    public List<Contact> getContacts() throws Exception {
        try {
            log.info("Getting existing contacts...");
            Contacts contactsResponse = xeroApi.getContacts(getAccessToken(), xeroTenantId, null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Boolean.TRUE); // A smaller version of the response object
            List<Contact> contacts = contactsResponse.getContacts();
            log.info("Contacts found: {}", contacts.size());
            return contacts;
        } catch (Exception e) {
            log.error("Failed to get contacts info: {}", getExceptionDetails(e));
            // throw, since returning empty list here would be a bad idea -- likely implies reinserting duplicates
            throw e;
        }
    }

    @Override
    public List<Contact> createContacts(List<CrmContact> crmContacts) {
        // We create contacts in create invoices call
        // so here we just return the mapped list
        return toContacts(crmContacts);
    }

    private List<Contact> toContacts(List<CrmContact> crmContacts) {
        if (CollectionUtils.isEmpty(crmContacts)) {
            return Collections.emptyList();
        }
        return crmContacts.stream()
                .map(crmContact -> asContact(crmContact))
                .collect(Collectors.toList());
    }

    @Override
    public String getTransactionKey(Invoice transaction) {
        return transaction.getReference();
    }

    @Override
    public String getContactPrimaryKey(Contact contact) {
        return contact.getEmailAddress();
    }

    @Override
    public String getCrmContactSecondaryKey(CrmContact crmContact) {
        return crmContact.fullName();
    }

    @Override
    public String getContactSecondaryKey(Contact contact) {
        return contact.getName();
    }

    @Override
    public List<Invoice> createTransactions(List<PaymentGatewayEvent> transactions,
                                            Map<String, Contact> contactsByPrimaryKey,
                                            Map<String, Contact> contactsBySecondaryKey) throws Exception {
        log.info("Input transactions: {}", transactions.size());

        Invoices invoices = asInvoices(transactions, contactsByPrimaryKey, contactsBySecondaryKey);
        log.info("Invoices to create: {}", invoices.getInvoices().size());

        try {
            Invoices createdInvoices = xeroApi.createInvoices(getAccessToken(), xeroTenantId, invoices, SUMMARIZE_ERRORS, UNITDP);
            List<Invoice> createdItems = createdInvoices.getInvoices();

            log.info("Invoices created: {}", createdItems.size());
            return createdItems;

        } catch (Exception e) {
            log.error("Failed to create invoices! {}", getExceptionDetails(e));
            throw e;
        }
    }

    private String getAccessToken() throws IOException, JwkException {
        DecodedJWT jwt = null;
        try {
            jwt = JWT.decode(accessToken);
        } catch (Exception e) {
            log.warn("Failed to decode access token! {}", e.getMessage());
        }

        if (jwt == null || jwt.getExpiresAt().getTime() < System.currentTimeMillis()) {
            log.info("Refreshing tokens...");
            try {
                TokenResponse tokenResponse = new RefreshTokenRequest(new NetHttpTransport(), new JacksonFactory(),
                        new GenericUrl(tokenServerUrl), refreshToken)
                        .setClientAuthentication(new BasicAuthentication(this.clientId, this.clientSecret))
                        .execute();

                DecodedJWT verifiedJWT = apiClient.verify(tokenResponse.getAccessToken());

                log.info("Tokens refreshed!");

                accessToken = verifiedJWT.getToken();
                refreshToken = tokenResponse.getRefreshToken();

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

    private Contact asContact(CrmContact crmContact) {
        if (crmContact == null) {
            return null;
        }
        Contact contact = new Contact();
        contact.setFirstName(crmContact.firstName);
        contact.setLastName(crmContact.lastName);
        contact.setName(crmContact.fullName());
        contact.setEmailAddress(crmContact.email);

        // TODO: make this part not-sfdc specific?
        if (crmContact.rawObject instanceof SObject) {
            SObject sObject = (SObject) crmContact.rawObject;
            String supporterId = (String) sObject.getField(SUPPORTER_ID_FIELD_NAME);
            contact.setAccountNumber(supporterId);
        }

        return contact;
    }

    private List<LineItem> getLineItems(PaymentGatewayEvent paymentGatewayEvent) {
        if (Objects.isNull(paymentGatewayEvent)) {
            return Collections.emptyList();
        }

        LineItem lineItem = new LineItem();
        lineItem.setDescription(paymentGatewayEvent.getTransactionDescription());
        lineItem.setQuantity(1.0);
        lineItem.setUnitAmount(paymentGatewayEvent.getTransactionAmountInDollars());
        // TODO: DR TEST (https://developer.xero.com/documentation/api/accounting/types/#tax-rates -- country specific)
        lineItem.setTaxType("EXEMPTOUTPUT");

        // TODO: DR TEST -- need to be able to override with code
        if (paymentGatewayEvent.isTransactionRecurring()) {
            lineItem.setAccountCode("122");
            lineItem.setItemCode("Partner");
        } else {
            lineItem.setAccountCode("116");
            lineItem.setItemCode("Donate");
        }

        return Collections.singletonList(lineItem);
    }

    private LocalDateTime asLocalDateTime(Date date) {
        if (date == null) {
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
                        return asInvoice(transaction, contact);
                    }).forEach(invoices::addInvoicesItem);
        }
        return invoices;
    }

    private Invoice asInvoice(PaymentGatewayEvent paymentGatewayEvent, Contact contact) {
        if (paymentGatewayEvent == null) {
            return null;
        }
        Invoice invoice = new Invoice();

        Calendar transactionDate = paymentGatewayEvent.getTransactionDate();
        LocalDate localDate = LocalDate.of(
                transactionDate.get(Calendar.YEAR),
                transactionDate.get(Calendar.MONTH) + 1, // (valid values 1 - 12)
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
