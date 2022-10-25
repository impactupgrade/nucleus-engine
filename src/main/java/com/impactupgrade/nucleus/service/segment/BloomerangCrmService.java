/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
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
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmCustomField;
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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.util.HttpClient.get;
import static com.impactupgrade.nucleus.util.HttpClient.post;
import static com.impactupgrade.nucleus.util.HttpClient.put;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

public class BloomerangCrmService implements CrmService {

  private static final Logger log = LogManager.getLogger(BloomerangCrmService.class);

  private static final String BLOOMERANG_URL = "https://api.bloomerang.co/v2/";
  private static final ObjectMapper mapper = new ObjectMapper();

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
  public Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception {
    //Not currently implemented
    return Optional.empty();
  }
  @Override
  public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) {
    List<String> keywords = new ArrayList<>();

    String phone = contactSearch.phone == null ? null : contactSearch.phone.replaceAll("[\\D]", "");

    if (!Strings.isNullOrEmpty(contactSearch.email)) {
      keywords.add(contactSearch.email);
    } else if (!Strings.isNullOrEmpty(phone)) {
      keywords.add(phone);
    } else if (!Strings.isNullOrEmpty(contactSearch.keywords)) {
      keywords.add(contactSearch.keywords);
    }

    String query = keywords.stream().map(k -> {
      try {
        return URLEncoder.encode(k, StandardCharsets.UTF_8.toString());
      } catch (UnsupportedEncodingException e) {
        // will never happen
        return null;
      }
    }).filter(Objects::nonNull).collect(Collectors.joining("+"));

    ConstituentSearchResults constituentSearchResults = null;
    try {
      constituentSearchResults = get(BLOOMERANG_URL + "constituents/search?search=" + query, headers(), ConstituentSearchResults.class);
    } catch (Exception e) {
//      log.error("search failed", e);
    }
    if (constituentSearchResults == null) {
      return PagedResults.getPagedResultsFromCurrentOffset(Collections.emptyList(), contactSearch);
    }

    // API appears to be doing SUPER forgiving fuzzy matches. If the search was by email/phone, verify those explicitly.
    // If it was a name search, make sure the name actually matches.
    List<Constituent> constituents = constituentSearchResults.results.stream()
        .filter(c -> Strings.isNullOrEmpty(contactSearch.email) || (c.primaryEmail != null && !Strings.isNullOrEmpty(c.primaryEmail.value) && c.primaryEmail.value.equalsIgnoreCase(contactSearch.email)))
        .filter(c -> Strings.isNullOrEmpty(phone) || (c.primaryPhone != null && !Strings.isNullOrEmpty(c.primaryPhone.number) && c.primaryPhone.number.replaceAll("[\\D]", "").contains(phone)))
        .collect(Collectors.toList());

    List<CrmContact> crmContacts = toCrmContact(constituents);
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
    List<String> transactionIds = new ArrayList<>();
    transactionIds.add(paymentGatewayEvent.getTransactionId());
    if (!Strings.isNullOrEmpty(paymentGatewayEvent.getTransactionSecondaryId())) {
      // Sometimes null, so don't blindly add it without first checking.
      transactionIds.add(paymentGatewayEvent.getTransactionSecondaryId());
    }

    return getDonation(
        paymentGatewayEvent,
        List.of("Donation", "RecurringDonationPayment"),
        env.getConfig().bloomerang.fieldDefinitions.paymentGatewayTransactionId,
        transactionIds
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

    String date = DateTimeFormatter.ofPattern("MM/dd/yyyy").format(paymentGatewayEvent.getTransactionDate());

    Donation donation = new Donation();
    donation.accountId = Integer.parseInt(paymentGatewayEvent.getCrmContact().id);
    donation.amount = paymentGatewayEvent.getTransactionAmountInDollars();
    donation.date = date;
    donation.method = "Credit Card";

    Designation designation = new Designation();
    designation.amount = donation.amount;

    // If the transaction included Fund metadata, assume it's the FundId. Otherwise, use the org's default.
    if (!Strings.isNullOrEmpty(paymentGatewayEvent.getMetadataValue(env.getConfig().metadataKeys.fund))) {
      designation.fundId = Integer.parseInt(paymentGatewayEvent.getMetadataValue(env.getConfig().metadataKeys.fund));
    } else {
      designation.fundId = env.getConfig().bloomerang.defaultFundId;
    }

    if (paymentGatewayEvent.isTransactionRecurring()) {
      designation.type = "RecurringDonationPayment";
      // This is a little odd, but it appears Bloomerang wants the ID of the *designation* within the RecurringDonation,
      // not the donation itself. So we unfortunately need to grab that from the API.
      Donation recurringDonation = getDonation(paymentGatewayEvent.getCrmRecurringDonationId());
      designation.recurringDonationId = recurringDonation.designations.get(0).id;
      designation.isExtraPayment = false;
    } else {
      designation.type = "Donation";
    }

    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewayName, paymentGatewayEvent.getGatewayName(), designation.customFields);
    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewayTransactionId, paymentGatewayEvent.getTransactionId(), designation.customFields);
    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewayCustomerId, paymentGatewayEvent.getCustomerId(), designation.customFields);

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
    if (!paymentGatewayEvent.isTransactionSuccess()) {
      log.info("skipping the non-successful transaction: {}", paymentGatewayEvent.getTransactionId());
      return null;
    }

    String date = DateTimeFormatter.ofPattern("MM/dd/yyyy").format(paymentGatewayEvent.getTransactionDate());

    Donation donation = new Donation();
    donation.accountId = Integer.parseInt(paymentGatewayEvent.getCrmContact().id);
    donation.amount = paymentGatewayEvent.getTransactionAmountInDollars();
    donation.date = date;

    Designation designation = new Designation();
    designation.amount = donation.amount;
    // If the transaction included Fund metadata, assume it's the FundId. Otherwise, use the org's default.
    if (!Strings.isNullOrEmpty(paymentGatewayEvent.getMetadataValue(env.getConfig().metadataKeys.fund))) {
      designation.fundId = Integer.parseInt(paymentGatewayEvent.getMetadataValue(env.getConfig().metadataKeys.fund));
    } else {
      designation.fundId = env.getConfig().bloomerang.defaultFundId;
    }
    designation.type = "RecurringDonation";
    designation.recurringDonationStatus = "Active";
    designation.recurringDonationStartDate = date;
    CrmRecurringDonation.Frequency frequency = CrmRecurringDonation.Frequency.fromName(paymentGatewayEvent.getSubscriptionInterval());
    designation.recurringDonationFrequency = frequency.name();

    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewayName, paymentGatewayEvent.getGatewayName(), designation.customFields);
    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewaySubscriptionId, paymentGatewayEvent.getSubscriptionId(), designation.customFields);
    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewayCustomerId, paymentGatewayEvent.getCustomerId(), designation.customFields);

    donation.designations.add(designation);

    log.info(mapper.writeValueAsString(donation));
    donation = post(BLOOMERANG_URL + "transaction", donation, APPLICATION_JSON, headers(), Donation.class);

    if (donation == null) {
      return null;
    }
    log.info("inserted recurring donation {}", donation.id);
    return donation.id + "";
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
        List.of("RecurringDonation"),
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
      rds.addAll(
          get(
              BLOOMERANG_URL + "transactions?type=RecurringDonation&accountId=" + contact.id + "&orderBy=Date&orderDirection=Desc",
              headers(),
              DonationResults.class
          ).results.stream()
              .map(rd -> toCrmRecurringDonation(rd, contact))
              .filter(rd -> rd.active)
              .collect(Collectors.toList())
      );
    }

    return rds;
  }
  @Override
  public List<CrmRecurringDonation> searchAllRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception{
    ContactSearch contactSearch = new ContactSearch();
    contactSearch.email = email.orElse(null);
    contactSearch.phone = phone.orElse(null);
    contactSearch.keywords = name.orElse(null);
    // TODO: page them?
    PagedResults<CrmContact> contacts = searchContacts(contactSearch);

    List<CrmRecurringDonation> rds = new ArrayList<>();
    for (CrmContact contact : contacts.getResults()) {
      rds.addAll(
              get(
                      BLOOMERANG_URL + "transactions?type=RecurringDonation&accountId=" + contact.id + "&orderBy=Date&orderDirection=Desc",
                      headers(),
                      DonationResults.class
              ).results.stream()
                      .map(rd -> toCrmRecurringDonation(rd, contact))
                      .collect(Collectors.toList())
      );
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
        List.of("RecurringDonation"),
        env.getConfig().bloomerang.fieldDefinitions.paymentGatewaySubscriptionId,
        List.of(paymentGatewayEvent.getSubscriptionId())
    );

    if (rd.isPresent()) {
      closeRecurringDonation(rd.get());
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
    // TODO: We need a refactor. Upstream (DonationService), we're already retrieving this once to confirm the RD's
    //  existence. But here we need it again in order to get the designations. Maybe we need to introduce the raw object
    //  as a field in ManageDonationEvent?
    Donation recurringDonation = getDonation(manageDonationEvent.getDonationId());

    if (manageDonationEvent.getAmount() != null && manageDonationEvent.getAmount() > 0) {
      recurringDonation.amount = manageDonationEvent.getAmount();
      recurringDonation.designations.stream().filter(d -> !Strings.isNullOrEmpty(d.recurringDonationStatus))
          .forEach(rd -> rd.amount = manageDonationEvent.getAmount());
      log.info("Updating amount to {}...", manageDonationEvent.getAmount());
    }
    if (manageDonationEvent.getNextPaymentDate() != null) {
      // TODO: RecurringDonationNextInstallmentDate
    }

    if (manageDonationEvent.getPauseDonation() == true) {
      recurringDonation.designations.stream().filter(d -> !Strings.isNullOrEmpty(d.recurringDonationStatus)).forEach(rd -> {
        rd.recurringDonationStatus = "Closed";
        rd.recurringDonationEndDate = new SimpleDateFormat("MM/dd/yyyy").format(Calendar.getInstance().getTime());
      });
    } else if (manageDonationEvent.getResumeDonation() == true) {
      recurringDonation.designations.stream().filter(d -> !Strings.isNullOrEmpty(d.recurringDonationStatus)).forEach(rd -> {
        rd.recurringDonationStatus = "Active";
        rd.recurringDonationEndDate = null;
      });
    }

    // TODO: See the note on Donation.customFields. Since we currently have the response format and are about to push
    //  in the request format, simply clear them out since we don't need them.
    recurringDonation.designations.stream().filter(d -> !Strings.isNullOrEmpty(d.recurringDonationStatus))
        .forEach(rd -> rd.customFields = null);

    put(BLOOMERANG_URL + "transaction/" + recurringDonation.id, recurringDonation, APPLICATION_JSON, headers(), Donation.class);
  }

  @Override
  public void closeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    // TODO: Avoid the double hit? DonationService already retrieved this once.
    Donation recurringDonation = getDonation(manageDonationEvent.getDonationId());
    closeRecurringDonation(recurringDonation);
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

  @Override
  public List<CrmCustomField> insertCustomFields(String layoutName, List<CrmCustomField> crmCustomFields) {
    return null;
  }

  protected Optional<Donation> getDonation(PaymentGatewayEvent paymentGatewayEvent, List<String> donationTypes,
      String customFieldKey, List<String> _customFieldValues) {
    if (Strings.isNullOrEmpty(customFieldKey)) {
      return Optional.empty();
    }

    List<String> customFieldValues = _customFieldValues.stream().filter(v -> !Strings.isNullOrEmpty(v)).collect(Collectors.toList());
    if (customFieldValues.isEmpty()) {
      return Optional.empty();
    }

    for (String donationType : donationTypes) {
      Optional<Donation> donation = getDonations(paymentGatewayEvent.getCrmContact().id, donationType).stream().filter(d -> {
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
    return donation.designations.stream().flatMap(designation -> designation.customFields.stream())
        .filter(jsonNode -> jsonNode.has("FieldId") && jsonNode.get("FieldId").asInt() == customFieldId)
        .map(jsonNode -> jsonNode.get("Value").get("Value").asText())
        .findFirst().orElse(null);
  }

  protected void closeRecurringDonation(Donation recurringDonation) throws Exception {
    recurringDonation.designations.stream().filter(d -> !Strings.isNullOrEmpty(d.recurringDonationStatus))
        .forEach(rd -> {
          rd.recurringDonationStatus = "Closed";
          rd.recurringDonationEndDate = new SimpleDateFormat("MM/dd/yyyy").format(Calendar.getInstance().getTime());

          // TODO: See the note on Donation.customFields. Since we currently have the response format and are about to push
          //  in the request format, simply clear them out since we don't need them.
          rd.customFields = null;
        });

    put(BLOOMERANG_URL + "transaction/" + recurringDonation.id, recurringDonation, APPLICATION_JSON, headers(), Donation.class);
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
        householdId,
        constituent.firstName,
        constituent.lastName,
        // TODO: See below note on Contact.FullName
//        constituent.fullName,
        constituent.firstName + " " + constituent.lastName,
        primaryEmail,
        primaryPhone, // home phone
        null, null, null, null, // other phone fields
        crmAddress,
        null, null, null, null, // opt in/out
        null, null, // owner
        null, null, null, null, // donation metrics
        null, // emailGroups
        null, // contactLanguage
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
    CrmContact crmContact = new CrmContact();
    crmContact.id = donation.accountId + "";

    return toCrmRecurringDonation(donation, crmContact);
  }

  protected CrmRecurringDonation toCrmRecurringDonation(Donation donation, CrmContact crmContact) {
    if (donation == null) {
      return null;
    }

    // TODO
    CrmAccount crmAccount = new CrmAccount();

    Designation designation = donation.designations.stream().filter(d -> !Strings.isNullOrEmpty(d.recurringDonationStatus)).findFirst().get();

    return new CrmRecurringDonation(
        donation.id + "",
        getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewaySubscriptionId),
        getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewayCustomerId),
        donation.amount,
        getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewayName),
        "Active".equalsIgnoreCase(designation.recurringDonationStatus),
        CrmRecurringDonation.Frequency.fromName(designation.recurringDonationFrequency),
        "Recurring Donation",
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
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Household {
    @JsonProperty("Id")
    public int id;
    @JsonProperty("FullName")
    public String fullName;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
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
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Email {
    @JsonProperty("Type")
    public String type = "Home";
    @JsonProperty("IsPrimary")
    public boolean isPrimary = true;
    @JsonProperty("Value")
    public String value;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Phone {
    @JsonProperty("Type")
    public String type = "Home";
    @JsonProperty("IsPrimary")
    public boolean isPrimary = true;
    @JsonProperty("Number")
    public String number;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
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
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class ConstituentSearchResults {
    @JsonProperty("Total")
    public int total;
    @JsonProperty("Results")
    public List<Constituent> results;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Donation {
    @JsonProperty("Id")
    public int id;
    @JsonProperty("AccountId")
    public Integer accountId;
    @JsonProperty("Amount")
    public Double amount;
    @JsonProperty("Method")
    public String method = "None";
    @JsonProperty("Date")
    public String date;
    @JsonProperty("Designations")
    public List<Designation> designations = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class DonationResults {
    @JsonProperty("Total")
    public int total;
    @JsonProperty("Results")
    public List<Donation> results;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Designation {
    @JsonProperty("Id")
    public int id;
    @JsonProperty("Amount")
    public Double amount;
    @JsonProperty("NonDeductibleAmount")
    public Double nonDeductibleAmount = 0.0;
    @JsonProperty("Type")
    public String type;
    @JsonProperty("FundId")
    public Integer fundId;
    @JsonProperty("RecurringDonationId")
    public Integer recurringDonationId;
    // Active, Closed, Overdue
    @JsonProperty("RecurringDonationStatus")
    public String recurringDonationStatus;
    @JsonProperty("RecurringDonationStartDate")
    public String recurringDonationStartDate;
    @JsonProperty("RecurringDonationEndDate")
    public String recurringDonationEndDate;
    // Weekly, EveryOtherWeekly, TwiceMonthly, Monthly, EveryOtherMonthly, Quarterly, Yearly
    @JsonProperty("RecurringDonationFrequency")
    public String recurringDonationFrequency;
    @JsonProperty("IsExtraPayment")
    public Boolean isExtraPayment;
    // TODO: The following is frustrating. Bloomerang uses one CustomField structure for requests, a different one
    //  for responses, and validation on their side isn't forgiving :(
    @JsonProperty("CustomValues")
    public List<JsonNode> customFields = new ArrayList<>();
  }
}
