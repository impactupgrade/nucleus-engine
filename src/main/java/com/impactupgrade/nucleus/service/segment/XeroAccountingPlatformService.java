/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

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
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.entity.Organization;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.AccountingTransaction;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.service.logic.NotificationService;
import com.sforce.soap.partner.sobject.SObject;
import com.xero.api.ApiClient;
import com.xero.api.XeroBadRequestException;
import com.xero.api.client.AccountingApi;
import com.xero.models.accounting.Address;
import com.xero.models.accounting.Contact;
import com.xero.models.accounting.ContactPerson;
import com.xero.models.accounting.Contacts;
import com.xero.models.accounting.Element;
import com.xero.models.accounting.Invoice;
import com.xero.models.accounting.Invoices;
import com.xero.models.accounting.LineItem;
import com.xero.models.accounting.Phone;
import org.json.JSONObject;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class XeroAccountingPlatformService implements AccountingPlatformService {

    protected static final String SUPPORTER_ID_FIELD_NAME = "Supporter_ID__c";
    // If false return 200 OK and mix of successfully created objects and any with validation errors
    protected static final Boolean SUMMARIZE_ERRORS = Boolean.TRUE;
    // e.g. unitdp=4 – (Unit Decimal Places) You can opt in to use four decimal places for unit amounts
    protected static final Integer UNITDP = 4;

    protected final ApiClient apiClient;
    protected final AccountingApi xeroApi;

    protected Environment env;

    protected String clientId;
    protected String clientSecret;
    protected String tokenServerUrl;
    protected String xeroTenantId;

    protected HibernateDao<Long, Organization> organizationDao;

    protected static String accessToken;
    protected static String refreshToken;

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
        return !Strings.isNullOrEmpty(env.getConfig().xero.clientId);
    }

    @Override
    public void init(Environment env) {
        this.env = env;
        this.clientId = env.getConfig().xero.clientId;
        this.clientSecret = env.getConfig().xero.clientSecret;
        this.tokenServerUrl = env.getConfig().xero.tokenServerUrl;
        this.xeroTenantId = env.getConfig().xero.tenantId;
        this.organizationDao = new HibernateDao<>(Organization.class);

        if (accessToken == null) {
            Organization org = getOrganization();
            JSONObject envJson = org.getEnvironmentJson();
            JSONObject xeroJson = envJson.getJSONObject("xero");
            accessToken = xeroJson.getString("accessToken");
            refreshToken = xeroJson.getString("refreshToken");
        }
    }

    protected Organization getOrganization() {
        return organizationDao.getQueryResult(
            "from Organization o where o.nucleusApiKey=:apiKey",
            query -> {
                query.setParameter("apiKey", env.getConfig().apiKey);
            }
        ).get();
    }

    @Override
    public Optional<AccountingTransaction> getTransaction(CrmDonation crmDonation) throws Exception {
        Invoices invoicesResponse = xeroApi.getInvoices(getAccessToken(), xeroTenantId,
                // OffsetDateTime ifModifiedSince
                null,
                //String where,
                "Reference==\"" + getReference(crmDonation) + "\"",
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
                null,
                //Boolean includeArchived,
                false,
                //Boolean createdByMyApp,
                null,
                // Integer unitdp
                null,
                // Boolean summaryOnly
                false //The supplied filter (where) is unavailable on this endpoint when summaryOnly=true
        );
        if (!invoicesResponse.getInvoices().isEmpty()) {
            return Optional.of(toAccountingTransaction(invoicesResponse.getInvoices().get(0)));
        } else {
            return Optional.empty();
        }
    }

    protected String getReference(CrmDonation crmDonation) {
        return (crmDonation.gatewayName + ":" + crmDonation.transactionId);
    }

//    @Override
//    public String updateOrCreateContact(CrmContact crmContact) throws Exception {
//        Contact contact = toContact(crmContact);
//        Contacts contacts = new Contacts();
//        contacts.setContacts(List.of(contact));
//
//        try {
//            // This method works very similar to POST Contacts (xeroApi.updateOrCreateContacts)
//            // but if an existing contact matches our ContactName or ContactNumber then we will receive an error
//            Contacts createdContacts = xeroApi.createContacts(getAccessToken(), xeroTenantId, contacts, SUMMARIZE_ERRORS);
//            Contact upsertedContact = createdContacts.getContacts().stream().findFirst().get();
//            return upsertedContact.getContactID().toString();
//        } catch (XeroBadRequestException e) {
//            // TODO: upsert appears to require the actual contact ID in order to update. Since we're only providing
//            //   the accountNumber, updating fails. However, the error gives us the contactID we need...
//            for (Element element : e.getElements()) {
//                if (element.getValidationErrors().stream().anyMatch(error -> error.getMessage().contains("Account Number already exists"))) {
//                    // TODO: Same as toContact -- DR specific, SFDC specific, etc.
//                    if (crmContact.crmRawObject instanceof SObject sObject) {
//                        String supporterId = (String) sObject.getField(SUPPORTER_ID_FIELD_NAME);
//                        return getContactForAccountNumber(supporterId).map(c -> c.getContactID().toString()).orElse(null);
//                    }
//                }
//                if (element.getValidationErrors().stream().anyMatch(error -> error.getMessage().contains("contact name must be unique across all active contacts"))) {
//                    // TODO: Same as toContact -- DR specific, SFDC specific, etc.
//                    if (crmContact.crmRawObject instanceof SObject sObject) {
//                        String supporterId = (String) sObject.getField(SUPPORTER_ID_FIELD_NAME);
//                        return getContactForName(contact.getName()).map(c -> {
//                            // A few contacts have been entered manually without an Account Number being set.
//                            // If that's the case, assume it's the correct person, without checking the supporterId.
//                            if (Strings.isNullOrEmpty(c.getAccountNumber()) || c.getAccountNumber().equals(supporterId)) {
//                                return c.getContactID().toString();
//                            } else {
//                                // Send notification if name already exists for different supporter id (account number)
//                                try {
//                                    env.logJobInfo("Sending notification for duplicated contact name '{}'...", crmContact.getFullName());
//                                    NotificationService.Notification notification = new NotificationService.Notification(
//                                        "Xero: Contact name already exists",
//                                        "Xero: Contact with name '" + crmContact.getFullName() + "' already exists. Supporter ID: " + supporterId + "."
//                                    );
//                                    env.notificationService().sendNotification(notification, "xero:contact-name-exists");
//                                } catch (Exception ex) {
//                                    env.logJobError("Failed to send notification! {}", getExceptionDetails(e));
//                                }
//                                return null;
//                            }
//                        }).orElse(null);
//                    }
//                }
//            }
//
//            env.logJobError("Failed to upsert contact! {}", getExceptionDetails(e));
//            return null;
//        } catch (Exception e) {
//            env.logJobError("Failed to upsert contact! {}", getExceptionDetails(e));
//            return null;
//        }
//    }

    @Override
    public List<String> updateOrCreateContacts(List<CrmContact> crmContacts) throws Exception {
        Contacts contacts = new Contacts();
        contacts.setContacts(crmContacts.stream().map(this::toContact).toList());

        try {
            Contacts upsertedContacts = xeroApi.updateOrCreateContacts(getAccessToken(), xeroTenantId, contacts, SUMMARIZE_ERRORS);
            return upsertedContacts.getContacts().stream().map(c -> c.getContactID().toString()).toList();
        } catch (Exception e) {
            //TODO: check errors
            return Collections.emptyList();
        }
    }

    protected Optional<Contact> getContact(String where) throws Exception {
        Contacts contacts = xeroApi.getContacts(getAccessToken(), xeroTenantId,
//            OffsetDateTime ifModifiedSince,
            null,
//            String where,
            where,
//            String order,
            null,
//            List<UUID> ids,
            null,
//            Integer page,
            null,
//            Boolean includeArchived,
            null,
//            Boolean summaryOnly
            true
        );
        return contacts.getContacts().stream().findFirst();
    }

    private Optional<Contact> getContactForName(String name) throws Exception {
        return getContact("Name=\"" + name + "\"");
    }

    private Optional<Contact> getContactForAccountNumber(String accountNumber) throws Exception {
        return getContact("AccountNumber=\"" + accountNumber + "\"");
    }

    @Override
    public String createTransaction(AccountingTransaction accountingTransaction) throws Exception {
        Invoices invoices = new Invoices();
        invoices.setInvoices(List.of(toInvoice(accountingTransaction)));
        try {
            Invoices createdInvoices = xeroApi.createInvoices(getAccessToken(), xeroTenantId, invoices, SUMMARIZE_ERRORS, UNITDP);
            return createdInvoices.getInvoices().stream().findFirst().get().getInvoiceID().toString();
        } catch (Exception e) {
            env.logJobError("Failed to create invoices! {}", getExceptionDetails(e));
            throw e;
        }
    }

//    @Override
//    public List<AccountingTransaction> getTransactions(Date startDate) throws Exception {
//        try {
//            List<Invoice> allInvoices = new ArrayList<>();
//            int page = 1;
//            int currentPageSize;
//
//            do {
//                List<Invoice> invoicesPage = getInvoices(startDate, page);
//                allInvoices.addAll(invoicesPage);
//                currentPageSize = invoicesPage.size();
//                page++;
//            } while (currentPageSize == 100);
//
//            return allInvoices.stream()
//                    .map(invoice -> getPaymentGatewayTransactionId(invoice))
//                    // Older transactions may not have the stripe: setup, so skip those entirely -- we have no way of pulling the ID from them.
//                    .filter(id -> !Strings.isNullOrEmpty(id))
//                    .map(paymentGatewayTransactionId -> new AccountingTransaction(
//                            null,
//                            null,
//                            null,
//                            null,
//                            null,
//                            paymentGatewayTransactionId,
//                            null,
//                            null
//                    ))
//                    .collect(Collectors.toList());
//        } catch (Exception e) {
//            env.logJobError("Failed to get existing transactions info! {}", getExceptionDetails(e));
//            // throw, since returning empty list here would be a bad idea -- likely implies reinserting duplicates
//            throw e;
//        }
//    }
//
//    protected List<Invoice> getInvoices(Date startDate, int page) throws Exception {
//        OffsetDateTime ifModifiedSince = OffsetDateTime.of(asLocalDateTime(startDate), ZoneOffset.UTC);
//        Invoices invoicesResponse = xeroApi.getInvoices(getAccessToken(), xeroTenantId,
//                // OffsetDateTime ifModifiedSince
//                ifModifiedSince,
//                //String where,
//                "Reference.StartsWith(\"Stripe\")",
//                // String order,
//                null,
//                // List<UUID> ids,
//                null,
//                //List<String> invoiceNumbers,
//                null,
//                //List<UUID> contactIDs,
//                null,
//                //List<String> statuses,
//                List.of(
//                        Invoice.StatusEnum.DRAFT.name(),
//                        Invoice.StatusEnum.SUBMITTED.name(),
//                        Invoice.StatusEnum.AUTHORISED.name(),
//                        Invoice.StatusEnum.PAID.name()
//                ),
//                //Integer page,
//                page,
//                //Boolean includeArchived,
//                false,
//                //Boolean createdByMyApp,
//                null,
//                // Integer unitdp
//                null,
//                // Boolean summaryOnly
//                false //The supplied filter (where) is unavailable on this endpoint when summaryOnly=true
//        );
//        return invoicesResponse.getInvoices();
//    }
//
//    protected LocalDateTime asLocalDateTime(Date date) {
//        if (date == null) {
//            return null;
//        }
//        return LocalDateTime.ofEpochSecond(date.toInstant().getEpochSecond(), 0, ZoneOffset.UTC);
//    }
//
//    @Override
//    public Map<String, String> updateOrCreateContacts(List<CrmContact> crmContacts) throws Exception {
//        if (CollectionUtils.isEmpty(crmContacts)) {
//            return Collections.emptyMap();
//        }
//        Contacts contacts = new Contacts();
//        contacts.setContacts(crmContacts.stream()
//                .map(crmContact -> toContact(crmContact))
//                .collect(Collectors.toList()));
//        try {
//            return xeroApi.updateOrCreateContacts(getAccessToken(), xeroTenantId, contacts, SUMMARIZE_ERRORS).getContacts().stream()
//                .collect(Collectors.toMap(
//                        // account number is set as the crm contact id
//                        Contact::getAccountNumber, contact -> contact.getContactID().toString()));
//        } catch (Exception e) {
//            env.logJobError("Failed to upsert contacts! {}", getExceptionDetails(e));
//            return Collections.emptyMap();
//        }
//    }
//
//    @Override
//    public void createTransactions(List<AccountingTransaction> transactions) throws Exception {
//        env.logJobInfo("Input transactions: {}", transactions.size());
//
//        Invoices invoices = new Invoices().invoices(transactions.stream().map(this::toInvoice).collect(Collectors.toList()));
//        env.logJobInfo("Invoices to create: {}", invoices.getInvoices().size());
//
//        try {
//            Invoices createdInvoices = xeroApi.createInvoices(getAccessToken(), xeroTenantId, invoices, SUMMARIZE_ERRORS, UNITDP);
//            List<Invoice> createdItems = createdInvoices.getInvoices();
//
//            env.logJobInfo("Invoices created: {}", createdItems.size());
//        } catch (Exception e) {
//            env.logJobError("Failed to create invoices! {}", getExceptionDetails(e));
//            throw e;
//        }
//    }

    protected String getAccessToken() throws Exception {
        DecodedJWT jwt = null;
        try {
            jwt = JWT.decode(accessToken);
        } catch (Exception e) {
            env.logJobWarn("Failed to decode access token! {}", e.getMessage());
        }

        long now = System.currentTimeMillis();
        if (jwt == null || jwt.getExpiresAt().getTime() < now) {
            env.logJobInfo("token expired; jwt={} now={}; refreshing...", jwt.getExpiresAt().getTime(), now);

            try {
                TokenResponse tokenResponse = new RefreshTokenRequest(new NetHttpTransport(), new JacksonFactory(),
                        new GenericUrl(tokenServerUrl), refreshToken)
                        .setClientAuthentication(new BasicAuthentication(this.clientId, this.clientSecret))
                        .execute();

                try {
                    DecodedJWT verifiedJWT = apiClient.verify(tokenResponse.getAccessToken());
                    accessToken = verifiedJWT.getToken();
                } catch (Exception e) {
                    env.logJobWarn("unable to validate the new access token; using it anyway...; error={}", e.getMessage());
                    accessToken = tokenResponse.getAccessToken();
                }
                refreshToken = tokenResponse.getRefreshToken();

                // TODO: not safe to have these in the logs, but allowing it for a moment while we debug
                env.logJobInfo("tokens refreshed; accessToken={} refreshToken={}", accessToken, refreshToken);

                Organization org = getOrganization();
                JSONObject envJson = org.getEnvironmentJson();
                JSONObject xeroJson = envJson.getJSONObject("xero");
                xeroJson.put("accessToken", accessToken);
                xeroJson.put("refreshToken", refreshToken);
                org.setEnvironmentJson(envJson);
                organizationDao.update(org);
            } catch (Exception e) {
                env.logJobError("Failed to refresh access token!", e);
                if (e instanceof TokenResponseException) {
                    TokenErrorResponse tokenErrorResponse = ((TokenResponseException) e).getDetails();
                    if (tokenErrorResponse != null) {
                        env.logJobWarn("error={} errorDescription={} errorUri={}", tokenErrorResponse.getError(),
                            tokenErrorResponse.getErrorDescription(), tokenErrorResponse.getErrorUri());
                    }
                }
                throw e;
            }
        }
        return accessToken;
    }

    protected String getExceptionDetails(Exception e) {
        return e == null ? null : e.getClass() + ":" + e;
    }

    // Mappings
//    protected Contact toContact(CrmContact crmContact) {
//        if (crmContact == null) {
//            return null;
//        }
//        Contact contact = new Contact();
//        contact.setFirstName(crmContact.firstName);
//        contact.setLastName(crmContact.lastName);
//        contact.setName(crmContact.getFullName());
//        contact.setEmailAddress(crmContact.email);
//        if (!Strings.isNullOrEmpty(crmContact.mailingAddress.street)) {
//            Address address = new Address()
//                    .addressLine1(crmContact.mailingAddress.street)
//                    .city(crmContact.mailingAddress.city)
//                    .region(crmContact.mailingAddress.state)
//                    .postalCode(crmContact.mailingAddress.postalCode)
//                    .country(crmContact.mailingAddress.country);
//            contact.setAddresses(List.of(address));
//        }
//
//        // TODO: make this part not-sfdc specific?
//        // TODO: SUPPORTER_ID_FIELD_NAME is DR specific
//        if (crmContact.crmRawObject instanceof SObject sObject) {
//            String supporterId = (String) sObject.getField(SUPPORTER_ID_FIELD_NAME);
//            contact.setAccountNumber(supporterId);
//        }
//
//        // TODO: temp
//        env.logJobInfo("contact {} {} {} {} {} {}", crmContact.id, contact.getFirstName(), contact.getLastName(), contact.getName(), contact.getEmailAddress(), contact.getAccountNumber());
//
//        return contact;
//    }

    protected Contact toContact(CrmContact crmContact) {
        if (crmContact == null) {
            return null;
        }

        String supporterId;
        if (crmContact.crmRawObject instanceof SObject sObject) {
            supporterId = (String) sObject.getField(SUPPORTER_ID_FIELD_NAME);
        } else {
            //Should be unreachable
            supporterId = crmContact.account.id;
        }

        Contact contact = new Contact();
        contact.accountNumber(supporterId);
        Phone mobilePhone = new Phone();
        mobilePhone.setPhoneType(Phone.PhoneTypeEnum.MOBILE);
        mobilePhone.setPhoneNumber(crmContact.mobilePhone);
        //TODO: area/country codes?
        contact.setPhones(List.of(mobilePhone)); //TODO: add home/work?
        contact.setEmailAddress(crmContact.email);
        if (crmContact.account.billingAddress != null) {
            contact.setAddresses(List.of(toAddress(crmContact.account.billingAddress)));
        }

        if (crmContact.account.recordType == EnvironmentConfig.AccountType.HOUSEHOLD) {
            // Household
            contact.setName(crmContact.getFullName() + " " + supporterId);
            contact.setFirstName(crmContact.firstName);
            contact.setLastName(crmContact.lastName);
        } else {
            // Organization
            //TODO: Three different record types to include: AU ORGANISATION, AU CHURCH, AU SCHOOL?
            contact.setName(crmContact.account.name + " " + supporterId);
            ContactPerson primaryContactPerson = new ContactPerson();
            primaryContactPerson.setFirstName(crmContact.firstName);
            primaryContactPerson.setLastName(crmContact.lastName);
            contact.setContactPersons(List.of(primaryContactPerson));
        }

        // TODO: temp
        env.logJobInfo("contact {} {} {} {} {} {}", crmContact.id, contact.getFirstName(), contact.getLastName(), contact.getName(), contact.getEmailAddress(), contact.getAccountNumber());

        return contact;
    }

    protected Address toAddress(CrmAddress crmAddress) {
        if (crmAddress == null) {
            return null;
        }
        return new Address()
            .addressLine1(crmAddress.street)
            .city(crmAddress.city)
            .region(crmAddress.state)
            .postalCode(crmAddress.postalCode)
            .country(crmAddress.country);
    }

    protected Invoice toInvoice(AccountingTransaction transaction) {
        Invoice invoice = new Invoice();

        ZonedDateTime transactionDate = transaction.date;
        org.threeten.bp.ZonedDateTime threetenTransactionDate = org.threeten.bp.ZonedDateTime.ofInstant(
            org.threeten.bp.Instant.ofEpochSecond(transactionDate.toEpochSecond()),
            org.threeten.bp.ZoneId.of(transactionDate.getZone().getId())
        );
        org.threeten.bp.LocalDate threetenLocalDate = threetenTransactionDate.toLocalDate();
        invoice.setDate(threetenLocalDate);
        invoice.setDueDate(threetenLocalDate);
        Contact contact = new Contact();
        contact.setContactID(UUID.fromString(transaction.contactId));
        invoice.setContact(contact);

        invoice.setLineItems(getLineItems(transaction));
        invoice.setType(Invoice.TypeEnum.ACCREC); // Receive

        invoice.setReference(getReference(transaction));
        invoice.setStatus(Invoice.StatusEnum.AUTHORISED);

        return invoice;
    }

    protected List<LineItem> getLineItems(AccountingTransaction accountingTransaction) {
        LineItem lineItem = new LineItem();
        lineItem.setDescription(accountingTransaction.description);
        lineItem.setQuantity(1.0);
        lineItem.setUnitAmount(accountingTransaction.amountInDollars);
        // TODO: DR TEST (https://developer.xero.com/documentation/api/accounting/types/#tax-rates -- country specific)
        lineItem.setTaxType("EXEMPTOUTPUT");

        // TODO: DR TEST -- need to be able to override with code
        if (accountingTransaction.transactionType == EnvironmentConfig.TransactionType.TICKET) {
            lineItem.setAccountCode("160");
            lineItem.setItemCode("EI");
        } else if (accountingTransaction.recurring) {
            lineItem.setAccountCode("122");
            lineItem.setItemCode("Partner");
        } else {
            lineItem.setAccountCode("116");
            lineItem.setItemCode("Donate");
        }

        return Collections.singletonList(lineItem);
    }

    protected String getReference(AccountingTransaction accountingTransaction) {
        return accountingTransaction.paymentGatewayName + ":" + accountingTransaction.paymentGatewayTransactionId;
    }

    protected AccountingTransaction toAccountingTransaction(Invoice invoice) {
        return new AccountingTransaction(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                getPaymentGatewayTransactionId(invoice),
                null
        );
    }

    protected String getPaymentGatewayTransactionId(Invoice invoice) {
        // references are, ex, Stripe:ch______
        String reference = invoice.getReference().toLowerCase(Locale.ROOT);
        return reference.startsWith("stripe:") ? reference.replace("stripe:", "") : null;
    }
}
