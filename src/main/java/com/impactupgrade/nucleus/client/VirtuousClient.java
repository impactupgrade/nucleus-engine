package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.impactupgrade.nucleus.util.HttpClient.delete;
import static com.impactupgrade.nucleus.util.HttpClient.get;
import static com.impactupgrade.nucleus.util.HttpClient.post;
import static com.impactupgrade.nucleus.util.HttpClient.put;
import static com.impactupgrade.nucleus.util.HttpClient.TokenResponse;
import static javax.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

public class VirtuousClient {

    private static final Logger log = LogManager.getLogger(VirtuousClient.class);

    private static final String VIRTUOUS_API_URL = "https://api.virtuoussoftware.com/api";
    private static final int DEFAULT_OFFSET = 0;
    private static final int DEFAULT_LIMIT = 100;

    private static TokenResponse tokenResponse;

    private String apiKey;
    private String username;
    private String password;
    private String tokenServerUrl;

    private String accessToken;
    private String refreshToken;

    protected Environment env;

    public VirtuousClient(Environment env) {
        this.env = env;

        this.apiKey = env.getConfig().virtuous.secretKey;

        this.username = env.getConfig().virtuous.username;
        this.password = env.getConfig().virtuous.password;
        this.tokenServerUrl = env.getConfig().virtuous.tokenServerUrl;

        this.accessToken = env.getConfig().virtuous.accessToken;
        this.refreshToken = env.getConfig().virtuous.refreshToken;
    }

    // Contact
    public Contact createContact(Contact contact) {
        contact = post(VIRTUOUS_API_URL + "/Contact", contact, APPLICATION_JSON, headers(), Contact.class);
        if (contact != null) {
          log.info("Created contact: {}", contact);
        }
        return contact;
    }

    public Contact getContactById(Integer id) {
        return getContact(VIRTUOUS_API_URL + "/Contact/" + id);
    }

    public Contact getContactByEmail(String email) throws Exception {
        return getContact(VIRTUOUS_API_URL + "/Contact/Find?email=" + email);
    }

    private Contact getContact(String contactUrl) {
        return get(contactUrl, headers(), Contact.class);
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
        // TODO: daysAgo >= 1 is always true. What was intended here?
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
        contact = put(VIRTUOUS_API_URL + "/Contact/" + contact.id, contact, APPLICATION_JSON, headers(), Contact.class);
        if (contact != null) {
          log.info("Updated contact: {}", contact);
        }
        return contact;
    }

    public ContactMethod createContactMethod(ContactMethod contactMethod) {
        contactMethod = post(VIRTUOUS_API_URL + "/ContactMethod", contactMethod, APPLICATION_JSON, headers(), ContactMethod.class);
        if (contactMethod != null) {
          log.info("Created contactMethod: {}", contactMethod);
        }
        return contactMethod;
    }

    public ContactMethod updateContactMethod(ContactMethod contactMethod) {
        contactMethod = put(VIRTUOUS_API_URL + "/ContactMethod/" + contactMethod.id, contactMethod, APPLICATION_JSON, headers(), ContactMethod.class);
        if (contactMethod != null) {
          log.info("Updated contactMethod: {}", contactMethod);
        }
        return contactMethod;
    }

    public void deleteContactMethod(ContactMethod contactMethod) {
        delete(VIRTUOUS_API_URL + "/ContactMethod/" + contactMethod.id, headers());
        log.info("Deleted contactMethod: {}", contactMethod.id);
    }

    public List<Contact> queryContacts(ContactQuery query) {
        ContactQueryResponse response = post(VIRTUOUS_API_URL + "/Contact/Query/FullContact?skip=" + DEFAULT_OFFSET + "&take=" + DEFAULT_LIMIT, query, APPLICATION_JSON, headers(), ContactQueryResponse.class);
        if (response == null) {
            return Collections.emptyList();
        }
        return response.contacts;
    }

    public List<ContactIndividualShort> getContactIndividuals(String searchString) {
        return getContactIndividuals(searchString, DEFAULT_OFFSET, DEFAULT_LIMIT);
    }

    public List<ContactIndividualShort> getContactIndividuals(String searchString, int offset, int limit) {
        ContactsSearchCriteria criteria = new ContactsSearchCriteria();
        criteria.search = searchString;
        ContactSearchResponse response = post(VIRTUOUS_API_URL + "/Contact/Search?skip=" + offset + "&take=" + limit, criteria, APPLICATION_JSON, headers(), ContactSearchResponse.class);
        if (response == null) {
            return Collections.emptyList();
        }
        return response.contactIndividualShorts;
    }

    // Gift
    public Gift getGiftByTransactionSourceAndId(String transactionSource, String transactionId) {
        String giftUrl = VIRTUOUS_API_URL + "/Gift/" + transactionSource + "/" + transactionId;
        return getGift(giftUrl);
    }

    private Gift getGift(String giftUrl) {
        return get(giftUrl, headers(), Gift.class);
    }

    // This endpoint creates a gift directly onto a contact record.
    // Using this endpoint assumes you know the precise contact the gift is matched to.
    // Virtuous does not support cleaning up data that is caused by
    // creating the gifts incorrectly through this endpoint.
    // Please use the Gift Transaction endpoint as a better alternative.
    // https://docs.virtuoussoftware.com/#5cbc35dc-6b1e-41da-b1a5-477043a9a66d
    public Gift createGift(Gift gift) {
        gift = post(VIRTUOUS_API_URL + "/Gift", gift, APPLICATION_JSON, headers(), Gift.class);
        if (gift != null) {
          log.info("Created gift: {}", gift);
        }
        return gift;
    }

    // This is the recommended way to create a gift.
    // This ensures the gift is matched using the Virtuous matching algorithms
    // for Contacts, Recurring gifts, Designations, etc.
    // https://docs.virtuoussoftware.com/#e4a6a1e3-71a4-44f9-bd7c-9466996befac
    public void createGiftAsync(GiftTransaction giftTransaction) {
        post(VIRTUOUS_API_URL + "/v2/Gift/Transaction", giftTransaction, APPLICATION_JSON, headers());
    }

    public Gift updateGift(Gift gift) {
        gift = put(VIRTUOUS_API_URL + "/Gift" + "/" + gift.id, gift, APPLICATION_JSON, headers(), Gift.class);
        if (gift != null) {
          log.info("Updated gift: {}", gift);
        }
        return gift;
    }

    // TODO: Should this return ReversingTransaction? Does the API respond with ReversingTransaction or the Gift?
    public Gift createReversingTransaction(Gift gift) throws Exception {
        gift = post(VIRTUOUS_API_URL + "/Gift/ReversingTransaction", reversingTransaction(gift), APPLICATION_JSON, headers(), Gift.class);
        if (gift != null) {
          log.info("Created reversing transaction: {}", gift);
        }
        return gift;
    }

    private ReversingTransaction reversingTransaction(Gift gift) {
        ReversingTransaction reversingTransaction = new ReversingTransaction();
        reversingTransaction.reversedGiftId = gift.id;
        reversingTransaction.giftDate = gift.giftDate;
        reversingTransaction.notes = "Reverting transaction: " +
                gift.transactionSource + "/" + gift.transactionId;
        return reversingTransaction;
    }

    public Task createTask(Task task) throws Exception {
      task = post(VIRTUOUS_API_URL + "/Task", task, APPLICATION_JSON, headers(), Task.class);
      if (task != null) {
        log.info("Created task: {}", task);
      }
      return task;
    }

    private HttpClient.HeaderBuilder headers() {
        // First, use the simple API key, if available.
        
        if (!Strings.isNullOrEmpty(apiKey)) {
            return HttpClient.HeaderBuilder.builder().authBearerToken(apiKey); 
        }

        // Otherwise, assume oauth.
        
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
        return HttpClient.HeaderBuilder.builder().authBearerToken(tokenResponse.accessToken);
    }

    private boolean containsValidAccessToken(TokenResponse tokenResponse) {
        return Objects.nonNull(tokenResponse) && new Date().before(tokenResponse.expiresAt);
    }

    private TokenResponse refreshAccessToken() {
        // To refresh access token:
        // curl -d "grant_type=refresh_token&refresh_token=REFRESH_TOKEN"
        // -X POST https://api.virtuoussoftware.com/Token
        return post(tokenServerUrl, Map.of("grant_type", "refresh_token", "refresh_token", refreshToken), APPLICATION_FORM_URLENCODED, headers(), TokenResponse.class);
    }

    private TokenResponse getTokenResponse() {
        return post(tokenServerUrl, Map.of("grant_type", "password", "username", username, "password", password), APPLICATION_FORM_URLENCODED, headers(), TokenResponse.class);
    }

    public static class ContactSearchResponse {
        @JsonProperty("list")
        public List<ContactIndividualShort> contactIndividualShorts = new ArrayList<>();
        public Integer total;

      @Override
      public String toString() {
        return "ContactSearchResponse{" +
            "contactIndividualShorts=" + contactIndividualShorts +
            ", total=" + total +
            '}';
      }
    }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Contact {
    //        public Boolean isCurrentUserFollowing;
    public Integer id;
    public String contactType;
    public Boolean isPrivate;
    public String name;
    //        public String informalName;
    public String description;
    //        public String website;
//        public String maritalStatus;
//        public Integer anniversaryMonth;
//        public Integer anniversaryDay;
//        public Integer anniversaryYear;
//        public Integer mergedIntoContactId;
    // TODO: address is needed for GET, but contactAddresses for POST
    public Address address;
    public List<Address> contactAddresses = new ArrayList<>();
    //        public String giftAskAmount;
//        public String giftAskType;
//        public String lifeToDateGiving;
//        public String yearToDateGiving;
//        public String lastGiftAmount;
    public String lastGiftDate;
    public List<ContactIndividual> contactIndividuals = new ArrayList<>();
//        public String contactGiftsUrl;
//        public String contactPassthroughGiftsUrl;
//        public String contactPlannedGiftsUrl;
//        public String contactRecurringGiftsUrl;
//        public String contactImportantNotesUrl;
//        public String contactNotesUrl;
//        public String contactTagsUrl;
//        public String contactRelationshipsUrl;
//        public String primaryAvatarUrl;
//        public List<ContactReference> contactReferences;
//        public Integer originSegmentId;
//        public String originSegment;
//        public Date createDateTimeUtc;
//        public Date modifiedDateTimeUtc;
//        public List<String> tags;
//        public List<String> organizationGroups;
//        public List<CustomField> customFields;
//        public List<CustomCollection> customCollections;


    @Override
    public String toString() {
      return "Contact{" +
          "id=" + id +
          ", contactType='" + contactType + '\'' +
          ", isPrivate=" + isPrivate +
          ", name='" + name + '\'' +
          ", description='" + description + '\'' +
          ", address=" + address +
          ", lastGiftDate='" + lastGiftDate + '\'' +
          ", contactIndividuals=" + contactIndividuals +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Address {
    public Integer id;
    //        public String label;
    public String address1;
    //        public String address2;
    public String city;
    public String state;
    public String postal;
    public String country;
    public Boolean isPrimary;
//        public Boolean canBePrimary;
//        public Integer startDay;
//        public Integer startMonth;
//        public Integer endMonth;
//        public Integer endDay;


    @Override
    public String toString() {
      return "Address{" +
          "id=" + id +
          ", address1='" + address1 + '\'' +
          ", city='" + city + '\'' +
          ", state='" + state + '\'' +
          ", postal='" + postal + '\'' +
          ", country='" + country + '\'' +
          ", isPrimary=" + isPrimary +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactIndividual {
    public Integer id;
    public Integer contactId;
    //        public String prefix;
    public String firstName;
    //        public String middleName;
    public String lastName;
    //        public String suffix;
//        public String gender;
    public Boolean isPrimary;
    //        public Boolean canBePrimary;
    public Boolean isSecondary;
    //        public Boolean canBeSecondary;
//        public Integer birthMonth;
//        public Integer birthDay;
//        public Integer birthYear;
//        public String birthDate;
//        public Integer approximateAge;
    public Boolean isDeceased;
    //        public String passion;
//        public String avatarUrl;
    public List<ContactMethod> contactMethods = new ArrayList<>();
//        public Date createDateTimeUtc;
//        public Date modifiedDateTimeUtc;
//        public List<CustomField> customFields = new ArrayList<>();
//        public List<CustomCollection> customCollections = new ArrayList<>();


    @Override
    public String toString() {
      return "ContactIndividual{" +
          "id=" + id +
          ", contactId=" + contactId +
          ", firstName='" + firstName + '\'' +
          ", lastName='" + lastName + '\'' +
          ", isPrimary=" + isPrimary +
          ", isSecondary=" + isSecondary +
          ", isDeceased=" + isDeceased +
          ", contactMethods=" + contactMethods +
          '}';
    }
  }

  // TODO: use 1 entity with merged fields?
  // TODO: find a better name
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactIndividualShort {
    //        public Integer individualId;
    public String name;
    public Integer id;
    //        public String contactType;
//        public String contactName;
    public String address;
    public String email;
    public String phone;
//        public String contactViewUrl;


    @Override
    public String toString() {
      return "ContactIndividualShort{" +
          "name='" + name + '\'' +
          ", id=" + id +
          ", address='" + address + '\'' +
          ", email='" + email + '\'' +
          ", phone='" + phone + '\'' +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactMethod {
    public Integer id;
    public Integer contactIndividualId;
    public String type;
    public String value;
    public Boolean isOptedIn;
    public Boolean isPrimary;
    public Boolean canBePrimary;

    @Override
    public String toString() {
      return "ContactMethod{" +
          "id=" + id +
          ", contactIndividualId=" + contactIndividualId +
          ", type='" + type + '\'' +
          ", value='" + value + '\'' +
          ", isOptedIn=" + isOptedIn +
          ", isPrimary=" + isPrimary +
          ", canBePrimary=" + canBePrimary +
          '}';
    }
  }

//    @JsonIgnoreProperties(ignoreUnknown = true)
//    public static class CustomField {
//        public String name;
//        public String value;
//        public String displayName;
//    }
//
//@JsonIgnoreProperties(ignoreUnknown = true)
//    public static class CustomCollection {
//        public Integer customCollectionId;
//        public String customCollectionName;
//        public Integer collectionInstanceId;
//        public List<Field> fields = new ArrayList<>();
//    }
//
//@JsonIgnoreProperties(ignoreUnknown = true)
//    public static class Field {
//        public String name;
//        public String value;
//    }
//
//@JsonIgnoreProperties(ignoreUnknown = true)
//    public static class ContactReference {
//        public String source;
//        public String id;
//    }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactsSearchCriteria {
    public String search;

    @Override
    public String toString() {
      return "ContactsSearchCriteria{" +
          "search='" + search + '\'' +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Gift {
    public Integer id;
    //        public Integer reversedGiftId;
    public String transactionSource;
    public String transactionId;
    public String contactId;
    //        public String contactName;
//        public String contactUrl;
    public String giftType;
    //        public String giftTypeFormatted;
    public String giftDate;
    //        public String giftDateFormatted;
    public Double amount;
    //        public String amountFormatted;
//        public String batch;
//        public Integer segmentId;
    public String segment;
    //        public String segmentCode;
//        public String segmentUrl;
//        public Integer mediaOutletId;
//        public String mediaOutlet;
//        public Integer grantId;
//        public String grant;
//        public String grantUrl;
    public String notes;
    //        public String tribute;
//        public Integer tributeId;
//        public String tributeType;
//        public Integer acknowledgeeIndividualId;
//        public Date receiptDate;
//        public String receiptDateFormatted;
//        public Integer contactPassthroughId;
//        public String contactPassthroughUrl;
//        public Integer contactIndividualId;
//        public String cashAccountingCode;
//        public Integer giftAskId;
//        public Integer contactMembershipId;
//        public List<GiftDesignation> giftDesignations = new ArrayList<>();
//        public List<GiftPremium> giftPremiums = new ArrayList<>();
//        public List<PledgePayment> pledgePayments = new ArrayList<>();
//        public List<RecurringGiftPayment> recurringGiftPayments = new ArrayList<>();
    public String giftUrl;
    public Boolean isPrivate;
    public Boolean isTaxDeductible;
//        public List<CustomField> customFields = new ArrayList<>();
//        public String creditCardType;
//        public String currencyCode;
//        public String exchangeRate;
//        public String baseCurrencyCode;


    @Override
    public String toString() {
      return "Gift{" +
          "id=" + id +
          ", transactionSource='" + transactionSource + '\'' +
          ", transactionId='" + transactionId + '\'' +
          ", contactId='" + contactId + '\'' +
          ", giftType='" + giftType + '\'' +
          ", giftDate='" + giftDate + '\'' +
          ", amount=" + amount +
          ", segment='" + segment + '\'' +
          ", notes='" + notes + '\'' +
          ", giftUrl='" + giftUrl + '\'' +
          ", isPrivate=" + isPrivate +
          ", isTaxDeductible=" + isTaxDeductible +
          '}';
    }
  }

//    @JsonIgnoreProperties(ignoreUnknown = true)
//    public static class GiftDesignation {
//        public Integer id;
//        public Integer projectId;
//        public String project;
//        public String projectCode;
//        public String externalAccountingCode;
//        public String projectType;
//        public String projectLocation;
//        public String projectUrl;
//        public Double amountDesignated;
//        public String display;
//    }
//
//@JsonIgnoreProperties(ignoreUnknown = true)
//    public static class GiftPremium {
//        public Integer id;
//        public Integer premiumId;
//        public String premium;
//        public String premiumUrl;
//        public Integer quantity;
//        public String display;
//    }
//
//@JsonIgnoreProperties(ignoreUnknown = true)
//    public static class PledgePayment {
//        public Integer id;
//        public Date expectedPaymentDate;
//        public Double expectedAmount;
//        public Integer giftId;
//        public Double actualAmount;
//    }
//
//@JsonIgnoreProperties(ignoreUnknown = true)
//    public static class RecurringGiftPayment {
//        public Integer id;
//        public Gift gift;
//        public Double expectedAmount;
//        public Date expectedPaymentDate;
//        public Date dismissPaymentDate;
//        public Date fulfillPaymentDate;
//    }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class GiftTransaction {
    public String transactionSource;
    public String transactionId;
    public Contact contact;
    public String giftDate;
    //        public String cancelDate;
//        public String giftType;
    public String amount;
    //        public String currencyCode;
    public String frequency;
    //        public String recurringGiftTransactionId;
    public Boolean recurringGiftTransactionUpdate;
    //        public String pledgeFrequency;
//        public String pledgeTransactionId;
//        public String batch;
    public String notes;
    public String segment;
    //        public String mediaOutlet;
//        public String receiptDate;
//        public String receiptSegment;
//        public String cashAccountingCode;
//        public String tribute;
//        public TributeDedication tributeDedication;
    public Boolean isPrivate;
    public Boolean isTaxDeductible;
//        public String checkNumber;
//        public String creditCardType;
//        public String nonCashGiftType;
//        public String nonCashGiftDescription;
//        public String stockTickerSymbol;
//        public Integer stockNumberOfShares;
//        public String submissionUrl;
//        public List<Designation> designations = new ArrayList<>();
//        public List<Premium> premiums = new ArrayList<>();
//        public List<CustomField> customFields = new ArrayList<>();
//        public Integer contactIndividualId;
//        public Contact passthroughContact;
//        public EventAttendee eventAttendee;


    @Override
    public String toString() {
      return "GiftTransaction{" +
          "transactionSource='" + transactionSource + '\'' +
          ", transactionId='" + transactionId + '\'' +
          ", contact=" + contact +
          ", giftDate='" + giftDate + '\'' +
          ", amount='" + amount + '\'' +
          ", frequency='" + frequency + '\'' +
          ", recurringGiftTransactionUpdate=" + recurringGiftTransactionUpdate +
          ", notes='" + notes + '\'' +
          ", segment='" + segment + '\'' +
          ", isPrivate=" + isPrivate +
          ", isTaxDeductible=" + isTaxDeductible +
          '}';
    }
  }

//    @JsonIgnoreProperties(ignoreUnknown = true)
//    public static class TributeDedication {
//        public Integer tributeId;
//        public String tributeType;
//        public String tributeFirstName;
//        public String tributeLastName;
//        public String tributeCity;
//        public String tributeState;
//        public Integer acknowledgeeIndividualId;
//        public String acknowledgeeLastName;
//        public String acknowledgeeAddress;
//        public String acknowledgeeCity;
//        public String acknowledgeeState;
//        public String acknowledgeePostal;
//        public String acknowledgeeEmail;
//        public String acknowledgeePhone;
//    }
//
//@JsonIgnoreProperties(ignoreUnknown = true)
//    public static class Designation {
//        public Integer id;
//        public String name;
//        public String code;
//        public String amountDesignated;
//    }
//
//@JsonIgnoreProperties(ignoreUnknown = true)
//    public static class Premium {
//        public Integer id;
//        public String name;
//        public String code;
//        public String quantity;
//    }
//
//@JsonIgnoreProperties(ignoreUnknown = true)
//    public static class EventAttendee {
//        public Integer eventId;
//        public String eventName;
//        public Boolean invited;
//        public Boolean rsvp;
//        public Boolean rsvpResponse;
//        public Boolean attended;
//    }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactQuery {
    //        public QueryLocation queryLocation;
    public List<QueryConditionGroup> groups = new ArrayList<>();
    public String sortBy;
    public Boolean descending;

    @Override
    public String toString() {
      return "ContactQuery{" +
          "groups=" + groups +
          ", sortBy='" + sortBy + '\'' +
          ", descending=" + descending +
          '}';
    }
  }

//    @JsonIgnoreProperties(ignoreUnknown = true)
//    public static class QueryLocation {
//        public Double topLatitude;
//        public Double leftLongitude;
//        public Double bottomLatitude;
//        public Double rightLongitude;
//    }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class QueryCondition {
    public String parameter;
    public String operator;
    public String value;
    //        public String secondaryValue;
    public List<String> values = new ArrayList<>();

    @Override
    public String toString() {
      return "QueryCondition{" +
          "parameter='" + parameter + '\'' +
          ", operator='" + operator + '\'' +
          ", value='" + value + '\'' +
          ", values=" + values +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class QueryConditionGroup {
    public List<QueryCondition> conditions = new ArrayList<>();

    @Override
    public String toString() {
      return "QueryConditionGroup{" +
          "conditions=" + conditions +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactQueryResponse {
    @JsonProperty("list")
    public List<Contact> contacts = new ArrayList<>();
//        public Integer total;


    @Override
    public String toString() {
      return "ContactQueryResponse{" +
          "contacts=" + contacts +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ReversingTransaction {
    public String giftDate;
    public Integer reversedGiftId;
    public String notes;

    @Override
    public String toString() {
      return "ReversingTransaction{" +
          "giftDate='" + giftDate + '\'' +
          ", reversedGiftId=" + reversedGiftId +
          ", notes='" + notes + '\'' +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Task {
    public Integer id;
    public Type taskType;
    public String task; // TODO: rename?
    public String description;
    public String dueDateTime;
    public Integer contactId;
    public String contact;
    public String contactUrl;

    @Override
    public String toString() {
      return "Task{" +
          "id=" + id +
          ", taskType='" + taskType + '\'' +
          ", task='" + task + '\'' +
          ", description='" + description + '\'' +
          ", dueDateTime=" + dueDateTime +
          ", contactId=" + contactId +
          ", contact='" + contact + '\'' +
          ", contactUrl='" + contactUrl + '\'' +
          '}';
    }

    public enum Type {
      @JsonProperty("General")
      GENERAL,
      @JsonProperty("Call")
      CALL,
      @JsonProperty("Meeting")
      MEETING;
    }
  }
}
