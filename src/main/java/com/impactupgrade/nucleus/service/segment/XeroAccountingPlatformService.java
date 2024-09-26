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
import com.impactupgrade.nucleus.model.AccountingContact;
import com.impactupgrade.nucleus.model.AccountingTransaction;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class XeroAccountingPlatformService implements AccountingPlatformService {

    protected static final String SUPPORTER_ID_FIELD_NAME = "Supporter_ID__c";
    protected static final String WPG_FIELD_NAME = "WPG__c";
    protected static final String TAX_DEDUCTIBLE_GIFT_FIELD_NAME = "Tax_Deductible_Gift__c";
    protected static final String OTHER_INCOME_FIELD_NAME = "Other_Income__c";
    // If false return 200 OK and mix of successfully created objects and any with validation errors
    protected static final Boolean SUMMARIZE_ERRORS = Boolean.FALSE;
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

    @Override
    public Optional<AccountingContact> getContact(CrmContact crmContact) throws Exception {
        Optional<Contact> contact = getContactForAccountNumber(getAccountNumber(crmContact));
        return contact
            .map(c -> new AccountingContact(c.getContactID().toString(), crmContact.id))
            .or(Optional::empty);
    }

    protected String getReference(CrmDonation crmDonation) {
        return (crmDonation.gatewayName + ":" + crmDonation.transactionId);
    }

    @Override
    public List<String> updateOrCreateContacts(List<CrmContact> crmContacts) throws Exception {
        Contacts contacts = new Contacts();
        contacts.setContacts(crmContacts.stream().map(this::toContact).toList());

        try {
            Contacts upsertedContacts = xeroApi.updateOrCreateContacts(getAccessToken(), xeroTenantId, contacts, SUMMARIZE_ERRORS);
            return upsertedContacts.getContacts().stream().map(c -> c.getContactID().toString()).toList();
        } catch (XeroBadRequestException e) {
            // TODO: upsert appears to require the actual contact ID in order to update. Since we're only providing
            //   the accountNumber, updating fails. However, the error gives us the contactID we need...
            List<Contact> existingContacts = new ArrayList<>();
            for (Element element : e.getElements()) {
                Contact contact = new Contact();
                if (element.getValidationErrors().stream().anyMatch(error -> error.getMessage().contains("Account Number already exists"))) {
                    contact.setContactID(element.getContactID());
                    //TODO: get account number from error
                }
                if (element.getValidationErrors().stream().anyMatch(error -> error.getMessage().contains("contact name must be unique across all active contacts"))) {
                    contact.setContactID(element.getContactID());
                    //TODO: get account number from error
                }
                existingContacts.add(contact);
            }

            Map<String, Contact> contactsByAccountNumber = existingContacts.stream()
                .collect(Collectors.toMap(c -> c.getAccountNumber(), c -> c));

            List<Contact> contactsToRetry = contacts.getContacts().stream()
                    .filter(c -> contactsByAccountNumber.containsKey(c.getAccountNumber()))
                    .map(c -> {
                        Contact existing = contactsByAccountNumber.get(c.getAccountNumber());
                        c.setContactID(existing.getContactID());
                        return c;
                    }).toList();

            Contacts toRetry = new Contacts();
            toRetry.setContacts(contactsToRetry);
            Contacts retriedContacts = xeroApi.updateOrCreateContacts(getAccessToken(), xeroTenantId, toRetry, false);
            //TODO: here we only return ids of retried items not all the contacts we had initially (!)
            return retriedContacts.getContacts().stream().map(c -> c.getContactID().toString()).toList();
        } catch (Exception e) {
            env.logJobError("Failed to upsert contact! {}", e);
            return null;
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

    public Optional<Contact> getContactForName(String name) throws Exception {
        return getContact("Name=\"" + name + "\"");
    }

    public Optional<Contact> getContactForAccountNumber(String accountNumber) throws Exception {
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
            env.logJobError("Failed to create invoices! {}", e);
            throw e;
        }
    }

    @Override
    public List<String> updateOrCreateTransactions(List<AccountingTransaction> accountingTransactions) throws Exception {
        Invoices invoices = new Invoices();
        invoices.setInvoices(accountingTransactions.stream().map(this::toInvoice).toList());
        Invoices createdInvoices = xeroApi.updateOrCreateInvoices(getAccessToken(), xeroTenantId, invoices, SUMMARIZE_ERRORS, UNITDP);
        return createdInvoices.getInvoices().stream().map(invoice -> invoice.getInvoiceID().toString()).toList();
    }

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

    protected Contact toContact(CrmContact crmContact) {
        Contact contact = new Contact();
        contact.setAccountNumber(getAccountNumber(crmContact));
        contact.setEmailAddress(crmContact.email);

        contact.setPhones(new ArrayList<>());
        Phone mobilePhone = new Phone();
        mobilePhone.setPhoneType(Phone.PhoneTypeEnum.MOBILE);
        mobilePhone.setPhoneNumber(crmContact.mobilePhone);
        //TODO: area/country codes?
        contact.getPhones().add(mobilePhone);
        if (!Strings.isNullOrEmpty(crmContact.workPhone)) {
            Phone workPhone = new Phone();
            workPhone.setPhoneType(Phone.PhoneTypeEnum.OFFICE);
            workPhone.setPhoneNumber(crmContact.workPhone);
            //TODO: area/country codes?
            contact.getPhones().add(workPhone);
        }

        if (crmContact.account.billingAddress != null) {
            contact.setAddresses(List.of(toAddress(crmContact.account.billingAddress)));
        }

        if (crmContact.account.recordType == EnvironmentConfig.AccountType.HOUSEHOLD) {
            // Household
            contact.setName(crmContact.getFullName() + " " + getAccountNumber(crmContact));
            contact.setFirstName(crmContact.firstName);
            contact.setLastName(crmContact.lastName);
        } else {
            // Organization
            //TODO: Three different record types to include: AU ORGANISATION, AU CHURCH, AU SCHOOL?
            contact.setName(crmContact.account.name + " " + getAccountNumber(crmContact));
            ContactPerson primaryContactPerson = new ContactPerson();
            primaryContactPerson.setFirstName(crmContact.firstName);
            primaryContactPerson.setLastName(crmContact.lastName);
            contact.setContactPersons(List.of(primaryContactPerson));
        }

        // TODO: temp
        env.logJobInfo("contact {} {} {} {} {} {}", crmContact.id, contact.getFirstName(), contact.getLastName(), contact.getName(), contact.getEmailAddress(), contact.getAccountNumber());

        return contact;
    }

    protected String getAccountNumber(CrmContact crmContact) {
        String supporterId = crmContact.crmRawObject instanceof SObject sObject ?
            (String) sObject.getField(SUPPORTER_ID_FIELD_NAME) : null;
        return crmContact.account.recordType == EnvironmentConfig.AccountType.HOUSEHOLD ?
            supporterId : crmContact.account.id;
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

            if ("true".equalsIgnoreCase(getCustomDonationField(accountingTransaction, OTHER_INCOME_FIELD_NAME))) {
                lineItem.setAccountCode("260");
                lineItem.setItemCode("Other Income");
            }

        } else if (accountingTransaction.recurring) {
            lineItem.setAccountCode("122");
            lineItem.setItemCode("Partner");

            if ("true".equalsIgnoreCase(getCustomDonationField(accountingTransaction, WPG_FIELD_NAME))) {
                lineItem.setAccountCode("120");
                lineItem.setItemCode("RecurringWPG");
            }

            //TODO: complete this part once COA Codes list is defined
//            if (!Strings.isNullOrEmpty(getCustomDonationField(accountingTransaction, "Country Designation"))) {
//                lineItem.setAccountCode("country_account_code"); //?
//                lineItem.setItemCode("country_item_code");
//            }

        } else {
            if ("true".equalsIgnoreCase(getCustomDonationField(accountingTransaction, TAX_DEDUCTIBLE_GIFT_FIELD_NAME))) {
                lineItem.setAccountCode("116");
                lineItem.setItemCode("Donate");
            }
            if ("true".equalsIgnoreCase(getCustomDonationField(accountingTransaction, WPG_FIELD_NAME))) {
                //TODO: create Receive Money?
                lineItem.setAccountCode("116");
                lineItem.setItemCode("Donate");
            }
        }

        return Collections.singletonList(lineItem);
    }

    protected String getReference(AccountingTransaction accountingTransaction) {
        return accountingTransaction.paymentGatewayName + ":" + accountingTransaction.paymentGatewayTransactionId;
    }

    protected String getCustomDonationField(AccountingTransaction accountingTransaction, String fieldName) {
        return accountingTransaction.crmDonation.crmRawObject instanceof SObject sObject ?
            (String) sObject.getField(fieldName) : null;
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
