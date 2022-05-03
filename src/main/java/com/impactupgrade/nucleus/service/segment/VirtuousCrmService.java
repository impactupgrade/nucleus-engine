package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
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
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class VirtuousCrmService implements BasicCrmService {

    private static final Logger log = LogManager.getLogger(VirtuousCrmService.class);

    private static final String VIRTUOUS_API_URL = "https://api.virtuoussoftware.com/api";

    private String tokenServerUrl;
    private String username;
    private String password;
    private String accessToken;
    private String refreshToken;

    protected Environment env;
    private ObjectMapper mapper;

    private TokenResponse tokenResponse;

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
        Contact contact = getContact(VIRTUOUS_API_URL + "/Contact/" + id);
        return Optional.ofNullable(asCrmContact(contact));
    }

    @Override
    public Optional<CrmContact> getContactByEmail(String email) throws Exception {
        Contact contact = getContact(VIRTUOUS_API_URL + "/Contact/Find?email=" + email);
        return Optional.ofNullable(asCrmContact(contact));
    }

    @Override
    public Optional<CrmContact> getContactByPhone(String phone) throws Exception {
        List<Contact> contacts = getContactsList(
                // TODO: check if parameters are required
                // defaults are skip = 0, take = 10
                VIRTUOUS_API_URL + "/Contact/Search?skip=0&take=10", phone);
        if (CollectionUtils.isEmpty(contacts)) {
            return Optional.empty();
        }
        if (contacts.size() > 1) {
            log.warn("Found more than 1 contact for phone '{}'", phone);
        }
        return Optional.of(asCrmContact(contacts.get(0)));
    }

    private Contact getContact(String contactUrl) throws Exception {
        HttpResponse response = executeGet(contactUrl);
        String responseString = getResponseString(response);
        if (isOk(response)) {
            return mapper.readValue(responseString, Contact.class);
        } else {
            log.error("Failed to get contact by url: {}! Response: {}", contactUrl, responseString);
            return null;
        }
    }

    private List<Contact> getContactsList(String contactsListUrl, String searchString) throws Exception {
        ContactsSearchCriteria criteria = new ContactsSearchCriteria();
        criteria.search = searchString;
        HttpResponse response = executePost(
                contactsListUrl,
                Map.of(
                        HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType(),
                        HttpHeaders.AUTHORIZATION, getAccessToken()),
                mapper.writeValueAsString(criteria));
        if (isOk(response)) {
            return mapper.readValue(getResponseString(response), List.class);
        } else {
            log.error("Failed to get contacts list by url: {} and search string {}!", contactsListUrl, searchString);
            return Collections.emptyList();
        }
    }

    @Override
    public String insertContact(CrmContact crmContact) throws Exception {
        Contact contact = asContact(crmContact);
        HttpResponse response = executePost(
                VIRTUOUS_API_URL + "/Contact",
                Map.of(
                        HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType(),
                        HttpHeaders.AUTHORIZATION, getAccessToken()),
                mapper.writeValueAsString(contact)
        );
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
        String id = crmContact.id;
        Contact contact = asContact(crmContact);
        HttpResponse response = executePost(
                VIRTUOUS_API_URL + "/Contact/" + id,
                Map.of(
                        HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType(),
                        HttpHeaders.AUTHORIZATION, getAccessToken()),
                mapper.writeValueAsString(contact)
        );
        if (!isOk(response)) {
            log.error("Failed to update contact! Response: {}", getResponseString(response));
        }
    }

    @Override
    public List<CrmContact> searchContacts(String firstName, String lastName, String email, String phone, String address) throws Exception {
        // TODO: how to?
        return null;
    }

    private CrmContact asCrmContact(Contact contact) {
        if (Objects.isNull(contact)) {
            return null;
        }
        CrmContact crmContact = new CrmContact();
        crmContact.id = String.valueOf(contact.id);
        //crmContact.accountId = // ?
        //crmContact.firstName = // ?
        //crmContact.lastName = // ?
        crmContact.fullName = contact.name;
        //crmContact.email = // ?
        return crmContact;
    }

    private Contact asContact(CrmContact crmContact) {
        if (Objects.isNull(crmContact)) {
            return null;
        }
        Contact contact = new Contact();
        contact.name = crmContact.fullName;
        // TODO: mappings
        return contact;
    }

    // Donation
    @Override
    public Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
        String transactionSource = "abc"; // TODO: where to get this one from? Any default value?
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
        GiftTransaction giftTransaction = asGiftTransaction(paymentGatewayEvent);
        HttpResponse response = executePost(
                VIRTUOUS_API_URL + "/v2/Gift/Transaction",
                Map.of(
                        HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType(),
                        HttpHeaders.AUTHORIZATION, getAccessToken()),
                mapper.writeValueAsString(giftTransaction)
        );
        if (isOk(response)) {
            // TODO: extract created item's id
            return "abc";
        } else {
            log.error("Failed to create Gift Transaction! Response: {}", getResponseString(response));
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
        // TODO: mappings
        return crmDonation;
    }

    private GiftTransaction asGiftTransaction(PaymentGatewayEvent paymentGatewayEvent) {
        if (Objects.isNull(paymentGatewayEvent)) {
            return null;
        }
        GiftTransaction giftTransaction = new GiftTransaction();
        giftTransaction.amount = paymentGatewayEvent.getTransactionAmountInDollars() + ""; // TODO: double check if string indeed
        giftTransaction.giftDate = paymentGatewayEvent.getTransactionDate().getTime().toString();
        giftTransaction.contact = asContact(paymentGatewayEvent.getCrmContact());
        // TODO: mappings
        return giftTransaction;
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
        if (!containsValidAccessToken(tokenResponse)) {

            if (!Strings.isNullOrEmpty(refreshToken)) {
                // Refresh access token if possible
                tokenResponse = refreshAccessToken();
            } else {
                // Get new token pair otherwise
                tokenResponse = getTokenResponse();
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
                Collections.emptyMap(),
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
                Collections.emptyMap(),
                Map.of("grant_type", "password",
                        "username", username,
                        "password", password,
                        "otp", "012345")
        );

        return mapper.readValue(getResponseString(response), TokenResponse.class);
    }

    private HttpResponse executePost(String url, Map<String, String> headers, Map<String, String> formParams) throws IOException {
        HttpPost post = new HttpPost(url);
        headers.entrySet().forEach(e -> {
            post.setHeader(e.getKey(), e.getValue());
        });
        List<NameValuePair> params = formParams.entrySet().stream()
                .map(e -> new BasicNameValuePair(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
        post.setEntity(new UrlEncodedFormEntity(params));
        HttpClient httpClient = HttpClientBuilder.create().build();
        HttpResponse response = httpClient.execute(post);
        return response;
    }

    private HttpResponse executePost(String url, Map<String, String> headers, String body) throws IOException {
        HttpPost post = new HttpPost(url);
        headers.entrySet().forEach(e -> {
            post.setHeader(e.getKey(), e.getValue());
        });
        post.setEntity(new StringEntity(body));
        HttpClient httpClient = HttpClientBuilder.create().build();
        HttpResponse response = httpClient.execute(post);
        return response;
    }

    private HttpResponse executeGet(String url) throws Exception {
        HttpGet get = new HttpGet(url);
        get.addHeader(HttpHeaders.AUTHORIZATION, getAccessToken());
        HttpClient httpClient = HttpClientBuilder.create().build();
        HttpResponse response = httpClient.execute(get);
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
        public Date giftDate;
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

    public class GiftPremium {
        public Integer id;
        public Integer premiumId;
        public String premium;
        public String premiumUrl;
        public Integer quantity;
        public String display;
    }

    public class PledgePayment {
        public Integer id;
        public Date expectedPaymentDate;
        public Double expectedAmount;
        public Integer giftId;
        public Double actualAmount;
    }

    public class RecurringGiftPayment {
        public Integer id;
        public Gift gift;
        public Double expectedAmount;
        public Date expectedPaymentDate;
        public Date dismissPaymentDate;
        public Date fulfillPaymentDate;
    }

    public class GiftTransaction {
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

    public class TributeDedication {
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

    public class Designation {
        public Integer id;
        public String name;
        public String code;
        public String amountDesignated;
    }

    public class Premium {
        public Integer id;
        public String name;
        public String code;
        public String quantity;
    }

    public class EventAttendee {
        public Integer eventId;
        public String eventName;
        public Boolean invited;
        public Boolean rsvp;
        public Boolean rsvpResponse;
        public Boolean attended;
    }


}
