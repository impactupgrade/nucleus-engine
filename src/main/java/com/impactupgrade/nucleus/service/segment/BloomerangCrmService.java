/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// TODO: Copies from the old C1 code. Needs cleaned up and rethought...
// TODO: If needs expand, make this into an open source client lib

public class BloomerangCrmService implements BasicCrmService {

  private static final Logger log = LogManager.getLogger(BloomerangCrmService.class);

  private static final String BLOOMERANG_URL = "https://api.bloomerang.co/v1/";
  private static final String SEARCH = BLOOMERANG_URL + "Constituent/?q=";
  private static final String POST_CONSTITUENT = BLOOMERANG_URL + "Constituent";
  private static final String POST_EMAIL = BLOOMERANG_URL + "Email";
  private static final String POST_PHONE = BLOOMERANG_URL + "Phone";
  private static final String POST_ADDRESS = BLOOMERANG_URL + "Address";
  private static final String POST_DONATION = BLOOMERANG_URL + "Donation";
  private static final String POST_RECURRINGDONATION = BLOOMERANG_URL + "RecurringDonation";

  private String apiKey;
  private String anonymousId;
  protected Environment env;
  private ObjectMapper mapper;

  @Override
  public String name() { return "bloomerang"; }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().bloomerang != null;
  }

  @Override
  public void init(Environment env) {
    this.apiKey = env.getConfig().bloomerang.secretKey;
    this.anonymousId = env.getConfig().bloomerang.anonymousId;
    this.env = env;

    mapper = new ObjectMapper();
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  @Override
  public Optional<CrmContact> getContactById(String id) throws Exception {
    // TODO
    return Optional.empty();
  }

  @Override
  public Optional<CrmContact> getContactByEmail(String email) throws Exception {
    if (Strings.isNullOrEmpty(email)) {
      // assume anonymous
      return Optional.of(new CrmContact(anonymousId));
    }

    ConstituentSearchResults constituentSearchResults = null;
    try {
      // search by email only
      constituentSearchResults = mapper.readValue(get(SEARCH + email), ConstituentSearchResults.class);
    } catch (Exception e) {
      // do nothing
    }

    if (constituentSearchResults != null && constituentSearchResults.results.length > 0
        && constituentSearchResults.results[0] != null) {
      // TODO: no accounts in Bloomerang, so this might need addressed upstream if the accountId doesn't exist
      return Optional.of(new CrmContact(constituentSearchResults.results[0].id + ""));
    } else {
      return Optional.empty();
    }
  }

  @Override
  public Optional<CrmContact> getContactByPhone(String phone) throws Exception {
    throw new RuntimeException("not implemented");
  }

  @Override
  public List<CrmContact> searchContacts(String firstName, String lastName, String email, String phone, String address) {
    throw new RuntimeException("not implemented");
  }

  @Override
  public List<CrmDonation> getDonationsByAccountId(String accountId) throws Exception {
    // TODO
    return Collections.emptyList();
  }

  @Override
  public Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
    // TODO
    return Optional.empty();
  }

  @Override
  public void insertDonationReattempt(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO
  }

  @Override
  public String insertContact(CrmContact crmContact) throws Exception {
    Constituent constituent = new Constituent();
    constituent.firstName = crmContact.firstName;
    constituent.lastName = crmContact.lastName;
    // TODO
//    constituent.customFields = customFields;
    String body = mapper.writeValueAsString(constituent);

    constituent = mapper.readValue(post(POST_CONSTITUENT, body), Constituent.class);

    if (!Strings.isNullOrEmpty(crmContact.email)) {
      final Email constituentEmail = new Email();
      constituentEmail.accountId = constituent.id;
      constituentEmail.value = crmContact.email;
      body = mapper.writeValueAsString(constituentEmail);
      post(POST_EMAIL, body);
    }

    if (!Strings.isNullOrEmpty(crmContact.mobilePhone)) {
      final Phone constituentPhone = new Phone();
      constituentPhone.accountId = constituent.id;
      constituentPhone.number = crmContact.mobilePhone;
      constituentPhone.typeName = "Mobile"; // TODO: confirm this is correct
      post(POST_PHONE, mapper.writeValueAsString(constituentPhone));
    }

    if (!Strings.isNullOrEmpty(crmContact.address.street)) {
      final Address constituentAddress = new Address();
      constituentAddress.accountId = constituent.id;
      constituentAddress.street = crmContact.address.street;
      constituentAddress.city = crmContact.address.city;
      constituentAddress.state = crmContact.address.state;
      constituentAddress.zip = crmContact.address.postalCode;
      post(POST_ADDRESS, mapper.writeValueAsString(constituentAddress));
    }

    log.info("inserted constituent {}", constituent.id);

    return constituent.id + "";
  }

  @Override
  public void updateContact(CrmContact crmContact) throws Exception {
    // TODO
  }

  @Override
  public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Donation donation = new Donation();
    donation.accountId = Integer.parseInt(paymentGatewayEvent.getCrmContact().id);
    donation.amount = paymentGatewayEvent.getTransactionAmountInDollars();
    donation.date = new SimpleDateFormat("MM/dd/yyyy").format(Calendar.getInstance().getTime());

    String body = mapper.writeValueAsString(donation);
    donation = mapper.readValue(post(POST_DONATION, body), Donation.class);

    log.info("inserted donation {}", donation.id);

    return donation.id + "";
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    RecurringDonation donation = new RecurringDonation();
    donation.setAccountId(Integer.parseInt(paymentGatewayEvent.getCrmContact().id));
    donation.setAmount(paymentGatewayEvent.getSubscriptionAmountInDollars());
    donation.setDate(new SimpleDateFormat("MM/dd/yyyy").format(Calendar.getInstance().getTime()));

    String body = mapper.writeValueAsString(donation);
    donation = mapper.readValue(post(POST_RECURRINGDONATION, body), RecurringDonation.class);

    log.info("inserted recurring donation {}", donation.id);

    return donation.id + "";
  }

  @Override
  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO
  }

  @Override
  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
    // TODO
  }

  @Override
  public void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception {
    // TODO
  }

  @Override
  public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
    return new EnvironmentConfig.CRMFieldDefinitions();
  }

  @Override
  public List<CrmContact> getEmailContacts(Calendar updatedSince, String filter) throws Exception {
    // TODO
    return Collections.emptyList();
  }

  @Override
  public List<CrmContact> getEmailDonorContacts(Calendar updatedSince, String filter) throws Exception {
    // TODO
    return Collections.emptyList();
  }

  @Override
  public double getDonationsTotal(String filter) throws Exception {
    // TODO
    return 0.0;
  }

  private InputStream get(String endpoint) throws Exception {
    final HttpClient client = new DefaultHttpClient();
    final HttpGet request = new HttpGet(endpoint);
    request.addHeader("Authorization", "Basic " + apiKey);
    final HttpResponse response = client.execute(request);
    return response.getEntity().getContent();
  }

  private String post(String endpoint, String body) throws Exception {
    final HttpClient client = new DefaultHttpClient();
    final HttpPost post = new HttpPost(endpoint);
    post.addHeader("Authorization", "Basic " + apiKey);
    final StringEntity requestEntity = new StringEntity(body, "application/json", "UTF-8");
    post.setEntity(requestEntity);
    final HttpResponse response = client.execute(post);

//		System.out.println(endpoint);
//		System.out.println(body);
    StringWriter writer = new StringWriter();
    IOUtils.copy(response.getEntity().getContent(), writer);
//		System.out.println(response);
//		System.out.println(response.getStatusLine().getStatusCode());

    return writer.toString();
  }


  public static class Constituent {
    @JsonIgnore
    private int id;

    @JsonProperty("Type")
    private String type = "Individual";

    @JsonProperty("FirstName")
    private String firstName;

    @JsonProperty("LastName")
    private String lastName;

    @JsonProperty("CustomFields")
    private Map<String, String[]> customFields;

    @JsonIgnore
    public int getId() {
      return id;
    }

    @JsonProperty("Id")
    public void setId(int id) {
      this.id = id;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public String getFirstName() {
      return firstName;
    }

    public void setFirstName(String firstName) {
      this.firstName = firstName;
    }

    public String getLastName() {
      return lastName;
    }

    public void setLastName(String lastName) {
      this.lastName = lastName;
    }

    public Map<String, String[]> getCustomFields() {
      return customFields;
    }

    public void setCustomFields(Map<String, String[]> customFields) {
      this.customFields = customFields;
    }
  }

  public static class Email {
    @JsonProperty("AccountId")
    public int accountId;

    @JsonProperty("TypeName")
    public String typeName = "Home";

    @JsonProperty("Value")
    public String value;
  }

  public static class Phone {
    @JsonProperty("AccountId")
    public int accountId;

    @JsonProperty("TypeName")
    public String typeName = "Home";

    @JsonProperty("Number")
    public String number;
  }

  public static class Address {
    @JsonProperty("AccountId")
    public int accountId;

    @JsonProperty("TypeName")
    public String typeName = "Home";

    @JsonProperty("IsPrimary")
    public boolean isPrimary = true;

    @JsonProperty("Street")
    public String street;

    @JsonProperty("City")
    public String city;

    @JsonProperty("State")
    public String state;

    @JsonProperty("PostalCode")
    public String zip;
  }

  public static class ConstituentSearchResults {
    @JsonProperty("Total")
    public int total;

    @JsonProperty("Results")
    public Constituent[] results;
  }

  public static class Duplicate {
    @JsonProperty("FirstName")
    public String firstName;

    @JsonProperty("LastName")
    public String lastName;

    @JsonProperty("Street")
    public String street;

    @JsonProperty("PhoneNumber")
    public String phone;

    @JsonProperty("Email")
    public String email;
  }

  public static class Donation {
    @JsonIgnore
    private int id;

    @JsonProperty("AccountId")
    private int accountId;

    @JsonProperty("Amount")
    private double amount;

    @JsonProperty("FundName")
    private String fundName = "Online Donation";

    @JsonProperty("Date")
    private String date;

    @JsonIgnore
    public int getId() {
      return id;
    }

    @JsonProperty("Id")
    public void setId(int id) {
      this.id = id;
    }

    public int getAccountId() {
      return accountId;
    }

    public void setAccountId(int accountId) {
      this.accountId = accountId;
    }

    public double getAmount() {
      return amount;
    }

    public void setAmount(double amount) {
      this.amount = amount;
    }

    public String getFundName() {
      return fundName;
    }

    public void setFundName(String fundName) {
      this.fundName = fundName;
    }

    public String getDate() {
      return date;
    }

    public void setDate(String date) {
      this.date = date;
    }
  }

  public static class RecurringDonation extends Donation {
    @JsonIgnore
    private int id;

    @JsonProperty("Frequency")
    private String frequency = "Monthly";

    @JsonIgnore
    public int getId() {
      return id;
    }

    @JsonProperty("Id")
    public void setId(int id) {
      this.id = id;
    }

    public String getFrequency() {
      return frequency;
    }

    public void setFrequency(String frequency) {
      this.frequency = frequency;
    }
  }
}
