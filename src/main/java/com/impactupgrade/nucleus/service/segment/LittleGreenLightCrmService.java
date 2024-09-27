/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.util.HttpClient.get;
import static com.impactupgrade.nucleus.util.HttpClient.post;
import static com.impactupgrade.nucleus.util.HttpClient.put;
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
    Set<String> keywords = new HashSet<>();

    String phone = contactSearch.phone == null ? null : contactSearch.phone.replaceAll("[\\D]", "");

    if (!Strings.isNullOrEmpty(contactSearch.email)) {
      keywords.add(contactSearch.email);
    }
    if (!Strings.isNullOrEmpty(phone)) {
      keywords.add(phone);
    }
    if (!Strings.isNullOrEmpty(contactSearch.firstName)) {
      keywords.add(contactSearch.firstName);
    }
    if (!Strings.isNullOrEmpty(contactSearch.lastName)) {
      keywords.add(contactSearch.lastName);
    }
    if (!contactSearch.keywords.isEmpty()) {
      keywords.addAll(contactSearch.keywords);
    }

    String query = keywords.stream().map(k -> {
      k = k.trim();
      try {
        return URLEncoder.encode(k, StandardCharsets.UTF_8.toString());
      } catch (UnsupportedEncodingException e) {
        // will never happen
        return null;
      }
    }).filter(Objects::nonNull).collect(Collectors.joining("+"));

    ConstituentSearchResults constituentSearchResults = null;
    try {
      constituentSearchResults = get(LITTLEGREENLIGHT_URL + "constituents/search?search=" + query, headers(), ConstituentSearchResults.class);
    } catch (Exception e) {
//      env.logJobError("search failed", e);
    }
    if (constituentSearchResults == null) {
      return PagedResults.pagedResultsFromCurrentOffset(Collections.emptyList(), contactSearch);
    }

    for (Constituent constituent : constituentSearchResults.items) {
      if (constituent.emailIds.size() > 1) {
        List<String> emailIds = constituent.emailIds.stream().filter(id -> id != constituent.primaryEmail.id).map(Object::toString).toList();
        constituent.secondaryEmails = get(LITTLEGREENLIGHT_URL + "emails?id=" + String.join("%7C", emailIds), headers(), EmailResults.class).items;
      }
      if (constituent.phoneIds.size() > 1) {
        List<String> phoneIds = constituent.phoneIds.stream().filter(id -> id != constituent.primaryPhone.id).map(Object::toString).toList();
        constituent.secondaryPhones = get(LITTLEGREENLIGHT_URL + "phones?id=" + String.join("%7C", phoneIds), headers(), PhoneResults.class).items;
      }
    }

    // API appears to be doing SUPER forgiving fuzzy matches. If the search was by email/phone/name, verify those explicitly.
    // If it was a name search, make sure the name actually matches.
    List<Constituent> constituents = constituentSearchResults.items.stream()
        .filter(c -> Strings.isNullOrEmpty(contactSearch.email)
            || (c.primaryEmail != null && !Strings.isNullOrEmpty(c.primaryEmail.value) && c.primaryEmail.value.equalsIgnoreCase(contactSearch.email))
            || (c.secondaryEmails.stream().anyMatch(e -> e.value.equalsIgnoreCase(contactSearch.email))))
        .filter(c -> Strings.isNullOrEmpty(phone)
            || (c.primaryPhone != null && !Strings.isNullOrEmpty(c.primaryPhone.number) && c.primaryPhone.number.replaceAll("[\\D]", "").contains(phone))
            || (c.secondaryPhones.stream().anyMatch(p -> p.number.replaceAll("[\\D]", "").contains(phone))))
        .filter(c -> Strings.isNullOrEmpty(contactSearch.firstName) || contactSearch.firstName.equalsIgnoreCase(c.firstName))
        .filter(c -> Strings.isNullOrEmpty(contactSearch.lastName) || contactSearch.lastName.equalsIgnoreCase(c.lastName))
        .collect(Collectors.toList());

    List<CrmContact> crmContacts = toCrmContact(constituents);
    return PagedResults.pagedResultsFromCurrentOffset(crmContacts, contactSearch);
  }

  @Override
  public Optional<CrmDonation> getDonationByTransactionIds(List<String> transactionIds, String accountId, String contactId) {
    return getDonation(
        contactId,
        List.of("Donation", "RecurringDonationPayment"),
        env.getConfig().littleGreenLight.fieldDefinitions.paymentGatewayTransactionId,
        transactionIds
    ).map(this::toCrmDonation);
  }

  // Not able to retrieve donations purely by transactionIds -- must have the Constituent.
  @Override
  public List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds) throws Exception {
    return Collections.emptyList();
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
    constituent.householdId = crmContact.account.id == null ? null : Integer.parseInt(crmContact.account.id);

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

    if (!Strings.isNullOrEmpty(crmContact.mailingAddress.street)) {
      final Address constituentAddress = new Address();
      constituentAddress.street = crmContact.mailingAddress.street;
      constituentAddress.city = crmContact.mailingAddress.city;
      constituentAddress.state = crmContact.mailingAddress.state;
      constituentAddress.postalCode = crmContact.mailingAddress.postalCode;
      constituentAddress.country = crmContact.mailingAddress.country;
      constituent.primaryAddress = constituentAddress;
    }

    constituent = post(LITTLEGREENLIGHT_URL + "constituent", constituent, APPLICATION_JSON, headers(), Constituent.class);

    if (constituent == null) {
      return null;
    }
    env.logJobInfo("inserted constituent {}", constituent.id);
    return constituent.id + "";
  }

  @Override
  public void updateContact(CrmContact crmContact) throws Exception {
    // currently used only by custom donation forms, messaging opt in/out, and batch updates
  }

  @Override
  public String insertDonation(CrmDonation crmDonation) throws Exception {
    // Bloomerang has no notion of non-successful transactions.
    if (crmDonation.status != CrmDonation.Status.SUCCESSFUL) {
      env.logJobInfo("skipping the non-successful transaction: {}", crmDonation.transactionId);
      return null;
    }

    String date = DateTimeFormatter.ofPattern("MM/dd/yyyy").format(crmDonation.closeDate);

    Donation donation = new Donation();
    donation.accountId = Integer.parseInt(crmDonation.contact.id);
    donation.receivedAmount = crmDonation.amount;
    donation.receivedDate = date;
    donation.method = "Credit Card";

    Designation designation = new Designation();
    designation.amount = donation.receivedAmount;

    // If the transaction included Fund metadata, assume it's the FundId. Otherwise, use the org's default.
    if (!Strings.isNullOrEmpty(crmDonation.getMetadataValue(env.getConfig().metadataKeys.fund))) {
      designation.fundId = Integer.parseInt(crmDonation.getMetadataValue(env.getConfig().metadataKeys.fund));
    } else {
      designation.fundId = env.getConfig().littleGreenLight.defaultFundId;
    }

    if (crmDonation.isRecurring()) {
      designation.type = "RecurringDonationPayment";
      // This is a little odd, but it appears Bloomerang wants the ID of the *designation* within the RecurringDonation,
      // not the donation itself. So we unfortunately need to grab that from the API.
      Donation recurringDonation = getDonation(crmDonation.recurringDonation.id);
      designation.recurringDonationId = recurringDonation.designations.get(0).id;
      designation.isExtraPayment = false;
    } else {
      designation.type = "Donation";
    }

    setProperty(env.getConfig().littleGreenLight.fieldDefinitions.paymentGatewayName, crmDonation.gatewayName, designation.customFields);
    setProperty(env.getConfig().littleGreenLight.fieldDefinitions.paymentGatewayTransactionId, crmDonation.transactionId, designation.customFields);
    setProperty(env.getConfig().littleGreenLight.fieldDefinitions.paymentGatewayCustomerId, crmDonation.customerId, designation.customFields);

    donation.designations.add(designation);

    donation = post(LITTLEGREENLIGHT_URL + "transaction", donation, APPLICATION_JSON, headers(), Donation.class);

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

  protected void setProperty(String fieldKey, String value, List<JsonNode> customFields) {
    // Optional field names may not be configured in env.json, so ensure we actually have a name first...
    // Likewise, don't set a null or empty value.
    if (Strings.isNullOrEmpty(fieldKey) || value == null) {
      return;
    }
    int fieldId = Integer.parseInt(fieldKey);

    ObjectNode objectNode = mapper.createObjectNode();
    objectNode.put("FieldId", fieldId);
    objectNode.put("Value", value);
    customFields.add(objectNode);
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
    Donation recurringDonation = getDonation(id);
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

  protected Optional<Donation> getDonation(String constituentId, List<String> donationTypes,
      String customFieldKey, String customFieldValue) {
    return getDonation(constituentId, donationTypes, customFieldKey, List.of(customFieldValue));
  }

  protected Optional<Donation> getDonation(String constituentId, List<String> donationTypes,
      String customFieldKey, List<String> _customFieldValues) {
    if (Strings.isNullOrEmpty(customFieldKey)) {
      return Optional.empty();
    }

    List<String> customFieldValues = _customFieldValues.stream().filter(v -> !Strings.isNullOrEmpty(v)).collect(Collectors.toList());
    if (customFieldValues.isEmpty()) {
      return Optional.empty();
    }

    for (String donationType : donationTypes) {
      Optional<Donation> donation = getDonations(constituentId, donationType).stream().filter(d -> {
        String customFieldValue = getCustomFieldValue(d, customFieldKey);
        return customFieldValues.contains(customFieldValue);
      }).findFirst();
      if (donation.isPresent()) {
        return donation;
      }
    }

    return Optional.empty();
  }

  // type: Donation, Pledge, PledgePayment, RecurringDonation, RecurringDonationPayment
  protected List<Donation> getDonations(String crmContactId, String type) {
    // Assuming that the default page size of 50 is enough...
    return get(
        LITTLEGREENLIGHT_URL + "transactions?type=" + type + "&accountId=" + crmContactId + "&orderBy=Date&orderDirection=Desc",
        headers(),
        DonationResults.class
    ).items;
  }

  protected Donation getDonation(String donationId) {
    return get(
        LITTLEGREENLIGHT_URL + "transaction/" + donationId,
        headers(),
        Donation.class
    );
  }

  protected String getCustomFieldValue(Donation donation, String customFieldKey) {
    if (Strings.isNullOrEmpty(customFieldKey)) {
      return null;
    }
    int customFieldId = Integer.parseInt(customFieldKey);
    return donation.designations.stream().flatMap(designation -> designation.customFields.stream())
        .filter(jsonNode -> jsonNode.has("FieldId") && jsonNode.get("FieldId").asInt() == customFieldId)
        .map(jsonNode -> jsonNode.get("Value").get("Value").asText())
        .findFirst().orElse(null);
  }

  protected CrmContact toCrmContact(Constituent constituent) {
    if (constituent == null) {
      return null;
    }

    String householdId = constituent.householdId == null ? null : constituent.householdId + "";
    String primaryEmail = constituent.primaryEmail == null ? null : constituent.primaryEmail.value;
    String primaryPhone = constituent.primaryPhone == null ? null : constituent.primaryPhone.number;
    CrmAddress crmAddress = new CrmAddress();
    if (constituent.primaryAddress != null){
      crmAddress = new CrmAddress(
          constituent.primaryAddress.street,
          constituent.primaryAddress.city,
          constituent.primaryAddress.state,
          constituent.primaryAddress.postalCode,
          constituent.primaryAddress.country
      );
    }

    return new CrmContact(
        constituent.id + "",
        new CrmAccount(householdId),
        null, // description
        primaryEmail,
        Collections.emptyList(), // List<String> emailGroups,
        null, // Boolean emailBounced,
        null, // Boolean emailOptIn,
        null, // Boolean emailOptOut,
        null, // Calendar firstDonationDate,
        constituent.firstName,
        primaryPhone,
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
        "https://crm.bloomerang.co/Constituent/" + constituent.id + "/Profile",
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

    // TODO
    CrmAccount crmAccount = new CrmAccount();

    CrmContact crmContact = new CrmContact();
    crmContact.id = donation.accountId + "";

    return new CrmDonation(
        donation.id + "",
        crmAccount,
        crmContact,
        null, // CrmRecurringDonation recurringDonation,
        donation.receivedAmount,
        null, // String customerId,
        null, // ZonedDateTime depositDate,
        null, // String depositId,
        null, // String depositTransactionId,
        getCustomFieldValue(donation, env.getConfig().littleGreenLight.fieldDefinitions.paymentGatewayName),
        null, // EnvironmentConfig.PaymentEventType paymentEventType,
        null, // String paymentMethod,
        null, // String refundId,
        null, // ZonedDateTime refundDate,
        CrmDonation.Status.SUCCESSFUL, // Bloomerang has no notion of non-successful transactions.
        null,
        false, // boolean transactionCurrencyConverted,
        null, // Double transactionExchangeRate,
        null, // Double transactionFeeInDollars,
        getCustomFieldValue(donation, env.getConfig().littleGreenLight.fieldDefinitions.paymentGatewayTransactionId),
        null, // Double transactionNetAmountInDollars,
        null, // Double transactionOriginalAmountInDollars,
        null, // String transactionOriginalCurrency,
        null, // String transactionSecondaryId,
        null, // String transactionUrl,
        null, // String campaignId,
        Utils.getZonedDateTimeFromDateTimeString(donation.receivedDate),
        null, // String description,
        null, // String name,
        null, // String ownerId,
        null, // String recordTypeId,
        donation,
        "https://crm.bloomerang.co/Constituent/" + donation.accountId + "/Transaction/Edit/" + donation.id
    );
  }

  private HttpClient.HeaderBuilder headers() {
    return HttpClient.HeaderBuilder.builder().header("X-API-KEY", apiKey);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Constituent {
    public int id;
    @JsonProperty("external_constituent_id") // TODO: work into Stripe Customer ID
    public String externalConstituentId;
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
    List<Email> phoneNumbers = new ArrayList<>();
    @JsonProperty("street_addresses")
    List<Email> streetAddresses = new ArrayList<>();

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
    public String externalConstituentId;
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
//    @JsonProperty("Method")
//    public String method = "None";
    @JsonProperty("received_date")
    public String receivedDate;
//    @JsonProperty("Designations")
//    public List<Designation> designations = new ArrayList<>();
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
