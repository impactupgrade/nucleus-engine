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
import java.text.SimpleDateFormat;
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
import static com.impactupgrade.nucleus.util.Utils.getZonedDateTimeFromDateTimeString;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

public class BloomerangCrmService implements CrmService {

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
  public List<CrmAccount> searchAccounts(AccountSearch accountSearch) {
    // TODO
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
      constituentSearchResults = get(BLOOMERANG_URL + "constituents/search?search=" + query, headers(), ConstituentSearchResults.class);
    } catch (Exception e) {
//      env.logJobError("search failed", e);
    }
    if (constituentSearchResults == null) {
      return PagedResults.pagedResultsFromCurrentOffset(Collections.emptyList(), contactSearch);
    }

    for (Constituent constituent : constituentSearchResults.results) {
      if (constituent.emailIds.size() > 1) {
        List<String> emailIds = constituent.emailIds.stream().filter(id -> id != constituent.primaryEmail.id).map(Object::toString).toList();
        constituent.secondaryEmails = get(BLOOMERANG_URL + "emails?id=" + String.join("%7C", emailIds), headers(), EmailResults.class).results;
      }
      if (constituent.phoneIds.size() > 1) {
        List<String> phoneIds = constituent.phoneIds.stream().filter(id -> id != constituent.primaryPhone.id).map(Object::toString).toList();
        constituent.secondaryPhones = get(BLOOMERANG_URL + "phones?id=" + String.join("%7C", phoneIds), headers(), PhoneResults.class).results;
      }
    }

    // API appears to be doing SUPER forgiving fuzzy matches. If the search was by email/phone/name, verify those explicitly.
    // If it was a name search, make sure the name actually matches.
    List<Constituent> constituents = constituentSearchResults.results.stream()
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
        env.getConfig().bloomerang.fieldDefinitions.paymentGatewayTransactionId,
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

    constituent = post(BLOOMERANG_URL + "constituent", constituent, APPLICATION_JSON, headers(), Constituent.class);

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
    donation.amount = crmDonation.amount;
    donation.date = date;
    donation.method = "Credit Card";

    Designation designation = new Designation();
    designation.amount = donation.amount;

    // If the transaction included Fund metadata, assume it's the FundId. Otherwise, use the org's default.
    if (!Strings.isNullOrEmpty(crmDonation.getMetadataValue(env.getConfig().metadataKeys.fund))) {
      designation.fundId = Integer.parseInt(crmDonation.getMetadataValue(env.getConfig().metadataKeys.fund));
    } else {
      designation.fundId = env.getConfig().bloomerang.defaultFundId;
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

    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewayName, crmDonation.gatewayName, designation.customFields);
    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewayTransactionId, crmDonation.transactionId, designation.customFields);
    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewayCustomerId, crmDonation.customerId, designation.customFields);

    donation.designations.add(designation);

    donation = post(BLOOMERANG_URL + "transaction", donation, APPLICATION_JSON, headers(), Donation.class);

    if (donation == null) {
      return null;
    }
    env.logJobInfo("inserted donation {}", donation.id);
    return donation.id + "";
  }

  @Override
  public String insertRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
    String date = DateTimeFormatter.ofPattern("MM/dd/yyyy").format(crmRecurringDonation.subscriptionStartDate);

    Donation donation = new Donation();
    donation.accountId = Integer.parseInt(crmRecurringDonation.contact.id);
    donation.amount = crmRecurringDonation.amount;
    donation.date = date;

    Designation designation = new Designation();
    designation.amount = donation.amount;
    // If the transaction included Fund metadata, assume it's the FundId. Otherwise, use the org's default.
    if (!Strings.isNullOrEmpty(crmRecurringDonation.getMetadataValue(env.getConfig().metadataKeys.fund))) {
      designation.fundId = Integer.parseInt(crmRecurringDonation.getMetadataValue(env.getConfig().metadataKeys.fund));
    } else {
      designation.fundId = env.getConfig().bloomerang.defaultFundId;
    }
    designation.type = "RecurringDonation";
    designation.recurringDonationStatus = "Active";
    designation.recurringDonationStartDate = date;
    designation.recurringDonationFrequency = crmRecurringDonation.frequency.name();

    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewayName, crmRecurringDonation.gatewayName, designation.customFields);
    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewaySubscriptionId, crmRecurringDonation.subscriptionId, designation.customFields);
    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewayCustomerId, crmRecurringDonation.customerId, designation.customFields);

    donation.designations.add(designation);

    env.logJobInfo(mapper.writeValueAsString(donation));
    donation = post(BLOOMERANG_URL + "transaction", donation, APPLICATION_JSON, headers(), Donation.class);

    if (donation == null) {
      return null;
    }
    env.logJobInfo("inserted recurring donation {}", donation.id);
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
  public void refundDonation(CrmDonation crmDonation) throws Exception {
    // Retrieving donations by Stripe IDs are not possible.
  }

  @Override
  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
    throw new RuntimeException("not implemented");
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
    return this.env.getConfig().bloomerang.fieldDefinitions;
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
  public PagedResults<CrmContact> getDonorContacts(Calendar updatedSince) throws Exception {
    return new PagedResults<>();
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
  public List<CrmAccount> getAccountsByEmails(List<String> emails) throws Exception {
    return Collections.emptyList();
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
//    env.logJobInfo("inserted household {}", household.id);
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
  public void addAccountToCampaign(CrmAccount crmAccount, String campaignId, String status) throws Exception {

  }

  @Override
  public void addContactToCampaign(CrmContact crmContact, String campaignId, String status) throws Exception {
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

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId) throws Exception {
    return Optional.empty(); // not possible without the contactId
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId, String accountId, String contactId) throws Exception {
    return getDonation(
        contactId,
        List.of("RecurringDonation"),
        env.getConfig().bloomerang.fieldDefinitions.paymentGatewaySubscriptionId,
        List.of(subscriptionId)
    ).map(this::toCrmRecurringDonation);
  }

  @Override
  public List<CrmRecurringDonation> searchAllRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception{
    ContactSearch contactSearch = new ContactSearch();
    contactSearch.email = email.orElse(null);
    contactSearch.phone = phone.orElse(null);
    contactSearch.keywords = name.map(Set::of).orElse(null);
    // TODO: page them?
    PagedResults<CrmContact> pagedResults = searchContacts(contactSearch);

    List<CrmRecurringDonation> rds = new ArrayList<>();
    for (PagedResults.ResultSet<CrmContact> resultSet : pagedResults.getResultSets()) {
      for (CrmContact contact : resultSet.getRecords()) {
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
    }
    return rds;
  }
  @Override
  public void insertDonationDeposit(List<CrmDonation> crmDonations) throws Exception {
    // currently no deposit management
  }

  @Override
  public List<CrmDonation> getDonations(Calendar updatedAfter) throws Exception {
    return List.of();
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    Donation recurringDonation = getDonation(id);
    return Optional.ofNullable(toCrmRecurringDonation(recurringDonation));
  }

  @Override
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    CrmRecurringDonation crmRecurringDonation = manageDonationEvent.getCrmRecurringDonation();
    // TODO: We need a refactor. Upstream (DonationService), we're already retrieving this once to confirm the RD's
    //  existence. But here we need it again in order to get the designations. Can we trust crmRecurringDonation.raw?
    Donation recurringDonation = getDonation(crmRecurringDonation.id);

    if (crmRecurringDonation.amount != null && crmRecurringDonation.amount > 0) {
      recurringDonation.amount = crmRecurringDonation.amount;
      recurringDonation.designations.stream().filter(d -> !Strings.isNullOrEmpty(d.recurringDonationStatus))
          .forEach(rd -> rd.amount = crmRecurringDonation.amount);
      env.logJobInfo("Updating amount to {}...", crmRecurringDonation.amount);
    }
    if (manageDonationEvent.getNextPaymentDate() != null) {
      // TODO: RecurringDonationNextInstallmentDate
    }

    if (manageDonationEvent.getPauseDonation()) {
      recurringDonation.designations.stream().filter(d -> !Strings.isNullOrEmpty(d.recurringDonationStatus)).forEach(rd -> {
        rd.recurringDonationStatus = "Closed";
        rd.recurringDonationEndDate = new SimpleDateFormat("MM/dd/yyyy").format(Calendar.getInstance().getTime());
      });
    } else if (manageDonationEvent.getResumeDonation()) {
      recurringDonation.designations.stream().filter(d -> !Strings.isNullOrEmpty(d.recurringDonationStatus)).forEach(rd -> {
        rd.recurringDonationStatus = "Active";
        rd.recurringDonationEndDate = "";
      });
    }

    // TODO: See the note on Donation.customFields. Since we currently have the response format and are about to push
    //  in the request format, simply clear them out since we don't need them.
    recurringDonation.designations.stream().filter(d -> !Strings.isNullOrEmpty(d.recurringDonationStatus))
        .forEach(rd -> rd.customFields = null);

    put(BLOOMERANG_URL + "transaction/" + recurringDonation.id, recurringDonation, APPLICATION_JSON, headers(), Donation.class);
  }

  @Override
  public void closeRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
    // TODO: Avoid the double hit? Upstream already retrieved this once. Doesn't crmRecurringDonation.raw ALWAYS have Donation in it?
    Donation recurringDonation = getDonation(crmRecurringDonation.id);
    closeRecurringDonation(recurringDonation);
  }

  @Override
  public String insertOpportunity(CrmOpportunity crmOpportunity) throws Exception {
    // bulk imports
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
        donation.amount,
        null, // String customerId,
        null, // ZonedDateTime depositDate,
        null, // String depositId,
        null, // String depositTransactionId,
        getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewayName),
        null, // EnvironmentConfig.PaymentEventType paymentEventType,
        null, // String paymentMethod,
        null, // String refundId,
        null, // ZonedDateTime refundDate,
        CrmDonation.Status.SUCCESSFUL, // Bloomerang has no notion of non-successful transactions.
        null,
        false, // boolean transactionCurrencyConverted,
        null, // Double transactionExchangeRate,
        null, // Double transactionFeeInDollars,
        getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewayTransactionId),
        null, // Double transactionNetAmountInDollars,
        null, // Double transactionOriginalAmountInDollars,
        null, // String transactionOriginalCurrency,
        null, // String transactionSecondaryId,
        null, // String transactionUrl,
        null, // String campaignId,
        Utils.getZonedDateTimeFromDateTimeString(donation.date),
        null, // String description,
        null, // String name,
        null, // String ownerId,
        null, // String recordTypeId,
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
        crmAccount,
        crmContact,
        "Active".equalsIgnoreCase(designation.recurringDonationStatus),
        donation.amount,
        getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewayCustomerId),
        null, // String description,
        "Recurring Donation",
        CrmRecurringDonation.Frequency.fromName(designation.recurringDonationFrequency),
        getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewayName),
        null, // String ownerId,
        designation.recurringDonationStatus,
        null, // String subscriptionCurrency,
        getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewaySubscriptionId),
        getZonedDateTimeFromDateTimeString(designation.recurringDonationEndDate),
        getZonedDateTimeFromDateTimeString(designation.recurringDonationNextDate),
        getZonedDateTimeFromDateTimeString(designation.recurringDonationStartDate),
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

    @JsonProperty("EmailIds")
    public List<Integer> emailIds = new ArrayList<>();
    @JsonProperty("PhoneIds")
    public List<Integer> phoneIds = new ArrayList<>();

    // transient
    List<Email> secondaryEmails = new ArrayList<>();
    List<Phone> secondaryPhones = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Email {
    @JsonProperty("Id")
    public int id;
    @JsonProperty("Type")
    public String type = "Home";
    @JsonProperty("IsPrimary")
    public boolean isPrimary = true;
    @JsonProperty("Value")
    public String value;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class EmailResults {
    @JsonProperty("Total")
    public int total;
    @JsonProperty("Results")
    public List<Email> results;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Phone {
    @JsonProperty("Id")
    public int id;
    @JsonProperty("Type")
    public String type = "Home";
    @JsonProperty("IsPrimary")
    public boolean isPrimary = true;
    @JsonProperty("Number")
    public String number;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class PhoneResults {
    @JsonProperty("Total")
    public int total;
    @JsonProperty("Results")
    public List<Phone> results;
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
    @JsonProperty("RecurringDonationNextInstallmentDate")
    public String recurringDonationNextDate;
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
