/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.AccountSearch;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmActivity;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmCampaign;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmContactListType;
import com.impactupgrade.nucleus.model.CrmCustomField;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmNote;
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.util.HttpClient;
import com.impactupgrade.nucleus.util.Utils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
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

public class LittleGreenLightCrmService implements CrmService {

  private static final String LITTLEGREENLIGHT_URL = "https://api.littlegreenlight.com/v1/";
  private static final ObjectMapper mapper = new ObjectMapper();

  private String apiKey;
  protected Environment env;

  @Override
  public String name() { return "littlegreenlight"; }

  @Override
  public boolean isConfigured(Environment env) {
    return !Strings.isNullOrEmpty(env.getConfig().littleGreenLight.secretKey);
  }

  @Override
  public void init(Environment env) {
    this.apiKey = env.getConfig().littleGreenLight.secretKey;
    this.env = env;
  }

  @Override
  public Optional<CrmContact> getContactById(String id) throws Exception {
    Constituent constituent = get(LITTLEGREENLIGHT_URL + "constituent/" + id, headers(), Constituent.class);
    return Optional.of(toCrmContact(constituent));
  }

  @Override
  public Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception {
    //Not currently implemented
    return Optional.empty();
  }

  @Override
  public List<CrmAccount> searchAccounts(AccountSearch accountSearch) {
    return Collections.emptyList();
  }

  @Override
  public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) {
    List<String> queries = new ArrayList<>();

    String phone = contactSearch.phone == null ? null : contactSearch.phone.replaceAll("[\\D]", "");

    if (!Strings.isNullOrEmpty(contactSearch.email)) {
      // TODO
    }
    if (!Strings.isNullOrEmpty(phone)) {
      // TODO
    }
    if (!Strings.isNullOrEmpty(contactSearch.firstName)) {
      queries.add("first_name=" + contactSearch.firstName);
    }
    if (!Strings.isNullOrEmpty(contactSearch.lastName)) {
      queries.add("last_name=" + contactSearch.firstName);
    }
    if (!contactSearch.keywords.isEmpty()) {
      // TODO
    }

    String query = queries.stream().map(q -> {
      q = q.trim();
      return URLEncoder.encode(q, StandardCharsets.UTF_8);
    }).collect(Collectors.joining("&"));

    ConstituentSearchResults constituentSearchResults = null;
    try {
      constituentSearchResults = get(LITTLEGREENLIGHT_URL + "v1/constituents/search.json?" + query, headers(), ConstituentSearchResults.class);
    } catch (Exception e) {
//      env.logJobError("search failed", e);
    }
    if (constituentSearchResults == null) {
      return PagedResults.pagedResultsFromCurrentOffset(Collections.emptyList(), contactSearch);
    }

    List<CrmContact> crmContacts = toCrmContact(constituentSearchResults.items);
    return PagedResults.pagedResultsFromCurrentOffset(crmContacts, contactSearch);
  }

  @Override
  public List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds) {
    return transactionIds.stream().flatMap(transactionId ->
      get(
          LITTLEGREENLIGHT_URL + "v1/gifts/search.json?external_id=" + transactionId + "&sort=date!",
          headers(),
          DonationResults.class
      ).items.stream()
    ).map(this::toCrmDonation).toList();
  }

  // Not able to retrieve donations purely by customerId -- must have the Constituent.
  @Override
  public List<CrmDonation> getDonationsByCustomerId(String customerId) throws Exception {
    return List.of();
  }

  @Override
  public void updateDonation(CrmDonation crmDonation) throws Exception {
    // Retrieving donations by Stripe IDs are not possible.
  }

  @Override
  public String insertContact(CrmContact crmContact) throws Exception {
    Constituent constituent = new Constituent();
    constituent.firstName = crmContact.firstName;
    constituent.lastName = crmContact.lastName;

    if (!Strings.isNullOrEmpty(crmContact.email)) {
      final Email constituentEmail = new Email();
      constituentEmail.address = crmContact.email;
      constituent.emailAddresses.add(constituentEmail);
    }

    if (!Strings.isNullOrEmpty(crmContact.phoneNumberForSMS())) {
      final Phone constituentPhone = new Phone();
      constituentPhone.number = crmContact.phoneNumberForSMS();
      constituent.phoneNumbers.add(constituentPhone);
    }

    if (!Strings.isNullOrEmpty(crmContact.mailingAddress.street)) {
      final Address constituentAddress = new Address();
      constituentAddress.street = crmContact.mailingAddress.street;
      constituentAddress.city = crmContact.mailingAddress.city;
      constituentAddress.state = crmContact.mailingAddress.state;
      constituentAddress.postalCode = crmContact.mailingAddress.postalCode;
      constituentAddress.country = crmContact.mailingAddress.country;
      constituent.streetAddresses.add(constituentAddress);
    }

    constituent = post(LITTLEGREENLIGHT_URL + "v1/constituents.json", constituent, APPLICATION_JSON, headers(), Constituent.class);

    if (constituent == null) {
      return null;
    }
    env.logJobInfo("inserted constituent {}", constituent.id);
    return constituent.id + "";
  }

  @Override
  public void updateContact(CrmContact crmContact) throws Exception {
    // TODO
  }

  @Override
  public String insertDonation(CrmDonation crmDonation) throws Exception {
    // LGL has no notion of non-successful transactions.
    if (crmDonation.status != CrmDonation.Status.SUCCESSFUL) {
      env.logJobInfo("skipping the non-successful transaction: {}", crmDonation.transactionId);
      return null;
    }

    String date = DateTimeFormatter.ofPattern("MM/dd/yyyy").format(crmDonation.closeDate);

    Donation donation = new Donation();
    donation.constituentId = Integer.parseInt(crmDonation.contact.id);
    donation.receivedAmount = crmDonation.amount;
    donation.receivedDate = date;
    // TODO: payment_type_id
//    donation.method = "Credit Card";

    // If the transaction included Fund metadata, assume it's the FundId. Otherwise, use the org's default.
    if (!Strings.isNullOrEmpty(crmDonation.getMetadataValue(env.getConfig().metadataKeys.fund))) {
      donation.fundId = Integer.parseInt(crmDonation.getMetadataValue(env.getConfig().metadataKeys.fund));
    } else {
      // TODO
//      donation.fundId = env.getConfig().littleGreenLight.defaultFundId;
    }

    // TODO: gift_type_id (Gift) and gift_category_id (Donation vs Recurring)
//    donation.type = "Donation";

    // TODO: payment_type_id (Stripe or Credit Card?)
//    setProperty(env.getConfig().littleGreenLight.fieldDefinitions.paymentGatewayName, crmDonation.gatewayName, designation.customFields);
    donation.externalId = crmDonation.transactionId;

    donation = post(LITTLEGREENLIGHT_URL + "v1/constituents/" + crmDonation.contact.id + "/gifts.json", donation, APPLICATION_JSON, headers(), Donation.class);

    if (donation == null) {
      return null;
    }
    env.logJobInfo("inserted donation {}", donation.id);
    return donation.id + "";
  }

  @Override
  public String insertRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
    return null;
  }

  @Override
  public void refundDonation(CrmDonation crmDonation) throws Exception {
    // Retrieving donations by Stripe IDs are not possible.
  }

  @Override
  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
  }

  @Override
  public Map<String, String> getContactLists(CrmContactListType listType) throws Exception {
    return Collections.emptyMap();
  }

  @Override
  public Map<String, String> getFieldOptions(String object) throws Exception {
    return Collections.emptyMap();
  }

  @Override
  public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
    return this.env.getConfig().littleGreenLight.fieldDefinitions;
  }

  @Override
  public PagedResults<CrmContact> getEmailContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return new PagedResults<>();
  }

  @Override
  public PagedResults<CrmAccount> getEmailAccounts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return new PagedResults<>();
  }

  @Override
  public PagedResults<CrmContact> getSmsContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return new PagedResults<>();
  }

  @Override
  public double getDonationsTotal(String filter) throws Exception {
    return 0.0;
  }

  @Override
  public Optional<CrmAccount> getAccountById(String id) throws Exception {
    return Optional.empty();
  }

  @Override
  public List<CrmAccount> getAccountsByEmails(List<String> emails) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public String insertAccount(CrmAccount crmAccount) throws Exception {
    return null;
  }

  @Override
  public void updateAccount(CrmAccount crmAccount) throws Exception {
  }

  @Override
  public void deleteAccount(String accountId) throws Exception {
  }

  @Override
  public void addAccountToCampaign(CrmAccount crmAccount, String campaignId, String status) throws Exception {
  }

  @Override
  public void addContactToCampaign(CrmContact crmContact, String campaignId, String status) throws Exception {
  }

  @Override
  public List<CrmContact> getContactsFromList(String listId) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public void addContactToList(CrmContact crmContact, String listId) throws Exception {
  }

  @Override
  public void removeContactFromList(CrmContact crmContact, String listId) throws Exception {
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId) throws Exception {
    return Optional.empty();
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId, String accountId, String contactId) throws Exception {
    return Optional.empty();
  }

  @Override
  public List<CrmRecurringDonation> searchAllRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception{
    return Collections.emptyList();
  }
  @Override
  public void insertDonationDeposit(List<CrmDonation> crmDonations) throws Exception {
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    return Optional.empty();
  }

  @Override
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
  }

  @Override
  public void closeRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
  }

  @Override
  public String insertOpportunity(CrmOpportunity crmOpportunity) throws Exception {
    return null;
  }

  @Override
  public String insertCampaign(CrmCampaign crmCampaign) throws Exception {
    return null;
  }

  @Override
  public void updateCampaign(CrmCampaign crmCampaign) throws Exception {
  }

  @Override
  public Optional<CrmCampaign> getCampaignByExternalReference(String externalReference) throws Exception {
    return Optional.empty();
  }

  @Override
  public void deleteCampaign(String campaignId) throws Exception {
  }

  @Override
  public Map<String, List<String>> getContactCampaignsByContactIds(List<String> contactIds,
      EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return Collections.emptyMap();
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
  public PagedResults.ResultSet<CrmContact> queryMoreContacts(String queryLocator) throws Exception {
    return null;
  }

  @Override
  public PagedResults.ResultSet<CrmAccount> queryMoreAccounts(String queryLocator) throws Exception {
    return null;
  }

  @Override
  public void batchInsertActivity(CrmActivity crmActivity) throws Exception {
  }

  @Override
  public void batchUpdateActivity(CrmActivity crmActivity) throws Exception {
  }

  @Override
  public List<CrmActivity> getActivitiesByExternalRefs(List<String> externalRefs) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public String insertNote(CrmNote crmNote) throws Exception {
    return null;
  }

  @Override
  public List<CrmCustomField> insertCustomFields(List<CrmCustomField> crmCustomFields) {
    return null;
  }

  protected CrmContact toCrmContact(Constituent constituent) {
    if (constituent == null) {
      return null;
    }

    Optional<String> primaryEmail = constituent.emailAddresses.stream()
        .filter(e -> e.isPreferred != null && e.isPreferred).findFirst().map(e -> e.address);
    if (primaryEmail.isEmpty()) {
      primaryEmail = constituent.emailAddresses.stream().findFirst().map(e -> e.address);
    }
    Optional<String> primaryPhone = constituent.phoneNumbers.stream()
        .filter(e -> e.isPreferred != null && e.isPreferred).findFirst().map(e -> e.number);
    if (primaryPhone.isEmpty()) {
      primaryPhone = constituent.phoneNumbers.stream().findFirst().map(e -> e.number);
    }
    Optional<Address> primaryAddress = constituent.streetAddresses.stream()
        .filter(e -> e.isPreferred != null && e.isPreferred).findFirst();
    if (primaryAddress.isEmpty()) {
      primaryAddress = constituent.streetAddresses.stream().findFirst();
    }
    CrmAddress crmAddress = new CrmAddress();
    if (primaryAddress.isPresent()){
      crmAddress = new CrmAddress(
          primaryAddress.get().street,
          primaryAddress.get().city,
          primaryAddress.get().state,
          primaryAddress.get().postalCode,
          primaryAddress.get().country
      );
    }

    return new CrmContact(
        constituent.id + "",
        null, // account
        null, // description
        primaryEmail.orElse(null),
        Collections.emptyList(), // List<String> emailGroups,
        null, // Boolean emailBounced,
        null, // Boolean emailOptIn,
        null, // Boolean emailOptOut,
        null, // Calendar firstDonationDate,
        constituent.firstName,
        primaryPhone.orElse(null),
        null, // Double largestDonationAmount,
        null, // Calendar lastDonationDate,
        constituent.lastName,
        null, // String language,
        crmAddress,
        null, // String mobilePhone,
        null, // Integer numDonations,
        null, // Integer numDonationsYtd
        null, // String ownerId,
        null, // String ownerName,
        null, // CrmContact.PreferredPhone preferredPhone,
        null, // Boolean smsOptIn,
        null, // Boolean smsOptOut,
        null, // String title,
        null, // Double totalDonationAmount,
        null, // Double totalDonationAmountYtd
        null, // String workPhone,
        constituent,
        "https://" + env.getConfig().littleGreenLight.subdomain + ".littlegreenlight.com/constituents/" + constituent.id,
        null // fieldFetcher
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

    CrmContact crmContact = new CrmContact();
    crmContact.id = donation.constituentId + "";

    return new CrmDonation(
        donation.id + "",
        null, // account
        crmContact,
        null, // CrmRecurringDonation recurringDonation,
        donation.receivedAmount,
        null, // String customerId,
        Utils.getZonedDateTimeFromDateTimeString(donation.depositDate),
        null, // String depositId,
        null, // String depositTransactionId,
        null, // String gatewayName // TODO
        null, // EnvironmentConfig.PaymentEventType paymentEventType,
        null, // String paymentMethod, // TODO
        null, // String refundId,
        null, // ZonedDateTime refundDate,
        CrmDonation.Status.SUCCESSFUL, // LGL has no notion of non-successful transactions.
        null, // String failureReason
        false, // boolean transactionCurrencyConverted,
        null, // Double transactionExchangeRate,
        null, // Double transactionFeeInDollars,
        donation.externalId,
        donation.depositedAmount,
        null, // Double transactionOriginalAmountInDollars,
        null, // String transactionOriginalCurrency,
        null, // String transactionSecondaryId,
        null, // String transactionUrl,
        donation.campaignId + "",
        Utils.getZonedDateTimeFromDateTimeString(donation.receivedDate),
        null, // String description,
        null, // String name,
        null, // String ownerId,
        null, // String recordTypeId,
        donation,
        "https://" + env.getConfig().littleGreenLight.subdomain + ".littlegreenlight.com/gifts/" + donation.id
    );
  }

  private HttpClient.HeaderBuilder headers() {
    return HttpClient.HeaderBuilder.builder().authBearerToken(apiKey);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Constituent {
    public int id;
    @JsonProperty("is_org") // TODO
    public Boolean isOrg;
    @JsonProperty("org_name") // TODO
    public String orgName;
    @JsonProperty("first_name")
    public String firstName;
    @JsonProperty("last_name")
    public String lastName;

    @JsonProperty("email_addresses")
    List<Email> emailAddresses = new ArrayList<>();
    @JsonProperty("phone_numbers")
    List<Phone> phoneNumbers = new ArrayList<>();
    @JsonProperty("street_addresses")
    List<Address> streetAddresses = new ArrayList<>();

    /*
    TODO
      "custom_attrs": [
        {
          "id": 0,
          "classification": "",
          "name": "",
          "key": "",
          "ordinal": 0,
          "value": ""
        }
      ]
     */
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Email {
    public int id;
    // TODO: email_address_type_id, email_type_name, is_preferred
    public String address;
    @JsonProperty("is_preferred")
    public Boolean isPreferred;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class EmailResults {
    @JsonProperty("total_items")
    public int totalItems;
    public List<Email> items;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Phone {
    public int id;
    // TODO: phone_number_type_id, phone_type_name, is_preferred
    public String number;
    @JsonProperty("is_preferred")
    public Boolean isPreferred;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class PhoneResults {
    @JsonProperty("total_items")
    public int totalItems;
    public List<Phone> items;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Address {
    public int id;
    // TODO: street_address_type_id, street_type_name, is_preferred
    public String street;
    public String city;
    public String state;
    @JsonProperty("postal_code")
    public String postalCode;
    public String country;
    @JsonProperty("is_preferred")
    public Boolean isPreferred;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class ConstituentSearchResults {
    @JsonProperty("total_items")
    public int totalItems;
    public List<Constituent> items;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Donation {
    public int id;
    @JsonProperty("external_id") // TODO: work into Stripe Transaction ID
    public String externalId;
    @JsonProperty("constituent_id")
    public Integer constituentId;
    @JsonProperty("campaign_id")
    public Integer campaignId;
    @JsonProperty("fund_id") // TODO
    public Integer fundId;
    @JsonProperty("appeal_id") // TODO
    public Integer appealId;
    @JsonProperty("event_id") // TODO
    public Integer eventId;
    @JsonProperty("received_amount")
    public Double receivedAmount;
    @JsonProperty("received_date")
    public String receivedDate;
    // TODO: payment_type_id, note
    @JsonProperty("deposit_date") // TODO
    public String depositDate;
    @JsonProperty("deposited_amount") // TODO
    public Double depositedAmount;

    /*
    TODO
        "custom_fields": [
          {
            "id": 0,
            "item_type": "",
            "name": "",
            "key": "",
            "facet_type": "",
            "ordinal": 0,
            "removable": true,
            "editable": true,
            "values": [
              {
                "category_id": 0,
                "name": "",
                "description": "",
                "short_code": "",
                "ordinal": 0,
                "removable": true,
                "can_change": true,
                "can_select": true
              }
            ]
          }
        ],
     */
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class DonationResults {
    @JsonProperty("total_items")
    public int totalItems;
    public List<Donation> items;
  }
}
