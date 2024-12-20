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
import com.google.common.collect.Lists;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.entity.Organization;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.sforce.soap.partner.sobject.SObject;
import com.xero.api.ApiClient;
import com.xero.api.XeroMinuteRateLimitException;
import com.xero.api.client.AccountingApi;
import com.xero.models.accounting.Address;
import com.xero.models.accounting.BankTransaction;
import com.xero.models.accounting.BankTransactions;
import com.xero.models.accounting.Contact;
import com.xero.models.accounting.ContactPerson;
import com.xero.models.accounting.Contacts;
import com.xero.models.accounting.Invoice;
import com.xero.models.accounting.Invoices;
import com.xero.models.accounting.LineItem;
import com.xero.models.accounting.Phone;
import org.json.JSONObject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

public class XeroAccountingPlatformService implements AccountingPlatformService {

    // TODO: these are custom to DR, move to dr-hub extension
    protected static final String SUPPORTER_ID_FIELD_NAME = "Supporter_ID__c";
    protected static final String ACCOUNT_ID_FIELD_NAME = "Account_ID__c";
    // If false return 200 OK and mix of successfully created objects and any with validation errors
    protected static final Boolean SUMMARIZE_ERRORS = Boolean.FALSE;
    // e.g. unitdp=4 – (Unit Decimal Places) You can opt in to use four decimal places for unit amounts
    protected static final Integer UNITDP = 4;

    protected static final Integer RATE_LIMIT_EXCEPTION_TIMEOUT_SECONDS = 61;
    protected static final Integer RATE_LIMIT_MAX_RETRIES = 3;

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
    public List<String> updateOrCreateContacts(List<CrmContact> crmContacts) throws Exception {
        Contacts contacts = new Contacts();
        contacts.setContacts(crmContacts.stream().map(this::toContact).toList());
        try {
            Contacts upsertedContacts = callWithRetries(() -> xeroApi.updateOrCreateContacts(getAccessToken(), xeroTenantId, contacts, SUMMARIZE_ERRORS));
            int index = 0;
            List<Contact> contactsToRetry = new ArrayList<>();
            List<String> processedIds = new ArrayList<>();

            for (Contact upserted : upsertedContacts.getContacts()) {
                if (Boolean.TRUE == upserted.getHasValidationErrors()) {
                    Contact contact = contacts.getContacts().get(index);

                    if (upserted.getValidationErrors().stream()
                        .anyMatch(error -> error.getMessage().contains("Account Number already exists"))) {

                        Optional<Contact> existingContact = getContactForAccountNumber(contact.getAccountNumber(), true);
                        if (existingContact.isPresent()) {
                            contact.setContactID(existingContact.get().getContactID());
                            contactsToRetry.add(contact);

                            env.logJobInfo("updating " + contact.getName() + " " + contact.getEmailAddress() + " " + contact.getContactID());
                        } else {
                            // Should be unreachable
                            env.logJobWarn("failed to get contact for account number {}!", contact.getAccountNumber());
                        }
                    } else {
                        env.logJobWarn("failed to upsert contact {}! error message(s): {}",
                            contact.getName(), upserted.getValidationErrors().stream().map(error -> error.getMessage()).collect(Collectors.joining(",")));
                    }
                } else {
                    processedIds.add(upserted.getContactID().toString());

                    env.logJobInfo("upserting " + upserted.getName() + " " + upserted.getEmailAddress() + " " + upserted.getContactID());
                }
                index++;
            }

            if (!contactsToRetry.isEmpty()) {
                contacts.setContacts(contactsToRetry);
                upsertedContacts = callWithRetries(() -> xeroApi.updateOrCreateContacts(getAccessToken(), xeroTenantId, contacts, SUMMARIZE_ERRORS));
                processedIds.addAll(upsertedContacts.getContacts().stream().map(c -> c.getContactID().toString()).toList());
            }
            return processedIds;
        } catch (Exception e) {
            env.logJobError("Failed to upsert contacts! {}", e);
            return Collections.emptyList();
        }
    }

    protected List<Contact> getContacts(String where, boolean includeArchived) throws Exception {
        Contacts contacts = callWithRetries(() -> xeroApi.getContacts(getAccessToken(), xeroTenantId,
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
            includeArchived,
//            Boolean summaryOnly
            true
        ));
        return contacts.getContacts();
    }

    public List<Contact> getContactsForAccountNumbers(List<String> accountNumbers, boolean includeArchived) throws Exception {
        String conditions = accountNumbers.stream().map(s -> "AccountNumber=\"" + s + "\"").collect(Collectors.joining(" OR "));
        return getContacts(conditions, includeArchived);
    }

    public Optional<Contact> getContactForAccountNumber(String accountNumber, boolean includeArchived) throws Exception {
        return getContacts("AccountNumber=\"" + accountNumber + "\"", includeArchived).stream().findFirst();
    }

    protected <T> T callWithRetries(Callable<T> callable) throws Exception {
        return callWithRetries(callable, RATE_LIMIT_MAX_RETRIES);
    }

    protected <T> T callWithRetries(Callable<T> callable, int maxRetries) throws Exception {
        for (int i = 0; i <= maxRetries; i++) {
            try {
                return callable.call();
            } catch (XeroMinuteRateLimitException e) {
                env.logJobWarn("API rate limit exceeded. Trying again after " + RATE_LIMIT_EXCEPTION_TIMEOUT_SECONDS + " seconds...");
                Thread.sleep(RATE_LIMIT_EXCEPTION_TIMEOUT_SECONDS * 1000);
            }
        }
        // Should be unreachable
        env.logJobWarn("Failed to get API response after {} tries!", maxRetries);
        return null;
    }

    @Override
    public List<String> updateOrCreateTransactions(List<CrmDonation> crmDonations) throws Exception {
        // Get existing invoices for crmDonations by date
        List<ZonedDateTime> donationDates = crmDonations.stream().map(ac -> ac.closeDate).toList();
        ZonedDateTime minDate = Collections.min(donationDates);
        List<Invoice> existingInvoices = getInvoices(minDate);
        // shouldn't be collisions moving forward, but there are old references like "RD-PayWay"
        Map<String, Invoice> invoicesByReference = existingInvoices.stream()
            .collect(Collectors.toMap(Invoice::getReference, invoice -> invoice, (i1, i2) -> i1));

        // TODO: Due to the rate limit risks, we break the list into chunks. That
        //  way if we run into the daily limit, we've at least inserted what we could.
        List<List<CrmDonation>> crmDonationBatches = Lists.partition(crmDonations, 50);
        List<String> createdInvoiceIds = new ArrayList<>();

        for (List<CrmDonation> crmDonationBatch : crmDonationBatches) {
            List<Invoice> invoices = new ArrayList<>();

            List<String> accountNumbers = crmDonationBatch.stream().map(d -> getAccountNumber(d.contact, d.account)).toList();
            Map<String, Contact> contactsByAccountNumbers = getContactsForAccountNumbers(accountNumbers, true).stream()
                .collect(Collectors.toMap(Contact::getAccountNumber, c -> c));

            for (CrmDonation crmDonation : crmDonationBatch) {
                Invoice existingInvoice = invoicesByReference.get(getReference(crmDonation));
                if (existingInvoice != null) {
                    // TODO: For now, avoiding updates of invoices, instead opting to skip them.
                    //  Concerned about update something that's already been reconciled.
//                    invoice.setInvoiceID(existingInvoice.getInvoiceID());

                    env.logJobInfo("skipping donation {}; found existing invoice {} {}", crmDonation.id, existingInvoice.getReference(), existingInvoice.getInvoiceID());
                    continue;
                }

                String accountNumber = getAccountNumber(crmDonation.contact, crmDonation.account);
                Contact contact = contactsByAccountNumbers.get(accountNumber);
                if (contact == null) {
                    env.logJobInfo("skipping donation {}; unable to find contact {}", crmDonation.id, accountNumber);
                    continue;
                }

                env.logJobInfo("donation invoice {} will be inserted on contact {}", crmDonation.id, accountNumber);
                Invoice invoice = toInvoice(crmDonation, contact.getContactID());
                invoices.add(invoice);
            }

            if (!invoices.isEmpty()) {
                env.logJobInfo("inserting {} invoices", invoices.size());
                Invoices invoicesPost = new Invoices();
                invoicesPost.setInvoices(invoices);
                Invoices createdInvoices = callWithRetries(() -> xeroApi.updateOrCreateInvoices(getAccessToken(), xeroTenantId, invoicesPost, SUMMARIZE_ERRORS, UNITDP));
                createdInvoices.getInvoices().stream().filter(i -> !i.getValidationErrors().isEmpty())
                    .forEach(i -> env.logJobWarn(i.getReference() + " failed to insert: " + i));
                createdInvoices.getInvoices().stream().filter(i -> i.getValidationErrors().isEmpty())
                    .forEach(i -> createdInvoiceIds.add(i.getInvoiceID().toString()));
            }
        }

        return createdInvoiceIds;
    }

    public List<Invoice> getInvoices(ZonedDateTime updatedAfter) throws Exception {
        return getInvoices(toUpdatedAfterClause(updatedAfter));
    }

    public List<Invoice> getInvoices(String where) throws Exception {
        int page = 1;
        boolean anotherPage = true;
        List<Invoice> allInvoices = new ArrayList<>();
        while (anotherPage) {
            final int _page = page;
            Invoices invoices = callWithRetries(() -> xeroApi.getInvoices(getAccessToken(), xeroTenantId,
                // OffsetDateTime ifModifiedSince
                null,
                where,
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
                _page,
                //Boolean includeArchived,
                false,
                //Boolean createdByMyApp,
                null,
                // Integer unitdp
                null,
                // Boolean summaryOnly
                false //The supplied filter (where) is unavailable on this endpoint when summaryOnly=true
            ));
            List<Invoice> pageInvoices = invoices.getInvoices();
            allInvoices.addAll(pageInvoices);
            if (pageInvoices.size() < 100) {
                anotherPage = false;
            }
            page++;
        }
        return allInvoices;
    }

    public List<BankTransaction> getBankTransactions(ZonedDateTime updatedAfter) throws Exception {
        return getBankTransactions(toUpdatedAfterClause(updatedAfter));
    }

    public List<BankTransaction> getBankTransactions(String where) throws Exception {
        int page = 1;
        boolean anotherPage = true;
        List<BankTransaction> allTransactions = new ArrayList<>();
        while (anotherPage) {
            final int _page = page;
            BankTransactions bankTransactions = callWithRetries(() -> xeroApi.getBankTransactions(
                getAccessToken(), xeroTenantId, null, where, null, _page, null));
            List<BankTransaction> pageBankTransactions = bankTransactions.getBankTransactions();
            allTransactions.addAll(pageBankTransactions);
            if (pageBankTransactions.size() < 100) {
                anotherPage = false;
            }
            page++;
        }
        return allTransactions;
    }

    protected String toUpdatedAfterClause(ZonedDateTime zonedDateTime) {
        int year = zonedDateTime.getYear();
        int month = zonedDateTime.getMonthValue();
        int day = zonedDateTime.getDayOfMonth();
        return "Date >= " + "DateTime(" + year + ", " + month + ", " + day + ")";
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

        String accountNumber = getAccountNumber(crmContact, crmContact.account);
        contact.setAccountNumber(accountNumber);

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
            contact.setName(crmContact.getFullName() + " " + accountNumber);
            contact.setFirstName(crmContact.firstName);
            contact.setLastName(crmContact.lastName);
        } else {
            // Organization
            //TODO: Three different record types to include: AU ORGANISATION, AU CHURCH, AU SCHOOL?
            contact.setName(crmContact.account.name + " " + accountNumber);
            if (!Strings.isNullOrEmpty(crmContact.email)) {
                ContactPerson primaryContactPerson = new ContactPerson();
                primaryContactPerson.setFirstName(crmContact.firstName);
                primaryContactPerson.setLastName(crmContact.lastName);
                primaryContactPerson.setEmailAddress(contact.getEmailAddress());
                contact.setContactPersons(List.of(primaryContactPerson));
            }
        }

        return contact;
    }

    // TODO: move to dr-hub, shouldn't be SFDC specific
    protected String getAccountNumber(CrmContact crmContact, CrmAccount crmAccount) {
        String supporterId = crmContact.crmRawObject instanceof SObject sObject ?
            (String) sObject.getField(SUPPORTER_ID_FIELD_NAME) : null;
        String accountId = crmAccount.crmRawObject instanceof SObject sObject ?
            (String) sObject.getField(ACCOUNT_ID_FIELD_NAME) : null;
        return crmAccount.recordType == EnvironmentConfig.AccountType.HOUSEHOLD ?
            supporterId : accountId;
    }

    protected Address toAddress(CrmAddress crmAddress) {
        if (crmAddress == null) {
            return null;
        }
        return new Address()
            .addressType(Address.AddressTypeEnum.STREET)
            .addressLine1(crmAddress.street)
            .city(crmAddress.city)
            .region(crmAddress.state)
            .postalCode(crmAddress.postalCode)
            .country(crmAddress.country);
    }

    protected Invoice toInvoice(CrmDonation crmDonation, UUID contactId) {
        Invoice invoice = new Invoice();

        org.threeten.bp.ZonedDateTime threetenTransactionDate = org.threeten.bp.ZonedDateTime.ofInstant(
            org.threeten.bp.Instant.ofEpochSecond(crmDonation.closeDate.toEpochSecond()),
            org.threeten.bp.ZoneId.of(crmDonation.closeDate.getZone().getId())
        );
        org.threeten.bp.LocalDate threetenLocalDate = threetenTransactionDate.toLocalDate();
        invoice.setDate(threetenLocalDate);
        invoice.setDueDate(threetenLocalDate);
        Contact contact = new Contact();
        contact.setContactID(contactId);
        invoice.setContact(contact);

        invoice.setLineItems(getLineItems(crmDonation));
        invoice.setType(Invoice.TypeEnum.ACCREC); // Receive

        invoice.setReference(getReference(crmDonation));
        invoice.setStatus(Invoice.StatusEnum.AUTHORISED);

        return invoice;
    }

    protected List<LineItem> getLineItems(CrmDonation crmDonation) {
        LineItem lineItem = new LineItem();
        // description is required, so fall back on name if needed
        if (!Strings.isNullOrEmpty(crmDonation.description)) {
            lineItem.setDescription(crmDonation.description);
        } else {
            lineItem.setDescription(crmDonation.name);
        }
        lineItem.setQuantity(1.0);
        lineItem.setUnitAmount(crmDonation.amount);
        // TODO: DR TEST (https://developer.xero.com/documentation/api/accounting/types/#tax-rates -- country specific)
        lineItem.setTaxType("EXEMPTOUTPUT");

        // TODO: DR TEST -- need to be able to override with code
        if (crmDonation.transactionType == EnvironmentConfig.TransactionType.TICKET) {
            lineItem.setAccountCode("160");
        } else if (crmDonation.isRecurring()) {
            lineItem.setAccountCode("122");
        } else {
            lineItem.setAccountCode("116");
        }
        return Collections.singletonList(lineItem);
    }

    protected String getReference(CrmDonation crmDonation) {
        String reference;
        if (!Strings.isNullOrEmpty(crmDonation.transactionId)) {
            reference = crmDonation.transactionId;
        } else {
            reference = crmDonation.id;
        }

        if (!Strings.isNullOrEmpty(crmDonation.gatewayName)) {
            reference = crmDonation.gatewayName + ":" + reference;
        }

        return reference;
    }
}
