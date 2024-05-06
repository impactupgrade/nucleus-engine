/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.HttpClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static com.impactupgrade.nucleus.util.HttpClient.delete;
import static com.impactupgrade.nucleus.util.HttpClient.get;
import static com.impactupgrade.nucleus.util.HttpClient.post;
import static com.impactupgrade.nucleus.util.HttpClient.put;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

public class VirtuousClient extends OAuthClient {

  private static final String VIRTUOUS_API_URL = "https://api.virtuoussoftware.com/api";
  private static final int DEFAULT_OFFSET = 0;
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAXIMUM_LIMIT = 1000;

  private final String apiKey;

  public VirtuousClient(Environment env) {
    super("virtuous", env);

    this.apiKey = env.getConfig().virtuous.secretKey;
  }

  @Override
  protected OAuthContext oAuthContext() {
    return new UsernamePasswordOAuthContext(env.getConfig().virtuous, env.getConfig().virtuous.tokenServerUrl, true);
  }

  // Contact
  public Contact createContact(Contact contact) {
    contact = post(VIRTUOUS_API_URL + "/Contact", contact, APPLICATION_JSON, headers(), Contact.class);
    if (contact != null) {
      env.logJobInfo("Created contact: {}", contact);
    }
    return contact;
  }

  public Contact getContactById(int id) {
    return getContact(VIRTUOUS_API_URL + "/Contact/" + id);
  }

  public Contact getContactByEmail(String email) throws Exception {
    return getContact(VIRTUOUS_API_URL + "/Contact/Find?email=" + email);
  }

  private Contact getContact(String contactUrl) {
    return get(contactUrl, headers(), Contact.class);
  }

  public List<Contact> queryContacts(Query query) {
    return queryContacts(query, true);
  }

  public List<Contact> queryContacts(Query query, boolean fullContact) {
    String path = "/Contact/Query";
    if (fullContact) {
      path += "/FullContact";
    }
    ContactQueryResponse response = post(VIRTUOUS_API_URL + path + "?skip=" + DEFAULT_OFFSET + "&take=" + DEFAULT_LIMIT, query, APPLICATION_JSON, headers(), ContactQueryResponse.class);
    if (response == null) {
      return Collections.emptyList();
    }
    return response.contacts;
  }

  public List<Contact> getContactsModifiedAfter(Calendar modifiedAfter) {
    QueryCondition queryCondition = new QueryCondition();
    queryCondition.parameter = "Last Modified Date";
    queryCondition.operator = "After";
    queryCondition.value = getLastModifiedDateValue(modifiedAfter);

    QueryConditionGroup group = new QueryConditionGroup();
    group.conditions = List.of(queryCondition);

    Query query = new Query();
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
    if (daysAgo == 0) {
      lastModifiedDate = "Today";
    } else if (daysAgo >= 1 && daysAgo < 30) {
      lastModifiedDate = "Yesterday"; // TODO: check if there is more accurate String (last week?)
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
      env.logJobInfo("Updated contact: {}", contact);
    }
    return contact;
  }

  public void deleteContact(int contactId) {
    put(VIRTUOUS_API_URL + "/Contact/Archive/" + contactId, "", APPLICATION_JSON, headers());
    env.logJobInfo("Deleted contact: {}", contactId);
  }

  // Contact Method
  public ContactMethod createContactMethod(ContactMethod contactMethod) {
    contactMethod = post(VIRTUOUS_API_URL + "/ContactMethod", contactMethod, APPLICATION_JSON, headers(), ContactMethod.class);
    if (contactMethod != null) {
      env.logJobInfo("Created contactMethod: {}", contactMethod);
    }
    return contactMethod;
  }

  public ContactMethod updateContactMethod(ContactMethod contactMethod) {
    contactMethod = put(VIRTUOUS_API_URL + "/ContactMethod/" + contactMethod.id, contactMethod, APPLICATION_JSON, headers(), ContactMethod.class);
    if (contactMethod != null) {
      env.logJobInfo("Updated contactMethod: {}", contactMethod);
    }
    return contactMethod;
  }

  public void deleteContactMethod(ContactMethod contactMethod) {
    delete(VIRTUOUS_API_URL + "/ContactMethod/" + contactMethod.id, headers());
    env.logJobInfo("Deleted contactMethod: {}", contactMethod.id);
  }

  public List<ContactIndividualShort> getContactIndividuals(String searchString) {
    ContactsSearchCriteria criteria = new ContactsSearchCriteria();
    criteria.search = searchString;
    ContactSearchResponse response = post(VIRTUOUS_API_URL + "/Contact/Search?skip=" + DEFAULT_OFFSET + "&take=" + DEFAULT_LIMIT, criteria, APPLICATION_JSON, headers(), ContactSearchResponse.class);
    if (response == null) {
      return Collections.emptyList();
    }
    return response.contactIndividualShorts;
  }

  public ContactIndividual updateContactIndividual(ContactIndividual contactIndividual) {
    contactIndividual = put(VIRTUOUS_API_URL + "/ContactIndividual/" + contactIndividual.id, contactIndividual, APPLICATION_JSON, headers(), ContactIndividual.class);
    if (contactIndividual != null) {
      env.logJobInfo("Updated contactIndividual: {}", contactIndividual);
    }
    return contactIndividual;
  }

  // Gift
  public Gift getGiftById(int Id) {
    String giftUrl = VIRTUOUS_API_URL + "/Gift/" + Id;
    return getGift(giftUrl);
  }

  public Gifts getGiftsByContact(int contactId) {
    String giftUrl = VIRTUOUS_API_URL + "/Gift/ByContact/" + contactId + "?take=" + MAXIMUM_LIMIT;
    return get(giftUrl, headers(), Gifts.class);
  }

  /***
   *
   * @param query
   * @param fullGift Determines if the query returns the full gift details, more efficient to not do so
   * @return
   */
  public List<Gift> queryGifts(Query query, boolean fullGift) {
    String path = "/Gift/Query";
    if (fullGift) {
      path += "/FullGift";
    }
    return getGiftQueryResults(VIRTUOUS_API_URL + path, query, 0, MAXIMUM_LIMIT);
  }

  private List<Gift> getGiftQueryResults(String url, Query query, int page, int limit) {
    int offset = page * limit;
    List<Gift> queryResults = new ArrayList<>();
    GiftQueryResponse response = post(url + "?skip=" + offset + "&take=" + limit, query, APPLICATION_JSON, headers(), GiftQueryResponse.class);
    if (response != null) {
      queryResults.addAll(response.gifts);
      if (response.gifts.size() == limit) {
        queryResults.addAll(getGiftQueryResults(url, query, page + 1, limit));
      }
    }
    return queryResults;
  }

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
      env.logJobInfo("Created gift: {}", gift);
    }
    return gift;
  }

//  // This is the recommended way to create a gift.
//  // This ensures the gift is matched using the Virtuous matching algorithms
//  // for Contacts, Recurring gifts, Designations, etc.
//  // https://docs.virtuoussoftware.com/#e4a6a1e3-71a4-44f9-bd7c-9466996befac
//  public void createGiftAsync(GiftTransaction giftTransaction) {
//    post(VIRTUOUS_API_URL + "/v2/Gift/Transaction", giftTransaction, APPLICATION_JSON, headers());
//  }

  public Gift updateGift(Gift gift) {
    gift = put(VIRTUOUS_API_URL + "/Gift" + "/" + gift.id, gift, APPLICATION_JSON, headers(), Gift.class);
    if (gift != null) {
      env.logJobInfo("Updated gift: {}", gift);
    }
    return gift;
  }

  public void deleteGift(int giftId) {
    delete(VIRTUOUS_API_URL + "/Gift/" + giftId, headers());
  }

  // TODO: Should this return ReversingTransaction? Does the API respond with ReversingTransaction or the Gift?
  public Gift createReversingTransaction(Gift gift) throws Exception {
    gift = post(VIRTUOUS_API_URL + "/Gift/ReversingTransaction", reversingTransaction(gift), APPLICATION_JSON, headers(), Gift.class);
    if (gift != null) {
      env.logJobInfo("Created reversing transaction: {}", gift);
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

  public RecurringGift createRecurringGift(RecurringGift recurringGift) {
    recurringGift = post(VIRTUOUS_API_URL + "/RecurringGift", recurringGift, APPLICATION_JSON, headers(), RecurringGift.class);
    if (recurringGift != null) {
      env.logJobInfo("Created gift: {}", recurringGift);
    }
    return recurringGift;
  }

  public RecurringGift getRecurringGiftById(int id) {
    String giftUrl = VIRTUOUS_API_URL + "/RecurringGift/" + id;
    return get(giftUrl, headers(), RecurringGift.class);
  }

  public RecurringGift updateRecurringGift(RecurringGift recurringGift) {
    recurringGift = put(VIRTUOUS_API_URL + "/RecurringGift/" + recurringGift.id, recurringGift, APPLICATION_JSON, headers(), RecurringGift.class);
    if (recurringGift != null) {
      env.logJobInfo("Updated RecurringGift: {}", recurringGift);
    }
    return recurringGift;
  }

  public RecurringGift cancelRecurringGift(int id) {
    RecurringGift recurringGift = put(VIRTUOUS_API_URL + "/RecurringGift/Cancel/" + id, "{}", APPLICATION_JSON, headers(), RecurringGift.class);
    if (recurringGift != null) {
      env.logJobInfo("Canceled RecurringGift: {}", recurringGift);
    }
    return recurringGift;
  }

  public RecurringGifts getRecurringGiftsByContact(int contactId) {
    String giftUrl = VIRTUOUS_API_URL + "/RecurringGift/ByContact/" + contactId + "?take=1000";
    return get(giftUrl, headers(), RecurringGifts.class);
  }

  public RecurringGiftPayments getRecurringGiftPayments(int recurringGiftId) {
    String giftUrl = VIRTUOUS_API_URL + "/RecurringGiftPayment/" + recurringGiftId;
    return get(giftUrl, headers(), RecurringGiftPayments.class);
  }

  public Task createTask(Task task) throws Exception {
    task = post(VIRTUOUS_API_URL + "/Task", task, APPLICATION_JSON, headers(), Task.class);
    if (task != null) {
      env.logJobInfo("Created task: {}", task);
    }
    return task;
  }

  public Segment getSegmentByCode(String segmentCode) {
    String giftUrl = VIRTUOUS_API_URL + "/Segment/Code/" + segmentCode;
    return get(giftUrl, headers(), Segment.class);
  }

  @Override
  protected HttpClient.HeaderBuilder headers() {
    // First, use the simple API key, if available.
    if (!Strings.isNullOrEmpty(apiKey)) {
      return HttpClient.HeaderBuilder.builder().authBearerToken(apiKey);
    }

    // Otherwise, assume oauth.
    // !
    // When fetching a token for a user with Two-Factor Authentication, you will receive a 202 (Accepted) response stating that a verification code is required.
    //The user will then need to enter the verification code that was sent to their phone. You will then request the token again but this time you will pass in an OTP (one-time-password) header with the verification code received
    //If the verification code and user credentials are correct, you will receive a token as seen in the Token authentication above.
    //To request a new Token after the user enters the verification code, add an OTP header:
    //curl -d "grant_type=password&username=YOUR_EMAIL&password=YOUR_PASSWORD&otp=YOUR_OTP" -X POST https://api.virtuoussoftware.com/Token  
    return super.headers();
  }

  public static class HasCustomFields {
    public List<CustomField> customFields = new ArrayList<>();

    @JsonIgnore
    protected String getValue(String fieldName) {
      if (Strings.isNullOrEmpty(fieldName)) {
        return null;
      }
      return customFields.stream().filter(f -> f.name.equalsIgnoreCase(fieldName)).findFirst().map(f -> f.value).orElse(null);
    }
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
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Contact {
    public Integer id;
    public String contactType;
    public Boolean isPrivate;
    public String name;
    public String description;
    // TODO: address is needed for GET, but contactAddresses for POST
    public Address address;
    public List<Address> contactAddresses = new ArrayList<>();
    public String lastGiftDate;
    public List<ContactIndividual> contactIndividuals = new ArrayList<>();

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
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Address {
    public Integer id;
    public String address1;
    public String address2;
    public String city;
    public String state;
    public String postal;
    public String country;
    public Boolean isPrimary;

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
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class ContactIndividual {
    public Integer id;
    public Integer contactId;
    public String firstName;
    public String lastName;
    public Boolean isPrimary;
    public Boolean isSecondary;
    public Boolean isDeceased;
    public List<ContactMethod> contactMethods = new ArrayList<>();

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
  @JsonInclude(JsonInclude.Include.NON_NULL)
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
  @JsonInclude(JsonInclude.Include.NON_NULL)
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

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
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
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Gift extends HasCustomFields {
    public Integer id;
    //        public Integer reversedGiftId;
    public String transactionSource;
    public String transactionId;
    public String contactId;
    public String giftType;
    public String giftDate;
    public String amount;
    public String segmentId;
    public String notes;
    public List<CreateRecurringGiftPayment> recurringGiftPayments = new ArrayList<>();
    public String giftUrl;
    public Boolean isPrivate;
    public Boolean isTaxDeductible;

    @JsonIgnore
    public String paymentGatewayDepositId(Environment env) {
      return getValue(env.getConfig().virtuous.fieldDefinitions.paymentGatewayDepositId);
    }

    @JsonIgnore
    public String paymentGatewayDepositDate(Environment env) {
      return getValue(env.getConfig().virtuous.fieldDefinitions.paymentGatewayDepositDate);
    }

    @JsonIgnore
    public String paymentGatewayDepositFee(Environment env) {
      return getValue(env.getConfig().virtuous.fieldDefinitions.paymentGatewayDepositFee);
    }

    @JsonIgnore
    public String paymentGatewayDepositNetAmount(Environment env) {
      return getValue(env.getConfig().virtuous.fieldDefinitions.paymentGatewayDepositNetAmount);
    }

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
          ", segmentId='" + segmentId + '\'' +
          ", notes='" + notes + '\'' +
          ", giftUrl='" + giftUrl + '\'' +
          ", isPrivate=" + isPrivate +
          ", isTaxDeductible=" + isTaxDeductible +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Gifts {
    public List<Gift> list = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class RecurringGifts {
    public List<RecurringGift> list = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class RecurringGift extends HasCustomFields {
    public Integer id;
    public Integer contactId;
    public String startDate;
    public Double amount;
    public String frequency;
    public String anticipatedEndDate;
    public String cancelDateTimeUtc;
    public Boolean automatedPayments;
    public Boolean trackPayments;
    public Boolean isPrivate;
    public String status;
    public String nextExpectedPaymentDate;

    @JsonIgnore
    public String paymentGatewayName(Environment env) {
      return getValue(env.getConfig().virtuous.fieldDefinitions.paymentGatewayName);
    }

    @JsonIgnore
    public String paymentGatewaySubscriptionId(Environment env) {
      return getValue(env.getConfig().virtuous.fieldDefinitions.paymentGatewaySubscriptionId);
    }

    @Override
    public String toString() {
      return "RecurringGift{" +
          "id=" + id +
          ", contactId=" + contactId +
          ", startDate='" + startDate + '\'' +
          ", amount=" + amount +
          ", frequency='" + frequency + '\'' +
          ", anticipatedEndDate='" + anticipatedEndDate + '\'' +
          ", cancelDateTimeUtc='" + cancelDateTimeUtc + '\'' +
          ", automatedPayments=" + automatedPayments +
          ", trackPayments=" + trackPayments +
          ", isPrivate=" + isPrivate +
          ", status='" + status + '\'' +
          ", nextExpectedPaymentDate='" + nextExpectedPaymentDate + '\'' +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class RecurringGiftPayments {
    public List<RecurringGiftPayment> list = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class RecurringGiftPayment {
    public Integer id;
    public Gift gift;
    public Double expectedAmount;
    public Date expectedPaymentDate;
    public Date fulfillPaymentDate;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class CreateRecurringGiftPayment {
    public Integer id;
    public Double amount;
    public String state;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Query {
    public List<QueryConditionGroup> groups = new ArrayList<>();
    public String sortBy;
    public Boolean descending;

    @Override
    public String toString() {
      return "Query{" +
          "groups=" + groups +
          ", sortBy='" + sortBy + '\'' +
          ", descending=" + descending +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class QueryCondition {
    public String parameter;
    public String operator;
    public String value;

    @Override
    public String toString() {
      return "QueryCondition{" +
          "parameter='" + parameter + '\'' +
          ", operator='" + operator + '\'' +
          ", value=" + value +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
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
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class ContactQueryResponse {
    @JsonProperty("list")
    public List<Contact> contacts = new ArrayList<>();
    public Integer total;

    @Override
    public String toString() {
      return "ContactQueryResponse{" +
          "contacts=" + contacts +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class GiftQueryResponse {
    @JsonProperty("list")
    public List<Gift> gifts = new ArrayList<>();
    public Integer total;

    @Override
    public String toString() {
      return "GiftQueryResponse{" +
          "contacts=" + gifts +
          '}';
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
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
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Task {
    public Integer id;
    public Type taskType;
    @JsonProperty("task")
    public String subject;
    public String description;
    public String dueDateTime;
    public Integer contactId;
    public String ownerEmail;

    @Override
    public String toString() {
      return "Task{" +
          "id=" + id +
          ", taskType=" + taskType +
          ", subject='" + subject + '\'' +
          ", description='" + description + '\'' +
          ", dueDateTime='" + dueDateTime + '\'' +
          ", contactId=" + contactId +
          ", ownerEmail='" + ownerEmail + '\'' +
          '}';
    }

    public enum Type {
      @JsonProperty("General")
      GENERAL,
      @JsonProperty("Call")
      CALL,
      @JsonProperty("Meeting")
      MEETING
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class CustomField {
    public String name;
    public String value;
    public String dataType;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Segment {
    public String id;
    public String name;
    public String code;
  }
}
