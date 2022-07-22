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
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.AccountingTransaction;
import com.impactupgrade.nucleus.model.CrmContact;
import com.sforce.soap.partner.sobject.SObject;
import com.xero.api.ApiClient;
import com.xero.api.client.AccountingApi;
import com.xero.models.accounting.Address;
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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public class XeroAccountingPlatformService implements AccountingPlatformService {

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
    public List<AccountingTransaction> getTransactions(Date startDate) throws Exception {
        try {
            List<Invoice> allInvoices = new ArrayList<>();
            int page = 1;
            int currentPageSize;

            do {
                List<Invoice> invoicesPage = getInvoices(startDate, page);
                allInvoices.addAll(invoicesPage);
                currentPageSize = invoicesPage.size();
                page++;
            } while (currentPageSize == 100);

            return allInvoices.stream().map(invoice -> {
                // references are, ex, Stripe:ch______
                String reference = invoice.getReference().toLowerCase(Locale.ROOT);
                String paymentGatewayTransactionId = reference.startsWith("stripe:") ? reference.replace("stripe:", "") : null;
                // TODO: Shouldn't need most of the fields?
                return new AccountingTransaction(
                    null,
                    null,
                    null,
                    null,
                    null,
                    paymentGatewayTransactionId,
                    null,
                    null
                );
            // Older transactions may not have the stripe: setup, so skip those entirely -- we have no way of pulling the ID from them.
            }).filter(transaction -> !Strings.isNullOrEmpty(transaction.paymentGatewayTransactionId)).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to get existing transactions info! {}", getExceptionDetails(e));
            // throw, since returning empty list here would be a bad idea -- likely implies reinserting duplicates
            throw e;
        }
    }

    protected List<Invoice> getInvoices(Date startDate, int page) throws Exception {
        OffsetDateTime ifModifiedSince = OffsetDateTime.of(asLocalDateTime(startDate), ZoneOffset.UTC);
        Invoices invoicesResponse = xeroApi.getInvoices(getAccessToken(), xeroTenantId,
            // OffsetDateTime ifModifiedSince
            ifModifiedSince,
            //String where,
            "Reference.StartsWith(\"Stripe\")",
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
                Invoice.StatusEnum.PAID.name()
            ),
            //Integer page,
            page,
            //Boolean includeArchived,
            false,
            //Boolean createdByMyApp,
            null,
            // Integer unitdp
            null,
            // Boolean summaryOnly
            false //The supplied filter (where) is unavailable on this endpoint when summaryOnly=true
        );
        return invoicesResponse.getInvoices();
    }

    @Override
    public Map<String, String> updateOrCreateContacts(List<CrmContact> crmContacts) throws Exception {
        if (CollectionUtils.isEmpty(crmContacts)) {
            return Collections.emptyMap();
        }
        List<Contact> contactsToCreate = toContacts(crmContacts);
        Contacts contacts = new Contacts();
        contacts.setContacts(contactsToCreate);
        try {
            return xeroApi.updateOrCreateContacts(getAccessToken(), xeroTenantId, contacts, SUMMARIZE_ERRORS).getContacts().stream()
                // account number is set as the crm contact id
                .collect(Collectors.toMap(Contact::getAccountNumber, contact -> contact.getContactID().toString()));
        } catch (Exception e) {
            log.error("Failed to upsert contacts! {}", getExceptionDetails(e));
            return Collections.emptyMap();
        }
    }

    protected List<Contact> toContacts(List<CrmContact> crmContacts) {
        if (CollectionUtils.isEmpty(crmContacts)) {
            return Collections.emptyList();
        }
        return crmContacts.stream()
                .map(crmContact -> asContact(crmContact))
                .collect(Collectors.toList());
    }

    @Override
    public void createTransactions(List<AccountingTransaction> transactions) throws Exception {
        log.info("Input transactions: {}", transactions.size());

        Invoices invoices = new Invoices().invoices(transactions.stream().map(this::asInvoice).collect(Collectors.toList()));
        log.info("Invoices to create: {}", invoices.getInvoices().size());

        try {
            Invoices createdInvoices = xeroApi.createInvoices(getAccessToken(), xeroTenantId, invoices, SUMMARIZE_ERRORS, UNITDP);
            List<Invoice> createdItems = createdInvoices.getInvoices();

            log.info("Invoices created: {}", createdItems.size());
        } catch (Exception e) {
            log.error("Failed to create invoices! {}", getExceptionDetails(e));
            throw e;
        }
    }

    protected String getAccessToken() throws IOException, JwkException {
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

    protected String getExceptionDetails(Exception e) {
        return Objects.nonNull(e) ? e.getClass() + ":" + e : null;
    }

    protected Contact asContact(CrmContact crmContact) {
        if (crmContact == null) {
            return null;
        }
        Contact contact = new Contact();
        contact.setFirstName(crmContact.firstName);
        contact.setLastName(crmContact.lastName);
        contact.setName(crmContact.fullName());
        contact.setEmailAddress(crmContact.email);
        if (!Strings.isNullOrEmpty(crmContact.address.street)) {
            Address address = new Address()
                .addressLine1(crmContact.address.street)
                .city(crmContact.address.street)
                .region(crmContact.address.state)
                .postalCode(crmContact.address.postalCode)
                .country(crmContact.address.country)
            ;
            contact.setAddresses(List.of(address));
        }

        // TODO: make this part not-sfdc specific?
        // TODO: SUPPORTER_ID_FIELD_NAME is DR specific
        if (crmContact.rawObject instanceof SObject sObject) {
            String supporterId = (String) sObject.getField(SUPPORTER_ID_FIELD_NAME);
            contact.setAccountNumber(supporterId);
        }

        // TODO: temp
        log.info("contact {} {} {} {} {} {}", crmContact.id, contact.getFirstName(), contact.getLastName(), contact.getName(), contact.getEmailAddress(), contact.getAccountNumber());

        return contact;
    }

    protected List<LineItem> getLineItems(AccountingTransaction accountingTransaction) {
        LineItem lineItem = new LineItem();
        lineItem.setDescription(accountingTransaction.description);
        lineItem.setQuantity(1.0);
        lineItem.setUnitAmount(accountingTransaction.amountInDollars);
        // TODO: DR TEST (https://developer.xero.com/documentation/api/accounting/types/#tax-rates -- country specific)
        lineItem.setTaxType("EXEMPTOUTPUT");

        // TODO: DR TEST -- need to be able to override with code
        if (accountingTransaction.recurring) {
            lineItem.setAccountCode("122");
            lineItem.setItemCode("Partner");
        } else {
            lineItem.setAccountCode("116");
            lineItem.setItemCode("Donate");
        }

        return Collections.singletonList(lineItem);
    }

    protected LocalDateTime asLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofEpochSecond(date.toInstant().getEpochSecond(), 0, ZoneOffset.UTC);
    }

    protected Invoice asInvoice(AccountingTransaction transaction) {
        Invoice invoice = new Invoice();

        Calendar transactionDate = transaction.date;
        LocalDate localDate = LocalDate.of(
                transactionDate.get(Calendar.YEAR),
                transactionDate.get(Calendar.MONTH) + 1, // (valid values 1 - 12)
                transactionDate.get(Calendar.DATE)
        );
        invoice.setDate(localDate);
        invoice.setDueDate(localDate);
        Contact contact = new Contact();
        contact.setContactID(UUID.fromString(transaction.contactId));
        invoice.setContact(contact);

        invoice.setLineItems(getLineItems(transaction));
        invoice.setType(Invoice.TypeEnum.ACCREC); // Receive

        invoice.setReference(transaction.paymentGatewayName + ":" + transaction.paymentGatewayTransactionId);
        // TODO: temporarily leaving them in DRAFT for DR to review
//        invoice.setStatus(Invoice.StatusEnum.AUTHORISED);

        return invoice;
    }
}
