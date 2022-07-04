/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.OpportunityEvent;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.util.HttpClient.get;
import static com.impactupgrade.nucleus.util.HttpClient.post;
import static com.impactupgrade.nucleus.util.HttpClient.put;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

public class BloomerangCrmService implements CrmService {

  private static final Logger log = LogManager.getLogger(BloomerangCrmService.class);

  private static final String BLOOMERANG_URL = "https://api.bloomerang.co/v2/";

  private String apiKey;
  protected Environment env;

  @Override
  public String name() { return "bloomerang"; }

  @Override
  public boolean isConfigured(Environment env) {
    return !Strings.isNullOrEmpty(env.getConfig().bloomerang.secretKey);
  }

  @Override
  public void init(Environment env) {
    this.apiKey = env.getConfig().bloomerang.secretKey;
    this.env = env;
  }

  @Override
  public Optional<CrmContact> getContactById(String id) throws Exception {
    Constituent constituent = get(BLOOMERANG_URL + "constituent/" + id, headers(), Constituent.class);
    return Optional.of(toCrmContact(constituent));
  }

  @Override
  public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) {
    List<String> keywords = new ArrayList<>();

    if (!Strings.isNullOrEmpty(contactSearch.email)) {
      keywords.add(contactSearch.email);
    } else if (!Strings.isNullOrEmpty(contactSearch.phone)) {
      // TODO: might need encoded
      keywords.add(contactSearch.phone);
    } else if (!Strings.isNullOrEmpty(contactSearch.keywords)) {
      keywords.add(contactSearch.keywords);
    }

    ConstituentSearchResults constituentSearchResults = null;
    try {
      constituentSearchResults = get(BLOOMERANG_URL + "constituents/search?search=" + String.join("+", keywords), headers(), ConstituentSearchResults.class);
    } catch (Exception e) {
      // do nothing
    }
    if (constituentSearchResults == null) {
      return PagedResults.getPagedResultsFromCurrentOffset(Collections.emptyList(), contactSearch);
    }

    // TODO: API appears to be doing SUPER forgiving fuzzy matches. We originally handled email-only searches and did
    //  something like this to ensure exact matches. Now that we're also doing phone and keywords, need to rethink
    //  it. But let's see how the above works in practice...
//    List<Constituent> constituents = constituentSearchResults.results.stream()
//        .filter(c -> c.primaryEmail != null && contactSearch.email.equalsIgnoreCase(c.primaryEmail.value))
//        .collect(Collectors.toList());

    List<CrmContact> crmContacts = toCrmContact(constituentSearchResults.results);
    return PagedResults.getPagedResultsFromCurrentOffset(crmContacts, contactSearch);
  }

  @Override
  public List<CrmDonation> getDonationsByAccountId(String accountId) throws Exception {
    // Doesn't appear possible? But I believe this was mainly for Donor Portal.
    return Collections.emptyList();
  }

  // TODO: Similar issue as getRecurringDonationBySubscriptionId.
  //  Try this and refactor upstream to remove the need for getDonationByTransactionId?
  @Override
  public Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
    // Retrieving donations by Stripe IDs are not possible.
    return Optional.empty();
  }

  // TODO: refundId support?
  @Override
  public Optional<CrmDonation> getDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return getDonation(
        paymentGatewayEvent,
        "Donation",
        env.getConfig().bloomerang.fieldDefinitions.paymentGatewayTransactionId,
        List.of(paymentGatewayEvent.getTransactionId(), paymentGatewayEvent.getTransactionSecondaryId())
    ).map(this::toCrmDonation);
  }

  @Override
  public void insertDonationReattempt(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // Retrieving donations by Stripe IDs are not possible.
  }

  @Override
  public String insertContact(CrmContact crmContact) throws Exception {
    Constituent constituent = new Constituent();
    constituent.firstName = crmContact.firstName;
    constituent.lastName = crmContact.lastName;
    constituent.householdId = crmContact.accountId == null ? null : Integer.parseInt(crmContact.accountId);

    if (!Strings.isNullOrEmpty(crmContact.email)) {
      final Email constituentEmail = new Email();
      constituentEmail.value = crmContact.email;
      constituent.primaryEmail = constituentEmail;
    }

    if (!Strings.isNullOrEmpty(crmContact.phoneNumberForSMS())) {
      final Phone constituentPhone = new Phone();
      constituentPhone.number = crmContact.phoneNumberForSMS();
      constituent.primaryPhone = constituentPhone;
    }

    if (!Strings.isNullOrEmpty(crmContact.address.street)) {
      final Address constituentAddress = new Address();
      constituentAddress.street = crmContact.address.street;
      constituentAddress.city = crmContact.address.city;
      constituentAddress.state = crmContact.address.state;
      constituentAddress.postalCode = crmContact.address.postalCode;
      constituentAddress.country = crmContact.address.country;
      constituent.primaryAddress = constituentAddress;
    }

    constituent = post(BLOOMERANG_URL + "constituent", constituent, APPLICATION_JSON, headers(), Constituent.class);

    if (constituent == null) {
      return null;
    }
    log.info("inserted constituent {}", constituent.id);
    return constituent.id + "";
  }

  @Override
  public void updateContact(CrmContact crmContact) throws Exception {
    // currently used only by custom donation forms, messaging opt in/out, and batch updates
  }

  @Override
  public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // Bloomerang has no notion of non-successful transactions.
    if (!paymentGatewayEvent.isTransactionSuccess()) {
      log.info("skipping the non-successful transaction: {}", paymentGatewayEvent.getTransactionId());
      return null;
    }

    Donation donation = new Donation();
    donation.accountId = Integer.parseInt(paymentGatewayEvent.getCrmContact().id);
    donation.amount = paymentGatewayEvent.getTransactionAmountInDollars();
    donation.date = new SimpleDateFormat("MM/dd/yyyy").format(Calendar.getInstance().getTime());

    Designation designation = new Designation();
    designation.amount = donation.amount;
    // If the transaction included Fund metadata, assume it's the FundId. Otherwise, use the org's default.
    if (!Strings.isNullOrEmpty(paymentGatewayEvent.getMetadataValue(env.getConfig().metadataKeys.fund))) {
      designation.fundId = Integer.parseInt(paymentGatewayEvent.getMetadataValue(env.getConfig().metadataKeys.fund));
    } else {
      designation.fundId = env.getConfig().bloomerang.defaultFundId;
    }
    if (paymentGatewayEvent.isTransactionRecurring()) {
      if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmRecurringDonationId())) {
        designation.type = "RecurringDonationPayment";
        designation.recurringDonationId = Integer.parseInt(paymentGatewayEvent.getCrmRecurringDonationId());
      } else {
        designation.type = "RecurringDonation";
      }
    } else {
      designation.type = "Donation";
    }
    donation.designations.add(designation);

    donation = post(BLOOMERANG_URL + "transaction", donation, APPLICATION_JSON, headers(), Donation.class);

    if (donation == null) {
      return null;
    }
    log.info("inserted donation {}", donation.id);
    return donation.id + "";
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // Bloomerang has no separate endpoint to create schedules, instead requiring you to create one within the
    // initial payment. We instead let the above insertDonation handle this entirely.
    return null;
  }

  @Override
  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // Retrieving donations by Stripe IDs are not possible.
  }

  @Override
  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
    throw new RuntimeException("not implemented");
  }

  @Override
  public void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception {
    throw new RuntimeException("not implemented");
  }

  @Override
  public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
    return this.env.getConfig().bloomerang.fieldDefinitions;
  }

  @Override
  public List<CrmContact> getEmailContacts(Calendar updatedSince, String filter) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public double getDonationsTotal(String filter) throws Exception {
    throw new RuntimeException("not implemented");
  }

  @Override
  public Optional<CrmAccount> getAccountById(String id) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmAccount> getAccountByCustomerId(String customerId) throws Exception {
    return Optional.empty();
  }

  @Override
  public String insertAccount(CrmAccount crmAccount) throws Exception {
    // For now, holding back on households. The odd part is Bloomerang only does this for true households, while
    // businesses are instead treated as a *constituent*.
    return null;

//    Household household = new Household();
//    household.fullName = crmAccount.name;
//    String body = mapper.writeValueAsString(household);
//
//    household = mapper.readValue(post(BLOOMERANG_URL + "household", body), Household.class);
//
//    log.info("inserted household {}", household.id);
//
//    return household.id + "";
  }

  @Override
  public void updateAccount(CrmAccount crmAccount) throws Exception {
    // For now, holding back on households.
  }

  @Override
  public void deleteAccount(String accountId) throws Exception {
    // For now, holding back on households.
  }

  @Override
  public void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception {
    // Unlikely to be relevant for Bloomerang.
  }

  @Override
  public List<CrmContact> getContactsFromList(String listId) throws Exception {
    // SMS mass blast
    return Collections.emptyList();
  }

  @Override
  public void addContactToList(CrmContact crmContact, String listId) throws Exception {
    // SMS signups
  }

  @Override
  public void removeContactFromList(CrmContact crmContact, String listId) throws Exception {
    // SMS opt out
  }

  // TODO: Refactoring idea: We don't need this since we override both the PaymentGatewayEvent and ManageDonationEvent
  //  flavors. This method is purely used in the default interface. Refactor further upstream?
  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    return getDonation(
        paymentGatewayEvent,
        "RecurringDonation",
        env.getConfig().bloomerang.fieldDefinitions.paymentGatewaySubscriptionId,
        List.of(paymentGatewayEvent.getSubscriptionId())
    ).map(this::toCrmRecurringDonation);
  }

  @Override
  public List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception {
    // Only used by ITs and we're currently skipping households.
    return Collections.emptyList();
  }

  @Override
  public List<CrmRecurringDonation> searchOpenRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception {
    ContactSearch contactSearch = new ContactSearch();
    contactSearch.email = email.orElse(null);
    contactSearch.phone = phone.orElse(null);
    contactSearch.keywords = name.orElse(null);
    // TODO: page them?
    PagedResults<CrmContact> contacts = searchContacts(contactSearch);

    List<CrmRecurringDonation> rds = new ArrayList<>();
    for (CrmContact contact : contacts.getResults()) {
      rds.addAll(get(
          BLOOMERANG_URL + "transactions?type=RecurringDonation&accountId=" + contact.id + "&orderBy=Date&orderDirection=Desc",
          headers(),
          DonationResults.class
      ).results.stream().map(this::toCrmRecurringDonation).collect(Collectors.toList()));
    }

    return rds;
  }

  @Override
  public void insertDonationDeposit(List<PaymentGatewayEvent> paymentGatewayEvents) throws Exception {
    // currently no deposit management
  }

  @Override
  public void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<Donation> rd = getDonation(
        paymentGatewayEvent,
        "RecurringDonation",
        env.getConfig().bloomerang.fieldDefinitions.paymentGatewaySubscriptionId,
        List.of(paymentGatewayEvent.getSubscriptionId())
    );

    if (rd.isPresent()) {
      closeRecurringDonation(rd.get().id);
    } else {
      log.warn("could not find RecurringDonation for subscription {}", paymentGatewayEvent.getSubscriptionId());
    }
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Donation recurringDonation = getDonation(manageDonationEvent.getDonationId());
    return Optional.ofNullable(toCrmRecurringDonation(recurringDonation));
  }

  @Override
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Donation recurringDonation = getDonation(manageDonationEvent.getDonationId());
    if (recurringDonation == null) {
      log.warn("unable to find recurring donation using donationId {}", manageDonationEvent.getDonationId());
      return;
    }

    if (manageDonationEvent.getAmount() != null && manageDonationEvent.getAmount() > 0) {
      recurringDonation.amount = manageDonationEvent.getAmount();
      log.info("Updating amount to {}...", manageDonationEvent.getAmount());
    }
    if (manageDonationEvent.getNextPaymentDate() != null) {
      // TODO: RecurringDonationNextInstallmentDate
    }

    if (manageDonationEvent.getPauseDonation() == true) {
      recurringDonation.recurringDonationStatus = "Closed";
    }

    if (manageDonationEvent.getResumeDonation() == true) {
      recurringDonation.recurringDonationStatus = "Active";
    }

    put(BLOOMERANG_URL + "transaction/" + recurringDonation.id, recurringDonation, APPLICATION_JSON, headers(), Donation.class);
  }

  @Override
  public void closeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    closeRecurringDonation(Integer.parseInt(manageDonationEvent.getDonationId()));
  }

  @Override
  public String insertOpportunity(OpportunityEvent opportunityEvent) throws Exception {
    // bulk imports
    return null;
  }

  @Override
  public Map<String, List<String>> getActiveCampaignsByContactIds(List<String> contactIds) throws Exception {
    // Unlikely to be relevant for Bloomerang.
    return Collections.emptyMap();
  }

  @Override
  public Optional<CrmUser> getUserById(String id) throws Exception {
    // Unlikely to be relevant for Bloomerang.
    return Optional.empty();
  }

  @Override
  public Optional<CrmUser> getUserByEmail(String email) throws Exception {
    // Unlikely to be relevant for Bloomerang.
    return Optional.empty();
  }

  @Override
  public String insertTask(CrmTask crmTask) throws Exception {
    // Unlikely to be relevant for Bloomerang.
    return null;
  }

  protected Optional<Donation> getDonation(PaymentGatewayEvent paymentGatewayEvent, String donationType,
      String customFieldKey, List<String> _customFieldValues) {
    if (Strings.isNullOrEmpty(customFieldKey)) {
      return Optional.empty();
    }
    int customFieldId = Integer.parseInt(customFieldKey);

    List<String> customFieldValues = _customFieldValues.stream().filter(v -> !Strings.isNullOrEmpty(v)).collect(Collectors.toList());
    if (customFieldValues.isEmpty()) {
      return Optional.empty();
    }

    return getDonations(paymentGatewayEvent.getCrmContact().id, donationType).stream().filter(d -> {
      String customFieldValue = d.customFields.stream().filter(f -> f.fieldId == customFieldId).map(f -> (String) f.value.value).findFirst().orElse(null);
      return customFieldValues.contains(customFieldValue);
    }).findFirst();
  }

  // type: Donation, Pledge, PledgePayment, RecurringDonation, RecurringDonationPayment
  protected List<Donation> getDonations(String crmContactId, String type) {
    // Assuming that the default page size of 50 is enough...
    return get(
        BLOOMERANG_URL + "transactions?type=" + type + "&accountId=" + crmContactId + "&orderBy=Date&orderDirection=Desc",
        headers(),
        DonationResults.class
    ).results;
  }

  protected Donation getDonation(String donationId) {
    return get(
        BLOOMERANG_URL + "transaction/" + donationId,
        headers(),
        Donation.class
    );
  }

  protected String getCustomFieldValue(Donation donation, String customFieldKey) {
    if (Strings.isNullOrEmpty(customFieldKey)) {
      return null;
    }
    int customFieldId = Integer.parseInt(customFieldKey);
    return donation.customFields.stream().filter(f -> f.fieldId == customFieldId).map(f -> (String) f.value.value).findFirst().orElse(null);
  }

  protected void closeRecurringDonation(int donationId) throws Exception {
    Donation recurringDonation = new Donation();
    recurringDonation.id = donationId;
    recurringDonation.recurringDonationStatus = "Closed";
    put(BLOOMERANG_URL + "transaction/" + recurringDonation.id, recurringDonation, APPLICATION_JSON, headers(), Donation.class);
  }

  protected CrmContact toCrmContact(Constituent constituent) {
    if (constituent == null) {
      return null;
    }

    String householdId = constituent.householdId == null ? null : constituent.householdId + "";
    String primaryEmail = constituent.primaryEmail == null ? null : constituent.primaryEmail.value;
    String primaryPhone = constituent.primaryPhone == null ? null : constituent.primaryPhone.number;
    CrmAddress address = constituent.primaryAddress == null ? null : new CrmAddress(
        constituent.primaryAddress.street,
        constituent.primaryAddress.city,
        constituent.primaryAddress.state,
        constituent.primaryAddress.postalCode,
        constituent.primaryAddress.country
    );

    return new CrmContact(
        constituent.id + "",
        householdId,
        constituent.firstName,
        constituent.lastName,
        // TODO: See below note on Contact.FullName
//        constituent.fullName,
        constituent.firstName + " " + constituent.lastName,
        primaryEmail,
        primaryPhone, // home phone
        null, null, null, null, // other phone fields
        address,
        null, null, null, null, // opt in/out
        null, null, // owner
        null, null, null, null, // donation metrics
        null, // emailGroups
        null, // contactLanguage
        constituent,
        "https://crm.bloomerang.co/Constituent/" + constituent.id + "/Profile"
    );
  }

  protected List<CrmContact> toCrmContact(List<Constituent> constituents) {
    if (constituents == null) {
      return Collections.emptyList();
    }
    return constituents.stream().map(this::toCrmContact).collect(Collectors.toList());
  }

  protected CrmDonation toCrmDonation(Donation donation) {
    if (donation == null) {
      return null;
    }

    Calendar c = Calendar.getInstance();
    try {
      c.setTime(new SimpleDateFormat("yyyy-MM-dd").parse(donation.date));
    } catch (ParseException e) {
      log.error("unparseable date: {}", donation.date, e);
    }

    // TODO
    CrmAccount crmAccount = new CrmAccount();

    CrmContact crmContact = new CrmContact();
    crmContact.id = donation.accountId + "";

    return new CrmDonation(
        donation.id + "",
        null, // name
        donation.amount,
        getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewayName),
        getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewayTransactionId),
        CrmDonation.Status.SUCCESSFUL, // Bloomerang has no notion of non-successful transactions.
        c,
        crmAccount,
        crmContact,
        donation,
        "https://crm.bloomerang.co/Constituent/" + donation.accountId + "/Transaction/Edit/" + donation.id
    );
  }

  protected CrmRecurringDonation toCrmRecurringDonation(Donation donation) {
    if (donation == null) {
      return null;
    }

    // TODO
    CrmAccount crmAccount = new CrmAccount();

    CrmContact crmContact = new CrmContact();
    crmContact.id = donation.accountId + "";

    return new CrmRecurringDonation(
        donation.id + "",
        getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewaySubscriptionId),
        getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewayCustomerId),
        donation.amount,
        getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewayName),
        "Active".equalsIgnoreCase(donation.recurringDonationStatus),
        CrmRecurringDonation.Frequency.fromName(donation.recurringDonationFrequency),
        null, // name
        crmAccount,
        crmContact,
        donation,
        "https://crm.bloomerang.co/Constituent/" + donation.accountId + "/Transaction/Edit/" + donation.id
    );
  }

  private HttpClient.HeaderBuilder headers() {
    return HttpClient.HeaderBuilder.builder().header("X-API-KEY", apiKey);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Household {
    @JsonProperty("Id")
    public int id;
    @JsonProperty("FullName")
    public String fullName;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Constituent {
    @JsonProperty("Id")
    public int id;
    @JsonProperty("Type")
    public String type = "Individual";
    @JsonProperty("FirstName")
    public String firstName;
    @JsonProperty("LastName")
    public String lastName;
    // TODO: Annoying issue. We may need to set this for Organization constituents. But the API won't let you set this
    //  for Individuals. Makes sense, but it's giving that same error even when this is null. We might need to extend the class...
//    @JsonProperty("FullName")
//    public String fullName;
    @JsonProperty("HouseholdId")
    public Integer householdId;
    @JsonProperty("PrimaryEmail")
    public Email primaryEmail;
    @JsonProperty("PrimaryPhone")
    public Phone primaryPhone;
    @JsonProperty("PrimaryAddress")
    public Address primaryAddress;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Email {
    @JsonProperty("Type")
    public String type = "Home";
    @JsonProperty("IsPrimary")
    public boolean isPrimary = true;
    @JsonProperty("Value")
    public String value;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Phone {
    @JsonProperty("Type")
    public String type = "Home";
    @JsonProperty("IsPrimary")
    public boolean isPrimary = true;
    @JsonProperty("Number")
    public String number;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Address {
    @JsonProperty("Type")
    public String type = "Home";
    @JsonProperty("IsPrimary")
    public boolean isPrimary = true;
    @JsonProperty("Street")
    public String street;
    @JsonProperty("City")
    public String city;
    @JsonProperty("State")
    public String state;
    @JsonProperty("PostalCode")
    public String postalCode;
    @JsonProperty("Country")
    public String country;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ConstituentSearchResults {
    @JsonProperty("Total")
    public int total;
    @JsonProperty("Results")
    public List<Constituent> results;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Donation {
    @JsonProperty("Id")
    public int id;
    @JsonProperty("AccountId")
    public int accountId;
    @JsonProperty("Amount")
    public double amount;
    @JsonProperty("Method")
    public String method = "Credit Card";
    @JsonProperty("Date")
    public String date;
    @JsonProperty("Designations")
    public List<Designation> designations = new ArrayList<>();
    // Active, Closed, Overdue
    @JsonProperty("RecurringDonationStatus")
    public String recurringDonationStatus;
    @JsonProperty("RecurringDonationFrequency")
    public String recurringDonationFrequency;
    @JsonProperty("CustomValues")
    public List<CustomField> customFields = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class DonationResults {
    @JsonProperty("Total")
    public int total;
    @JsonProperty("Results")
    public List<Donation> results;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Designation {
    @JsonProperty("Amount")
    public double amount;
    @JsonProperty("NonDeductibleAmount")
    public double nonDeductibleAmount = 0.0;
    @JsonProperty("Type")
    public String type;
    @JsonProperty("FundId")
    public int fundId;
    @JsonProperty("RecurringDonationId")
    public Integer recurringDonationId;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class CustomField {
    @JsonProperty("FieldId")
    public int fieldId;
    @JsonProperty("Value")
    public CustomValue value;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class CustomValue {
    @JsonProperty("89234433")
    public int id;
    @JsonProperty("Value")
    public Object value;
  }
}
