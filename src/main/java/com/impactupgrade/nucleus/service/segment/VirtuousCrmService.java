package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.commons.collections.CollectionUtils;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.core.Response;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VirtuousCrmService implements BasicCrmService {

    private static final Logger log = LogManager.getLogger(VirtuousCrmService.class);

    private static final String VIRTUOUS_API_URL = "https://api.virtuoussoftware.com/api";
    //private static final String DATE_FORMAT = "MM/dd/yyyy HH:mm:ss z";
    private static final String DATE_FORMAT = "MM/dd/yyyy";
    private static final int DEFAULT_OFFSET = 0;
    private static final int DEFAULT_LIMIT = 100;

    private static TokenResponse tokenResponse;

    private String tokenServerUrl;
    private String username;
    private String password;
    private String accessToken;
    private String refreshToken;

    protected Environment env;

    @Override
    public String name() {
        return "virtuous";
    }

    @Override
    public boolean isConfigured(Environment env) {
        return env.getConfig().virtuous != null;
    }

    @Override
    public void init(Environment env) {
        this.tokenServerUrl = env.getConfig().virtuous.tokenServerUrl;
        this.username = env.getConfig().virtuous.username;
        this.password = env.getConfig().virtuous.password;
        this.accessToken = env.getConfig().virtuous.accessToken;
        this.refreshToken = env.getConfig().virtuous.refreshToken;
    }

    // Contacts
    @Override
    public Optional<CrmContact> getContactById(String id) throws Exception {
        try {
            Integer.parseInt(id);
        } catch (NumberFormatException nfe) {
            log.error("Failed to parse numeric id from string {}!", id);
            return Optional.empty();
        }
        Contact contact = getContact(VIRTUOUS_API_URL + "/Contact/" + id);
        return Optional.ofNullable(asCrmContact(contact));
    }

    @Override
    public Optional<CrmContact> getContactByEmail(String email) throws Exception {
        Contact contact = getContact(VIRTUOUS_API_URL + "/Contact/Find?email=" + email);
        return Optional.ofNullable(asCrmContact(contact));
    }

    private Contact getContact(String contactUrl) {
        Response response = HttpClient.getJson(contactUrl, getAccessToken());
        int statusCode = response.getStatus();
        if (statusCode == HttpStatus.SC_OK) {
            return response.readEntity(Contact.class);
        }
        if (statusCode == HttpStatus.SC_NOT_FOUND) {
            log.info("Contact not found.");
        } else {
            log.error("Failed to get contact by url: {}! Response (status/body): {}/{}", contactUrl, statusCode, response.readEntity(String.class));
        }
        return null;
    }

    @Override
    public Optional<CrmContact> getContactByPhone(String phone) throws Exception {
        List<Contact> contacts = queryContacts(List.of(queryCondition("Phone Number", "Is", phone)));
        if (CollectionUtils.isEmpty(contacts)) {
            return Optional.empty();
        }
        if (contacts.size() > 1) {
            log.warn("Found more than 1 contact for phone '{}'", phone);
        }
        return contacts.stream()
                .findFirst().map(this::asCrmContact);
    }

    @Override
    public String insertContact(CrmContact crmContact) throws Exception {
        Contact contact = asContact(crmContact);
        Response response = HttpClient.postJson(contact, getAccessToken(), VIRTUOUS_API_URL + "/Contact");
        if (isOk(response)) {
            Contact createdContact = response.readEntity(Contact.class);
            return createdContact.id + "";
        } else {
            log.error("Failed to create contact! Response: {}", response.readEntity(String.class));
            return null;
        }
    }

    @Override
    public void updateContact(CrmContact crmContact) throws Exception {
        Contact contact = asContact(crmContact);
        Response response = HttpClient.putJson(contact, getAccessToken(), VIRTUOUS_API_URL + "/Contact/" + crmContact.id);
        if (!isOk(response)) {
            log.error("Failed to update contact! Response: {}", response.readEntity(String.class));
        }
    }

    @Override
    public List<CrmContact> searchContacts(String firstName, String lastName, String email, String phone, String address) {
        List<QueryCondition> conditions = new ArrayList<>();
        if (!Strings.isNullOrEmpty(firstName)) {
            conditions.add(queryCondition("First Name", "Is", firstName));
        }
        if (!Strings.isNullOrEmpty(lastName)) {
            conditions.add(queryCondition("Last Name", "Is", lastName));
        }
        if (!Strings.isNullOrEmpty(email)) {
            conditions.add(queryCondition("Email Address", "Is", email));
        }
        if (!Strings.isNullOrEmpty(phone)) {
            conditions.add(queryCondition("Phone Number", "Is", phone));
        }
        if (!Strings.isNullOrEmpty(address)) {
            conditions.add(queryCondition("Address Line 1", "Is", address));
        }
        List<Contact> contacts = queryContacts(conditions);
        if (CollectionUtils.isEmpty(contacts)) {
            return Collections.emptyList();
        }
        return contacts.stream()
                .map(this::asCrmContact)
                .collect(Collectors.toList());
    }

    private QueryCondition queryCondition(String parameter, String operator, String value) {
        QueryCondition queryCondition = new QueryCondition();
        queryCondition.parameter = parameter;
        queryCondition.operator = operator;
        queryCondition.value = value;
        return queryCondition;
    }

    private List<Contact> queryContacts(List<QueryCondition> conditions) {
        QueryConditionGroup group = new QueryConditionGroup();
        group.conditions = conditions;

        ContactQuery query = new ContactQuery();
        //query.queryLocation = null; // TODO: decide if we need this param
        query.groups = List.of(group);
        query.sortBy = "Last Name";
        query.descending = false;
        Response response = HttpClient.postJson(
                query, getAccessToken(),
                VIRTUOUS_API_URL + "/Contact/Query/FullContact?skip=" + DEFAULT_OFFSET + "&take=" + DEFAULT_LIMIT);
        if (response.getStatus() == HttpStatus.SC_OK) {
            return response.readEntity(ContactQueryResponse.class).contacts;
        } else {
            log.error("Failed to query contacts! Response: {}", response.readEntity(String.class));
            return null;
        }
    }

    // TODO: move to a mapper class?
    private CrmContact asCrmContact(Contact contact) {
        if (Objects.isNull(contact)) {
            return null;
        }
        CrmContact crmContact = new CrmContact();
        crmContact.id = String.valueOf(contact.id);
        //crmContact.accountId = // ?
        ContactIndividual contactIndividual = getPrimaryContactIndividual(contact);
        crmContact.firstName = contactIndividual.firstName;
        crmContact.lastName = contactIndividual.lastName;
        crmContact.fullName = contact.name;

        //crmContact.email = ?
        crmContact.homePhone = getPhone(contactIndividual, "Home Phone");
        crmContact.mobilePhone = getPhone(contactIndividual, "Mobile Phone");
        crmContact.workPhone = getPhone(contactIndividual, "Work Phone");
        crmContact.otherPhone = getPhone(contactIndividual, "Other Phone");
        //crmContact.preferredPhone = CrmContact.PreferredPhone.MOBILE // ?

        crmContact.address = getCrmAddress(contact.address);

        //crmContact.emailOptIn;
        //crmContact.emailOptOut;
        //crmContact.smsOptIn;
        //crmContact.smsOptOut;
        //crmContact.ownerId;
        //crmContact.ownerName;
        //crmContact.totalDonationAmount = contact.lifeToDateGiving; // Parse double
        // crmContact.numDonations;
        //crmContact.firstDonationDate;
        crmContact.lastDonationDate = getCalendar(contact.lastGiftDate);
        crmContact.notes = contact.description;
        //  public List<String> emailGroups;
        //  public String contactLanguage;

        return crmContact;
    }

    private ContactIndividual getPrimaryContactIndividual(Contact contact) {
        return contact.contactIndividuals.stream()
                .filter(contactIndividual -> Boolean.TRUE == contactIndividual.isPrimary)
                .findFirst().orElse(null);

    }

    private String getPhone(ContactIndividual contactIndividual, String phoneType) {
        return contactIndividual.contactMethods.stream()
                .filter(contactMethod -> phoneType.equals(contactMethod.type))
                .findFirst()
                .map(contactMethod -> contactMethod.value).orElse(null);
    }

    private CrmAddress getCrmAddress(Address address) {
        if (Objects.isNull(address)) {
            return null;
        }
        CrmAddress crmAddress = new CrmAddress();
        crmAddress.country = address.country;
        crmAddress.state = address.state;
        crmAddress.city = address.city;
        crmAddress.postalCode = address.postal;
        crmAddress.street = address.address1;
        return crmAddress;
    }

    private Calendar getCalendar(String date) {
        Calendar calendar = null;
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT, Locale.ENGLISH);
        try {
            calendar = Calendar.getInstance();
            calendar.setTime(sdf.parse(date));
        } catch (ParseException e) {
            log.error("Failed to parse date string '{}'!", date);
        }
        return calendar;
    }

    private Contact asContact(CrmContact crmContact) {
        if (Objects.isNull(crmContact)) {
            return null;
        }
        Contact contact = new Contact();
        contact.id = Integer.parseInt(crmContact.id);
        contact.name = crmContact.fullName;
        contact.isPrivate = false;
        contact.contactType =
                "Household"; // Foundation/Organization/Household ?

        contact.address = asAddress(crmContact.address);

        ContactIndividual contactIndividual = new ContactIndividual();
        contactIndividual.contactId = contact.id;
        contactIndividual.firstName = crmContact.firstName;
        contactIndividual.lastName = crmContact.lastName;
        contactIndividual.isPrimary = true;
        contactIndividual.isSecondary = false;
        contactIndividual.isDeceased = false;
        contactIndividual.contactMethods = Stream.of(
                contactMethod("Home Email", crmContact.email, true, Boolean.TRUE == crmContact.emailOptIn),
                contactMethod("Home Phone", crmContact.homePhone, crmContact.preferredPhone == CrmContact.PreferredPhone.HOME, false),
                contactMethod("Mobile Phone", crmContact.mobilePhone, crmContact.preferredPhone == CrmContact.PreferredPhone.MOBILE, false),
                contactMethod("Work Phone", crmContact.workPhone, crmContact.preferredPhone == CrmContact.PreferredPhone.WORK, false),
                contactMethod("Other Phone", crmContact.otherPhone, crmContact.preferredPhone == CrmContact.PreferredPhone.OTHER, false)
        ).filter(Objects::nonNull).collect(Collectors.toList());

        contact.contactIndividuals = List.of(contactIndividual);

        return contact;
    }

    private Address asAddress(CrmAddress crmAddress) {
        if (Objects.isNull(crmAddress)) {
            return null;
        }
        Address address = new Address();
        address.country = crmAddress.country;
        address.state = crmAddress.state;
        address.city = crmAddress.city;
        address.postal = crmAddress.postalCode;
        address.address1 = crmAddress.street;
        address.isPrimary = true; // ?
        return address;
    }

    private ContactMethod contactMethod(String type, String value, boolean isPrimary, boolean isOptedIn) {
        if (Strings.isNullOrEmpty(value)) {
            return null;
        }
        ContactMethod contactMethod = new ContactMethod();
        contactMethod.type = type;
        contactMethod.value = value;
        contactMethod.isPrimary = isPrimary;
        contactMethod.isOptedIn = isOptedIn;
        return contactMethod;
    }

    // Donations
    @Override
    public Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
        // TODO: For now, safe to assume Stripe here,
        //  but might need an interface change...
        return getDonationByTransactionSourceAndId("stripe", transactionId);
    }

    public Optional<CrmDonation> getDonationByTransactionSourceAndId(String transactionSource, String transactionId) throws Exception {
        String giftUrl = VIRTUOUS_API_URL + "/Gift/" + transactionSource + "/" + transactionId;
        Gift gift = getGift(giftUrl);
        return Optional.ofNullable(asCrmDonation(gift));
    }

    private Gift getGift(String giftUrl) {
        Response response = HttpClient.getJson(giftUrl, getAccessToken());
        int statusCode = response.getStatus();
        if (statusCode == HttpStatus.SC_OK) {
            return response.readEntity(Gift.class);
        }
        if (statusCode == HttpStatus.SC_NOT_FOUND) {
            log.info("Gift not found.");
        } else {
            log.error("Failed to get gift by url: {}! Response (status/body): {}/{}", giftUrl, statusCode, response.readEntity(String.class));
        }
        return null;
    }

    // This endpoint creates a gift directly onto a contact record.
    // Using this endpoint assumes you know the precise contact the gift is matched to.
    // Virtuous does not support cleaning up data that is caused by
    // creating the gifts incorrectly through this endpoint.
    // Please use the Gift Transaction endpoint as a better alternative.
    // https://docs.virtuoussoftware.com/#5cbc35dc-6b1e-41da-b1a5-477043a9a66d
    @Override
    public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        Gift gift = asGift(paymentGatewayEvent);
        Response response = HttpClient.postJson(gift, getAccessToken(),
                VIRTUOUS_API_URL + "/Gift");
        if (isOk(response)) {
            Gift createdGift = response.readEntity(Gift.class);
            return createdGift.id + "";
        } else {
            log.error("Failed to create Gift Transaction! Response: {}", response.readEntity(String.class));
            return null;
        }
    }

    // This is the recommended way to create a gift.
    // This ensures the gift is matched using the Virtuous matching algorithms
    // for Contacts, Recurring gifts, Designations, etc.
    // https://docs.virtuoussoftware.com/#e4a6a1e3-71a4-44f9-bd7c-9466996befac
    public void insertDonationAsync(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        GiftTransaction giftTransaction = asGiftTransaction(paymentGatewayEvent);
        Response response = HttpClient.postJson(giftTransaction, getAccessToken(),
                VIRTUOUS_API_URL + "/v2/Gift/Transaction");
        if (!isOk(response)) {
            log.error("Failed to create Gift Transaction! Response: {}", response.readEntity(String.class));
        }
    }

    @Override
    public void insertDonationReattempt(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        CrmDonation existingDonation = getDonation(paymentGatewayEvent).get();
        Gift gift = asGift(paymentGatewayEvent);
        try {
            gift.id = Integer.parseInt(existingDonation.id);
        } catch (NumberFormatException nfe) {
            log.error("Failed to parse numeric id from string {}!", existingDonation.id);
            return;
        }
        Response response = HttpClient.putJson(gift, getAccessToken(), VIRTUOUS_API_URL + "/Gift" + "/" + gift.id);
        if (!isOk(response)) {
            log.error("Failed to update Gift! Response: {}", response.readEntity(String.class));
        }
    }

    @Override
    public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        CrmDonation existingDonation = getDonation(paymentGatewayEvent).get();
        Integer donationId;
        try {
            donationId = Integer.parseInt(existingDonation.id);
        } catch (NumberFormatException nfe) {
            log.error("Failed to parse numeric id from string {}!", existingDonation.id);
            return;
        }

        ReversingTransaction reversingTransaction = new ReversingTransaction();
        reversingTransaction.reversedGiftId = donationId;
        reversingTransaction.giftDate = paymentGatewayEvent.getTransactionDate().getTime();
        reversingTransaction.notes = "Reverting transaction: " +
                existingDonation.paymentGatewayName + "/" + existingDonation.id;

        Response response = HttpClient.postJson(reversingTransaction, getAccessToken(), VIRTUOUS_API_URL + "/Gift/ReversingTransaction");
        if (!isOk(response)) {
            log.error("Failed to create Reversing Transaction! Response: {}", response.readEntity(String.class));
        }
    }

    private CrmDonation asCrmDonation(Gift gift) {
        if (Objects.isNull(gift)) {
            return null;
        }
        CrmDonation crmDonation = new CrmDonation();
        crmDonation.id = gift.id + "";
        crmDonation.name = gift.transactionId; // ?
        crmDonation.amount = gift.amount;
        crmDonation.paymentGatewayName = gift.transactionSource; // ?
        //crmDonation.status = CrmDonation.Status.SUCCESSFUL; // ?
        crmDonation.closeDate = getCalendar(gift.giftDate); // ?
        crmDonation.crmUrl = gift.giftUrl;
        return crmDonation;
    }

    private Gift asGift(PaymentGatewayEvent paymentGatewayEvent) {
        if (Objects.isNull(paymentGatewayEvent)) {
            return null;
        }
        Gift gift = new Gift();

        gift.contactId = paymentGatewayEvent.getCrmContact().id;
        gift.giftType = "Credit"; // ?
        gift.giftDate = new SimpleDateFormat(DATE_FORMAT).format(paymentGatewayEvent.getTransactionDate().getTime());
        //gift.giftDate = paymentGatewayEvent.getTransactionDate();
        gift.amount = paymentGatewayEvent.getTransactionAmountInDollars();
        gift.transactionSource = paymentGatewayEvent.getGatewayName();
        gift.transactionId = paymentGatewayEvent.getTransactionId();
        gift.isPrivate = true; // ?
        gift.isTaxDeductible = true; // ?

        return gift;
    }

    private GiftTransaction asGiftTransaction(PaymentGatewayEvent paymentGatewayEvent) {
        if (Objects.isNull(paymentGatewayEvent)) {
            return null;
        }
        GiftTransaction giftTransaction = new GiftTransaction();

        giftTransaction.transactionSource = paymentGatewayEvent.getGatewayName(); // ?
        giftTransaction.transactionId = paymentGatewayEvent.getTransactionId(); // ?

        giftTransaction.amount = paymentGatewayEvent.getTransactionAmountInDollars() + ""; // TODO: double check if string indeed
        giftTransaction.giftDate = paymentGatewayEvent.getTransactionDate().getTime().toString();
        giftTransaction.contact = asContact(paymentGatewayEvent.getCrmContact());

        giftTransaction.recurringGiftTransactionUpdate = false; // ?
        giftTransaction.isPrivate = false; // ?
        giftTransaction.isTaxDeductible = false; // ?
        return giftTransaction;
    }

    @Override
    public List<CrmContact> getEmailContacts(Calendar updatedSince, String filter) throws Exception {
        List<Contact> contacts = queryContacts(List.of(queryCondition("Last Modified Date", "After", getLastModifiedDateValue(updatedSince))));
        if (CollectionUtils.isEmpty(contacts)) {
            return Collections.emptyList();
        }

        if (!Strings.isNullOrEmpty(filter)) {
            List<ContactIndividualShort> contactIndividuals = getContactIndividuals(filter, DEFAULT_OFFSET, DEFAULT_LIMIT);
            if (CollectionUtils.isEmpty(contactIndividuals)) {
                return Collections.emptyList();
            }
            Set<Integer> ids = contactIndividuals.stream()
                    .map(contactIndividualShort -> contactIndividualShort.id)
                    .collect(Collectors.toSet());
            contacts = contacts.stream()
                    .filter(contact -> ids.contains(contact.id))
                    .collect(Collectors.toList());
        }

        return contacts.stream()
                .map(this::asCrmContact)
                .collect(Collectors.toList());
    }

    private String getLastModifiedDateValue(Calendar calendar) {
        //"valueOptions": [
        //				"180 Days Ago",
        //				"270 Days Ago",
        //				"30 Days Ago",
        //				"60 Days Ago",
        //				"90 Days Ago",
        //				"Last Sunday",
        //				"One week from now",
        //				"One Year Ago",
        //				"Start Of This Month",
        //				"This Calendar Year",
        //				"Today",
        //				"Tomorrow",
        //				"Two Years Ago",
        //				"Yesterday"
        //			]
        LocalDateTime then = LocalDateTime.ofInstant(calendar.toInstant(), ZoneId.of("UTC"));
        long daysAgo = Duration.between(then, LocalDateTime.now()).toDays();
        String lastModifiedDate;
        if (daysAgo < 1) {
            lastModifiedDate = "Today";
        } else if (daysAgo >= 1) {
            lastModifiedDate = "Yesterday";
        } else if (daysAgo >= 30 && daysAgo < 60) {
            lastModifiedDate = "30 Days Ago";
        } else if (daysAgo >= 60 && daysAgo < 90) {
            lastModifiedDate = "60 Days Ago";
        } else if (daysAgo >= 90 && daysAgo < 180) {
            lastModifiedDate = "90 Days Ago";
        } else if (daysAgo >= 180 && daysAgo < 270) {
            lastModifiedDate = "180 Days Ago";
        } else if (daysAgo >= 270 && daysAgo < 365) {
            lastModifiedDate = "270 Days Ago";
        } else {
            lastModifiedDate = "One Year Ago";
        }
        return lastModifiedDate;
    }

    // TODO: switch to PagedResult
    private List<ContactIndividualShort> getContactIndividuals(String searchString, int offset, int limit) {
        ContactsSearchCriteria criteria = new ContactsSearchCriteria();
        criteria.search = searchString;
        Response response = HttpClient.postJson(
                criteria, getAccessToken(),
                VIRTUOUS_API_URL + "/Contact/Search?skip=" + offset + "&take=" + limit);
        int statusCode = response.getStatus();
        if (statusCode == HttpStatus.SC_OK) {
            return response.readEntity(ContactSearchResponse.class).contactIndividualShorts;
        } else {
            log.error("Failed to get contacts list for search string {}! Response: {}", searchString, response.readEntity(String.class));
            return null;
        }
    }

    @Override
    public List<CrmContact> getEmailDonorContacts(Calendar updatedSince, String filter) throws Exception {
        return null;
    }

    @Override
    public double getDonationsTotal(String filter) throws Exception {
        return 0;
    }

    @Override
    public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
        // TODO:
    }

    @Override
    public void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception {
        //TODO:
    }

    @Override
    public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
        return null;
    }


    private String getAccessToken() {
        // TODO: check access token from config, if available; howto?
        if (!containsValidAccessToken(tokenResponse)) {

            log.info("Getting new access token...");
            if (!Strings.isNullOrEmpty(refreshToken)) {
                // Refresh access token if possible
                log.info("Refreshing token...");
                tokenResponse = refreshAccessToken();
            } else {
                // Get new token pair otherwise
                log.info("Getting new pair of tokens...");
                tokenResponse = getTokenResponse();
                log.info("TR: {}", tokenResponse.accessToken);
            }

            // !
            // When fetching a token for a user with Two-Factor Authentication, you will receive a 202 (Accepted) response stating that a verification code is required.
            //The user will then need to enter the verification code that was sent to their phone. You will then request the token again but this time you will pass in an OTP (one-time-password) header with the verification code received
            //If the verification code and user credentials are correct, you will receive a token as seen in the Token authentication above.
            //To request a new Token after the user enters the verification code, add an OTP header:
            //curl -d "grant_type=password&username=YOUR_EMAIL&password=YOUR_PASSWORD&otp=YOUR_OTP" -X POST https://api.virtuoussoftware.com/Token
        }
        return tokenResponse.accessToken;
    }

    private boolean containsValidAccessToken(TokenResponse tokenResponse) {
        return Objects.nonNull(tokenResponse) && new Date().before(tokenResponse.expiresAt);
    }

    private TokenResponse refreshAccessToken() {
        // To refresh access token:
        // curl -d "grant_type=refresh_token&refresh_token=REFRESH_TOKEN"
        // -X POST https://api.virtuoussoftware.com/Token
        Response response = HttpClient.postForm(
                Map.of("grant_type", "refresh_token",
                        "refresh_token", refreshToken),
                null, tokenServerUrl);
        return response.readEntity(TokenResponse.class);
    }

    private TokenResponse getTokenResponse() {
        // To get access token:
        // curl -d
        // "grant_type=password&username=YOUR_EMAIL&password=YOUR_PASSWORD"
        // -X POST https://api.virtuoussoftware.com/Token

//        OkHttpClient client = new OkHttpClient().newBuilder()
//                .build();
//        MediaType mediaType = MediaType.parse("text/plain");
//        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
//                .addFormDataPart("grant_type","password")
//                .addFormDataPart("username","bobloblaw@loblaw.org")
//                .addFormDataPart("password","SomeFancyGoodPassword")
//                .addFormDataPart("otp","012345")
//                .build();
//        Request request = new Request.Builder()
//                .url("https://api.virtuoussoftware.com/Token")
//                .method("POST", body)
//                .build();
//        Response response = client.newCall(request).execute();
        Response response = HttpClient.postForm(
                Map.of("grant_type", "password"
                        , "username", username
                        , "password", password
                        //, "otp", "012345"
                ), null, tokenServerUrl);

        return response.readEntity(TokenResponse.class);
    }

    private boolean isOk(Response response) {
        return Set.of(HttpStatus.SC_OK, HttpStatus.SC_CREATED, HttpStatus.SC_ACCEPTED)
                .contains(response.getStatus());
    }

    // {
    //  "access_token": "abc123.....",
    //  "token_type": "bearer",
    //  "expires_in": 3599,
    //  "refresh_token": "zyx987...",
    //  "userName": "bobloblaw@loblaw.org",
    //  "twoFactorEnabled": "True",
    //  ".issued": "Thu, 10 Feb 2022 22:27:19 GMT",
    //  ".expires": "Thu, 10 Feb 2022 23:27:19 GMT"
    //}
    public static class TokenResponse {
        @JsonProperty("access_token")
        public String accessToken;
        @JsonProperty("token_type")
        public String tokenType;
        @JsonProperty("expires_in")
        public Integer expiresIn;
        @JsonProperty("refresh_token")
        public String refreshToken;
        @JsonProperty("userName")
        public String username;
        public Boolean twoFactorEnabled;
        @JsonProperty(".issued")
        public Date issuedAt;
        @JsonProperty(".expires")
        public Date expiresAt;
    }

    public static class ContactSearchResponse {
        @JsonProperty("list")
        public List<ContactIndividualShort> contactIndividualShorts;
        public Integer total;
    }

    public static class Contact {
        public Boolean isCurrentUserFollowing;
        public Integer id;
        public String contactType;
        public Boolean isPrivate;
        public String name;
        public String informalName;
        public String description;
        public String website;
        public String maritalStatus;
        public Integer anniversaryMonth;
        public Integer anniversaryDay;
        public Integer anniversaryYear;
        public Integer mergedIntoContactId;
        public Address address;
        public String giftAskAmount;
        public String giftAskType;
        public String lifeToDateGiving;
        public String yearToDateGiving;
        public String lastGiftAmount;
        public String lastGiftDate;
        public List<ContactIndividual> contactIndividuals;
        public String contactGiftsUrl;
        public String contactPassthroughGiftsUrl;
        public String contactPlannedGiftsUrl;
        public String contactRecurringGiftsUrl;
        public String contactImportantNotesUrl;
        public String contactNotesUrl;
        public String contactTagsUrl;
        public String contactRelationshipsUrl;
        public String primaryAvatarUrl;
        public List<ContactReference> contactReferences;
        public Integer originSegmentId;
        public String originSegment;
        public Date createDateTimeUtc;
        public Date modifiedDateTimeUtc;
        public List<String> tags;
        public List<String> organizationGroups;
        public List<CustomField> customFields;
        public List<CustomCollection> customCollections;
    }

    public static class Address {
        public Integer id;
        public String label;
        public String address1;
        public String address2;
        public String city;
        public String state;
        public String postal;
        public String country;
        public Boolean isPrimary;
        public Boolean canBePrimary;
        public Integer startDay;
        public Integer endMonth;
        public Integer endDay;
    }

    public static class ContactIndividual {
        public Integer id;
        public Integer contactId;
        public String prefix;
        public String firstName;
        public String middleName;
        public String lastName;
        public String suffix;
        public String gender;
        public Boolean isPrimary;
        public Boolean canBePrimary;
        public Boolean isSecondary;
        public Boolean canBeSecondary;
        public Integer birthMonth;
        public Integer birthDay;
        public Integer birthYear;
        public Integer birthDate;
        public Integer approximateAge;
        public Boolean isDeceased;
        public String passion;
        public String avatarUrl;
        public List<ContactMethod> contactMethods;
        public Date createDateTimeUtc;
        public Date modifiedDateTimeUtc;
        public List<CustomField> customFields;
        public List<CustomCollection> customCollections;
    }

    public static class ContactIndividualShort {
        public Integer individualId;
        public String name;
        public Integer id;
        public String contactType;
        public String contactName;
        public String address;
        public String email;
        public String phone;
        public String contactViewUrl;
    }

    public static class ContactMethod {
        public Integer id;
        public String type;
        public String value;
        public Boolean isOptedIn;
        public Boolean isPrimary;
        public Boolean canBePrimary;
    }

    public static class CustomField {
        public String name;
        public String value;
        public String displayName;
    }

    public static class CustomCollection {
        public Integer customCollectionId;
        public String customCollectionName;
        public Integer collectionInstanceId;
        public List<Field> fields;
    }

    public static class Field {
        public String name;
        public String value;
    }

    public static class ContactReference {
        public String source;
        public String id;
    }

    public static class ContactsSearchCriteria {
        public String search;
    }

    public static class Gift {
        public Integer id;
        public String transactionSource;
        public String transactionId;
        public String contactId;
        public String contactName;
        public String contactUrl;
        public String giftType;
        public String giftTypeFormatted;
        public String giftDate;
        public String giftDateFormatted;
        public Double amount;
        public String amountFormatted;
        public String batch;
        public Integer segmentId;
        public String segment;
        public String segmentCode;
        public String segmentUrl;
        public Integer mediaOutletId;
        public String mediaOutlet;
        public Integer grantId;
        public String grant;
        public String grantUrl;
        public String notes;
        public String tribute;
        public Integer tributeId;
        public String tributeType;
        public Integer acknowledgeeIndividualId;
        public Date receiptDate;
        public String receiptDateFormatted;
        public Integer contactPassthroughId;
        public String contactPassthroughUrl;
        public Integer contactIndividualId;
        public String cashAccountingCode;
        public Integer giftAskId;
        public Integer contactMembershipId;
        public List<GiftDesignation> giftDesignations;
        public List<GiftPremium> giftPremiums;
        public List<PledgePayment> pledgePayments;
        public List<RecurringGiftPayment> recurringGiftPayments;
        public String giftUrl;
        public Boolean isPrivate;
        public Boolean isTaxDeductible;
        public List<CustomField> customFields;
    }

    public static class GiftDesignation {
        public Integer id;
        public Integer projectId;
        public String project;
        public String projectCode;
        public String externalAccountingCode;
        public String projectType;
        public String projectLocation;
        public String projectUrl;
        public Double amountDesignated;
        public String display;
    }

    public static class GiftPremium {
        public Integer id;
        public Integer premiumId;
        public String premium;
        public String premiumUrl;
        public Integer quantity;
        public String display;
    }

    public static class PledgePayment {
        public Integer id;
        public Date expectedPaymentDate;
        public Double expectedAmount;
        public Integer giftId;
        public Double actualAmount;
    }

    public static class RecurringGiftPayment {
        public Integer id;
        public Gift gift;
        public Double expectedAmount;
        public Date expectedPaymentDate;
        public Date dismissPaymentDate;
        public Date fulfillPaymentDate;
    }

    public static class GiftTransaction {
        public String transactionSource;
        public String transactionId;
        public Contact contact;
        public String giftDate;
        public String cancelDate;
        public String giftType;
        public String amount;
        public String currencyCode;
        public String frequency;
        public String recurringGiftTransactionId;
        public Boolean recurringGiftTransactionUpdate;
        public String pledgeFrequency;
        public String pledgeTransactionId;
        public String batch;
        public String notes;
        public String segment;
        public String mediaOutlet;
        public String receiptDate;
        public String receiptSegment;
        public String cashAccountingCode;
        public String tribute;
        public TributeDedication tributeDedication;
        public Boolean isPrivate;
        public Boolean isTaxDeductible;
        public String checkNumber;
        public String creditCardType;
        public String nonCashGiftType;
        public String nonCashGiftDescription;
        public String stockTickerSymbol;
        public Integer stockNumberOfShares;
        public String submissionUrl;
        public List<Designation> designations;
        public List<Premium> premiums;
        public List<CustomField> customFields;
        public Integer contactIndividualId;
        public Contact passthroughContact;
        public EventAttendee eventAttendee;
    }

    public static class TributeDedication {
        public Integer tributeId;
        public String tributeType;
        public String tributeFirstName;
        public String tributeLastName;
        public String tributeCity;
        public String tributeState;
        public Integer acknowledgeeIndividualId;
        public String acknowledgeeLastName;
        public String acknowledgeeAddress;
        public String acknowledgeeCity;
        public String acknowledgeeState;
        public String acknowledgeePostal;
        public String acknowledgeeEmail;
        public String acknowledgeePhone;
    }

    public static class Designation {
        public Integer id;
        public String name;
        public String code;
        public String amountDesignated;
    }

    public static class Premium {
        public Integer id;
        public String name;
        public String code;
        public String quantity;
    }

    public static class EventAttendee {
        public Integer eventId;
        public String eventName;
        public Boolean invited;
        public Boolean rsvp;
        public Boolean rsvpResponse;
        public Boolean attended;
    }

    public static class ContactQuery {
        public QueryLocation queryLocation;
        public List<QueryConditionGroup> groups;
        public String sortBy;
        public Boolean descending;

    }

    public static class QueryLocation {
        public Double topLatitude;
        public Double leftLongitude;
        public Double bottomLatitude;
        public Double rightLongitude;
    }

    public static class QueryCondition {
        public String parameter;
        public String operator;
        public String value;
        public String secondaryValue;
        public List<String> values;
    }

    public static class QueryConditionGroup {
        public List<QueryCondition> conditions;
    }

    public static class ContactQueryResponse {
        @JsonProperty("list")
        public List<Contact> contacts;
        public Integer total;
    }

    public static class ReversingTransaction {
        public Date giftDate;
        public Integer reversedGiftId;
        public String notes;
    }

}
