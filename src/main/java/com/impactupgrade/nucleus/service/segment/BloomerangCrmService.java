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
    return env.getConfig().bloomerang != null;
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
    // TODO: For now, supporting the individual use cases, but this needs reworked at the client level. Add support for
    //  combining clauses, owner, keyword search, pagination, etc.

    if (!Strings.isNullOrEmpty(contactSearch.email)) {
      ConstituentSearchResults constituentSearchResults = null;
      try {
        // search by email only
        constituentSearchResults = get(BLOOMERANG_URL + "constituents/search?search=" + contactSearch.email, headers(), ConstituentSearchResults.class);
      } catch (Exception e) {
        // do nothing
      }
      if (constituentSearchResults == null) {
        return PagedResults.getPagedResultsFromCurrentOffset(Collections.emptyList(), contactSearch);
      }
      // filter by exact email match -- API appears to be doing SUPER forgiving fuzzy matches
      List<Constituent> constituents = constituentSearchResults.results.stream()
          .filter(c -> c.primaryEmail != null && contactSearch.email.equalsIgnoreCase(c.primaryEmail.value))
          .collect(Collectors.toList());
      List<CrmContact> crmContacts = toCrmContact(constituents);
      return PagedResults.getPagedResultsFromCurrentOffset(crmContacts, contactSearch);
    } else {
      throw new RuntimeException("not implemented");
    }
  }

  @Override
  public List<CrmDonation> getDonationsByAccountId(String accountId) throws Exception {
    // Doesn't appear possible? But I believe this was mainly for Donor Portal.
    return Collections.emptyList();
  }

  @Override
  public Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
    // Retrieving donations by Stripe IDs are not possible.
    return Optional.empty();
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
    // TODO: currently used only by custom donation forms, messaging opt in/out, and batch updates
  }

  @Override
  public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
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
    // We're temporarily skipping RDs, since retrieving RDs by Stripe Subscription ID is not possible.
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
    return new EnvironmentConfig.CRMFieldDefinitions();
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
    throw new RuntimeException("not implemented");
  }

  @Override
  public List<CrmContact> getContactsFromList(String listId) throws Exception {
    throw new RuntimeException("not implemented");
  }

  @Override
  public void addContactToList(CrmContact crmContact, String listId) throws Exception {
    throw new RuntimeException("not implemented");
  }

  @Override
  public void removeContactFromList(CrmContact crmContact, String listId) throws Exception {
    throw new RuntimeException("not implemented");
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    // We're temporarily skipping RDs, since retrieving RDs by Stripe Subscription ID is not possible.
    return Optional.empty();
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId) throws Exception {
    // We're temporarily skipping RDs, since retrieving RDs by Stripe Subscription ID is not possible.
    return Optional.empty();
  }

  @Override
  public List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception {
    // We're temporarily skipping RDs, since retrieving RDs by Stripe Subscription ID is not possible.
    return Collections.emptyList();
  }

  @Override
  public List<CrmRecurringDonation> searchOpenRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception {
    // We're temporarily skipping RDs, since retrieving RDs by Stripe Subscription ID is not possible.
    return Collections.emptyList();
  }

  @Override
  public void insertDonationDeposit(List<PaymentGatewayEvent> paymentGatewayEvents) throws Exception {
    throw new RuntimeException("not implemented");
  }

  @Override
  public void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // We're temporarily skipping RDs, since retrieving RDs by Stripe Subscription ID is not possible.
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    // We're temporarily skipping RDs, since retrieving RDs by Stripe Subscription ID is not possible.
    return Optional.empty();
  }

  @Override
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    // We're temporarily skipping RDs, since retrieving RDs by Stripe Subscription ID is not possible.
  }

  @Override
  public void closeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    // We're temporarily skipping RDs, since retrieving RDs by Stripe Subscription ID is not possible.
  }

  @Override
  public String insertOpportunity(OpportunityEvent opportunityEvent) throws Exception {
    throw new RuntimeException("not implemented");
  }

  @Override
  public Map<String, List<String>> getActiveCampaignsByContactIds(List<String> contactIds) throws Exception {
    throw new RuntimeException("not implemented");
  }

  @Override
  public Optional<CrmUser> getUserById(String id) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmUser> getUserByEmail(String email) throws Exception {
    return Optional.empty();
  }

  @Override
  public String insertTask(CrmTask crmTask) throws Exception {
    return null;
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

  protected Optional<CrmContact> toCrmContact(Optional<Constituent> constituent) {
    return constituent.map(this::toCrmContact);
  }

  protected List<CrmContact> toCrmContact(List<Constituent> constituents) {
    if (constituents == null) {
      return Collections.emptyList();
    }
    return constituents.stream().map(this::toCrmContact).collect(Collectors.toList());
  }

  protected PagedResults<CrmContact> toCrmContact(PagedResults<Constituent> constituents) {
    return new PagedResults<>(constituents.getResults().stream().map(this::toCrmContact).collect(Collectors.toList()),
        constituents.getPageSize(), constituents.getNextPageToken());
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
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Designation {
    @JsonProperty("Amount")
    public double amount;
    @JsonProperty("NonDeductibleAmount")
    public double nonDeductibleAmount = 0.0;
    @JsonProperty("Type")
    public String type = "Donation";
    @JsonProperty("FundId")
    public int fundId;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class RecurringDonation extends Donation {
    @JsonProperty("Id")
    public int id;
    @JsonProperty("Frequency")
    public String frequency = "Monthly";
  }
}
