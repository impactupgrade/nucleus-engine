package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.core.Response;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class VirtuousClient {

    private static final Logger log = LogManager.getLogger(VirtuousClient.class);

    //public static final String DATE_FORMAT = "MM/dd/yyyy HH:mm:ss z";
    public static final String DATE_FORMAT = "MM/dd/yyyy";

    private static final String VIRTUOUS_API_URL = "https://api.virtuoussoftware.com/api";
    private static final int DEFAULT_OFFSET = 0;
    private static final int DEFAULT_LIMIT = 100;

    private static TokenResponse tokenResponse;

    private String tokenServerUrl;
    private String username;
    private String password;
    private String accessToken;
    private String refreshToken;

    protected Environment env;

    public VirtuousClient(Environment env) {
        this.env = env;
        this.tokenServerUrl = env.getConfig().virtuous.tokenServerUrl;
        this.username = env.getConfig().virtuous.username;
        this.password = env.getConfig().virtuous.password;
        this.accessToken = env.getConfig().virtuous.accessToken;
        this.refreshToken = env.getConfig().virtuous.refreshToken;
    }

    // Contact
    public Contact createContact(Contact contact) {
        Response response = HttpClient.postJson(contact, getAccessToken(), VIRTUOUS_API_URL + "/Contact");
        if (isOk(response)) {
            return response.readEntity(Contact.class);
        } else {
            log.error("Failed to create contact! Response: {}", response.readEntity(String.class));
            return null;
        }
    }

    public Contact getContactById(Integer id) {
        return getContact(VIRTUOUS_API_URL + "/Contact/" + id);
    }

    public Contact getContactByEmail(String email) throws Exception {
        return getContact(VIRTUOUS_API_URL + "/Contact/Find?email=" + email);
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

    public List<Contact> getContactsModifiedAfter(Calendar modifiedAfter) {
        QueryCondition queryCondition = new QueryCondition();
        queryCondition.parameter = "Last Modified Date";
        queryCondition.operator = "After";
        queryCondition.value = getLastModifiedDateValue(modifiedAfter);

        QueryConditionGroup group = new QueryConditionGroup();
        group.conditions = List.of(queryCondition);

        ContactQuery query = new ContactQuery();
        //query.queryLocation = null; // TODO: decide if we need this param
        query.groups = List.of(group);
        query.sortBy = "Last Name";
        query.descending = false;

        return queryContacts(query);
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

    public Contact updateContact(Contact contact) {
        Response response = HttpClient.putJson(contact, getAccessToken(), VIRTUOUS_API_URL + "/Contact/" + contact.id);
        if (isOk(response)) {
            return response.readEntity(Contact.class);
        } else {
            log.error("Failed to update contact! Response: {}", response.readEntity(String.class));
            return null;
        }
    }

    public ContactMethod createContactMethod(ContactMethod contactMethod) {
        Response response = HttpClient.postJson(contactMethod, getAccessToken(), VIRTUOUS_API_URL + "/ContactMethod");
        if (isOk(response)) {
            return response.readEntity(ContactMethod.class);
        } else {
            log.error("Failed to create contact method! Response: {}", response.readEntity(String.class));
            return null;
        }
    }

    public ContactMethod updateContactMethod(ContactMethod contactMethod) {
        Response response = HttpClient.putJson(contactMethod, getAccessToken(), VIRTUOUS_API_URL + "/ContactMethod/" + contactMethod.id);
        if (isOk(response)) {
            return response.readEntity(ContactMethod.class);
        } else {
            log.error("Failed to update contact method! Response: {}", response.readEntity(String.class));
            return null;
        }
    }

    public ContactMethod deleteContactMethod(ContactMethod contactMethod) {
        Response response = HttpClient.delete(getAccessToken(), VIRTUOUS_API_URL + "/ContactMethod/" + contactMethod.id);
        if (isOk(response)) {
            return contactMethod;
        } else {
            log.error("Failed to delete contact method! Response: {}", response.readEntity(String.class));
            return null;
        }
    }

    public List<Contact> queryContacts(ContactQuery query) {
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

    public List<ContactIndividualShort> getContactIndividuals(String searchString) {
        return getContactIndividuals(searchString, DEFAULT_OFFSET, DEFAULT_LIMIT);
    }

    public List<ContactIndividualShort> getContactIndividuals(String searchString, int offset, int limit) {
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

    // Gift
    public Gift getGiftByTransactionSourceAndId(String transactionSource, String transactionId) {
        String giftUrl = VIRTUOUS_API_URL + "/Gift/" + transactionSource + "/" + transactionId;
        return getGift(giftUrl);
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
    public Gift createGift(Gift gift) {
        Response response = HttpClient.postJson(gift, getAccessToken(),
                VIRTUOUS_API_URL + "/Gift");
        if (isOk(response)) {
            return response.readEntity(Gift.class);
        } else {
            log.error("Failed to create Gift Transaction! Response: {}", response.readEntity(String.class));
            return null;
        }
    }

    // This is the recommended way to create a gift.
    // This ensures the gift is matched using the Virtuous matching algorithms
    // for Contacts, Recurring gifts, Designations, etc.
    // https://docs.virtuoussoftware.com/#e4a6a1e3-71a4-44f9-bd7c-9466996befac
    public void createGiftAsync(GiftTransaction giftTransaction) {
        Response response = HttpClient.postJson(giftTransaction, getAccessToken(),
                VIRTUOUS_API_URL + "/v2/Gift/Transaction");
        if (!isOk(response)) {
            log.error("Failed to create Gift Transaction! Response: {}", response.readEntity(String.class));
        }
    }

    public Gift updateGift(Gift gift) {
        Response response = HttpClient.putJson(gift, getAccessToken(), VIRTUOUS_API_URL + "/Gift" + "/" + gift.id);
        if (isOk(response)) {
            return response.readEntity(Gift.class);
        } else {
            log.error("Failed to update Gift! Response: {}", response.readEntity(String.class));
            return null;
        }
    }

    public Gift createReversingTransaction(Gift gift) throws Exception {
        Response response = HttpClient.postJson(reversingTransaction(gift), getAccessToken(), VIRTUOUS_API_URL + "/Gift/ReversingTransaction");
        if (isOk(response)) {
            return response.readEntity(Gift.class);
        } else {
            log.error("Failed to create Reversing Transaction! Response: {}", response.readEntity(String.class));
            return null;
        }
    }

    public ReversingTransaction reversingTransaction(Gift gift) {
        ReversingTransaction reversingTransaction = new ReversingTransaction();
        reversingTransaction.reversedGiftId = gift.id;
        reversingTransaction.giftDate = gift.giftDate;
        reversingTransaction.notes = "Reverting transaction: " +
                gift.transactionSource + "/" + gift.transactionId;
        return reversingTransaction;
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
        public Integer contactIndividualId;
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
