package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
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
    private static final String DATE_FORMAT = "MM/dd/yyyy";

    private static TokenResponse tokenResponse;

    private String tokenServerUrl;
    private String username;
    private String password;
    private String accessToken;
    private String refreshToken;

    protected Environment env;
    private ObjectMapper mapper;

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

        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // Contacts
    @Override
    public Optional<CrmContact> getContactById(String id) throws Exception {
        try {
            Integer.parseInt(id);
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("Failed to parse numeric id from string '" + id + "'!");
        }
        Contact contact = getContact(VIRTUOUS_API_URL + "/Contact/" + id);
        return Optional.ofNullable(asCrmContact(contact));
    }

    @Override
    public Optional<CrmContact> getContactByEmail(String email) throws Exception {
        Contact contact = getContact(VIRTUOUS_API_URL + "/Contact/Find?email=" + email);
        return Optional.ofNullable(asCrmContact(contact));
    }

    private Contact getContact(String contactUrl) throws Exception {
        HttpResponse response = executeGet(contactUrl);
        String responseString = getResponseString(response);
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == HttpStatus.SC_OK) {
            return mapper.readValue(responseString, Contact.class);
        }
        if (statusCode == HttpStatus.SC_NOT_FOUND) {
            log.info("Contact not found.");
        } else {
            log.error("Failed to get contact by url: {}! Response (status/body): {}/{}", contactUrl, statusCode, responseString);
        }
        return null;
    }

    @Override
    public Optional<CrmContact> getContactByPhone(String phone) throws Exception {
        List<ContactIndividualShort> contactIndividuals = getContactIndividualsList(phone);
        if (CollectionUtils.isEmpty(contactIndividuals)) {
            return Optional.empty();
        }
        if (contactIndividuals.size() > 1) {
            log.warn("Found more than 1 contact individuals for phone '{}'", phone);
        }
        return Optional.of(asCrmContact(contactIndividuals.get(0)));
    }

    private List<ContactIndividualShort> getContactIndividualsList(String searchString) throws Exception {
        ContactsSearchCriteria criteria = new ContactsSearchCriteria();
        criteria.search = searchString;
        // TODO: check if parameters are required
        // defaults are skip = 0, take = 10
        HttpResponse response = executePost(
                VIRTUOUS_API_URL + "/Contact/Search?skip=0&take=10",
                mapper.writeValueAsString(criteria));
        String responseString = getResponseString(response);
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == HttpStatus.SC_OK) {
            return mapper.readValue(responseString, ContactSearchResponse.class).contactIndividualShorts;
        } else {
            log.error("Failed to get contacts list for search string {}! Response: {}", searchString, responseString);
        }
        return Collections.emptyList();
    }

    @Override
    public String insertContact(CrmContact crmContact) throws Exception {
        Contact contact = asContact(crmContact);
        HttpResponse response = executePost(VIRTUOUS_API_URL + "/Contact", mapper.writeValueAsString(contact));
        String responseString = getResponseString(response);
        if (isOk(response)) {
            Contact createdContact = mapper.readValue(responseString, Contact.class);
            return createdContact.id + "";
        } else {
            log.error("Failed to create contact! Response: {}", responseString);
            return null;
        }
    }

    @Override
    public void updateContact(CrmContact crmContact) throws Exception {
        Contact contact = asContact(crmContact);
        HttpResponse response = executePut(VIRTUOUS_API_URL + "/Contact/" + crmContact.id, mapper.writeValueAsString(contact));
        if (!isOk(response)) {
            log.error("Failed to update contact! Response: {}", getResponseString(response));
        }
    }

    @Override
    public List<CrmContact> searchContacts(String firstName, String lastName, String email, String phone, String address) throws Exception {
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

        QueryConditionGroup group = new QueryConditionGroup();
        group.conditions = conditions;

        ContactQuery query = new ContactQuery();
        //query.queryLocation = null; // TODO: decide if we need this param
        query.groups = List.of(group);
        query.sortBy = "Last Name";
        query.descending = false;

        HttpResponse response = executePost(VIRTUOUS_API_URL + "/Contact/Query/FullContact?skip=0&take=10", mapper.writeValueAsString(query));
        String responseString = getResponseString(response);
        if (isOk(response)) {
            List<Contact> contacts = mapper.readValue(responseString, ContactQueryResponse.class).contacts;
            return contacts.stream()
                    .map(this::asCrmContact)
                    .collect(Collectors.toList());
        } else {
            log.error("Failed to query contacts! Response: {}", responseString);
            return null;
        }
    }

    private QueryCondition queryCondition(String parameter, String operator, String value) {
        QueryCondition queryCondition = new QueryCondition();
        queryCondition.parameter = parameter;
        queryCondition.operator = operator;
        queryCondition.value = value;
        return queryCondition;
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

    private CrmContact asCrmContact(ContactIndividualShort contactIndividualShort) {
        if (Objects.isNull(contactIndividualShort)) {
            return null;
        }
        CrmContact crmContact = new CrmContact();
        crmContact.id = String.valueOf(contactIndividualShort.id);
        //crmContact.accountId = // ?
        crmContact.fullName = contactIndividualShort.name;

        crmContact.email = contactIndividualShort.email;
        //crmContact.homePhone = getPhone(contactIndividual, "Home Phone");
        crmContact.mobilePhone = contactIndividualShort.phone;
        //crmContact.workPhone = getPhone(contactIndividual, "Work Phone");
        //crmContact.preferredPhone = CrmContact.PreferredPhone.MOBILE // ?

        //crmContact.emailOptIn;
        //crmContact.emailOptOut;
        //crmContact.smsOptIn;
        //crmContact.smsOptOut;
        //crmContact.ownerId;
        //crmContact.ownerName;
        //crmContact.totalDonationAmount = contact.lifeToDateGiving; // Parse double
        // crmContact.numDonations;
        //crmContact.firstDonationDate;
        //crmContact.lastDonationDate = getCalendar(contact.lastGiftDate);
        //crmContact.notes = contact.description;
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
        contact.contactType = "Household"; // Foundation/Organization/Household ?

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
        String transactionSource = "stripe"; // TODO: where to get this one from?
        String giftUrl = VIRTUOUS_API_URL + "/Gift/" + transactionSource + "/" + transactionId;
        HttpResponse response = executeGet(giftUrl);
        String responseString = getResponseString(response);
        if (isOk(response)) {
            Gift gift = mapper.readValue(responseString, Gift.class);
            return Optional.of(asCrmDonation(gift));
        } else {
            log.error("Failed to get gift by url: {}! Response: {}", giftUrl, responseString);
            return Optional.empty();
        }
    }

    @Override
    public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        //GiftTransaction giftTransaction = asGiftTransaction(paymentGatewayEvent);
        Gift giftTransaction = asGift(paymentGatewayEvent);
        HttpResponse response = executePost(
                //VIRTUOUS_API_URL + "/v2/Gift/Transaction",
                VIRTUOUS_API_URL + "/Gift",
                mapper.writeValueAsString(giftTransaction)
        );
        String responseString = getResponseString(response);
        if (isOk(response)) {
            Gift gift = mapper.readValue(responseString, Gift.class);
            return gift.id + "";
        } else {
            log.error("Failed to create Gift Transaction! Response: {}", responseString);
            return null;
        }
    }

    @Override
    public void insertDonationReattempt(PaymentGatewayEvent paymentGatewayEvent) throws Exception {

    }

    @Override
    public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {

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

    @Override
    public List<CrmContact> getEmailContacts(Calendar updatedSince, String filter) throws Exception {
        return null;
    }

    @Override
    public List<CrmContact> getEmailDonorContacts(Calendar updatedSince, String filter) throws Exception {
        return null;
    }

    @Override
    public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {

    }

    @Override
    public void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception {

    }

    @Override
    public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
        return null;
    }


    private String getAccessToken() throws Exception {
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

    private TokenResponse refreshAccessToken() throws Exception {
        // To refresh access token:
        // curl -d "grant_type=refresh_token&refresh_token=REFRESH_TOKEN"
        // -X POST https://api.virtuoussoftware.com/Token
        HttpResponse response = executePost(
                tokenServerUrl,
                Map.of("grant_type", "refresh_token",
                        "refresh_token", refreshToken)
        );
        return mapper.readValue(getResponseString(response), TokenResponse.class);
    }

    private TokenResponse getTokenResponse() throws Exception {
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
        HttpResponse response = executePost(
                tokenServerUrl,
                Map.of("grant_type", "password"
                        , "username", username
                        , "password", password
                        //, "otp", "012345"
                )
        );

        return mapper.readValue(getResponseString(response), TokenResponse.class);
    }

    private HttpResponse executePost(String url, Map<String, String> formParams) throws IOException {
        HttpPost post = new HttpPost(url);
        List<NameValuePair> params = formParams.entrySet().stream()
                .map(e -> new BasicNameValuePair(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
        post.setEntity(new UrlEncodedFormEntity(params));
        HttpClient httpClient = HttpClientBuilder.create().build();
        HttpResponse response = httpClient.execute(post);
        return response;
    }

    private HttpResponse executePost(String url, String body) throws Exception {
        HttpPost post = new HttpPost(url);
        post.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
        post.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + getAccessToken());
        post.setEntity(new StringEntity(body));
        HttpClient httpClient = HttpClientBuilder.create().build();
        log.info("Executing post...");
        log.info("Request url: {}", url);
        log.info("Request body: {}", body);
        HttpResponse response = httpClient.execute(post);
        log.info("Executing post done! Response status: {}", response.getStatusLine().getStatusCode());
        return response;
    }

    private HttpResponse executeGet(String url) throws Exception {
        HttpGet get = new HttpGet(url);
        get.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + getAccessToken());
        HttpClient httpClient = HttpClientBuilder.create().build();
        HttpResponse response = httpClient.execute(get);
        return response;
    }

    private HttpResponse executePut(String url, String body) throws Exception {
        HttpPut put = new HttpPut(url);
        put.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
        put.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + getAccessToken());
        put.setEntity(new StringEntity(body));
        HttpClient httpClient = HttpClientBuilder.create().build();
        HttpResponse response = httpClient.execute(put);
        return response;
    }

    private boolean isOk(HttpResponse httpResponse) {
        return Set.of(HttpStatus.SC_OK, HttpStatus.SC_CREATED, HttpStatus.SC_ACCEPTED)
                .contains(httpResponse.getStatusLine().getStatusCode());
    }

    private String getResponseString(org.apache.http.HttpResponse response) throws IOException {
        try (InputStream stream = response.getEntity().getContent()) {
            return IOUtils.toString(stream, "UTF-8");
        }
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

    // TODO: use 1 entity with merged fields?
    // TODO: find a better name
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


}
