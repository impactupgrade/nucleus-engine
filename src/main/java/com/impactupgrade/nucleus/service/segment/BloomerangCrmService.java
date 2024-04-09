/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import com.impactupgrade.integration.bloomerang.BloomerangClient;
import com.impactupgrade.integration.bloomerang.model.Address;
import com.impactupgrade.integration.bloomerang.model.Constituent;
import com.impactupgrade.integration.bloomerang.model.Designation;
import com.impactupgrade.integration.bloomerang.model.Donation;
import com.impactupgrade.integration.bloomerang.model.Email;
import com.impactupgrade.integration.bloomerang.model.Phone;
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
import com.impactupgrade.nucleus.util.Utils;

import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.util.Utils.getZonedDateTimeFromDateTimeString;

public class BloomerangCrmService implements CrmService {

  private static final ObjectMapper mapper = new ObjectMapper();

  protected Environment env;
  protected BloomerangClient bloomerangClient;

  @Override
  public String name() { return "bloomerang"; }

  @Override
  public boolean isConfigured(Environment env) {
    return !Strings.isNullOrEmpty(env.getConfig().bloomerang.secretKey);
  }

  @Override
  public void init(Environment env) {
    this.env = env;
    bloomerangClient = new BloomerangClient(env.getConfig().bloomerang.secretKey);
  }

  @Override
  public Optional<CrmContact> getContactById(String id) throws Exception {
    Constituent constituent = bloomerangClient.getConstituentById(id);
    return Optional.ofNullable(toCrmContact(constituent));
  }

  @Override
  public Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception {
    //Not currently implemented
    return Optional.empty();
  }

  @Override
  public Optional<CrmContact> getFilteredContactByEmail(String email, String filter) throws Exception {
    //TODO Not currently implemented
    return Optional.empty();
  }

  @Override
  public List<CrmAccount> searchAccounts(AccountSearch accountSearch) {
    // TODO
    return Collections.emptyList();
  }

  @Override
  public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) {
    List<Constituent> constituents = bloomerangClient.searchConstituents(
        contactSearch.firstName, contactSearch.lastName, contactSearch.email, contactSearch.phone, contactSearch.keywords);

    List<CrmContact> crmContacts = toCrmContact(constituents);
    return PagedResults.getPagedResultsFromCurrentOffset(crmContacts, contactSearch);
  }

  @Override
  public Optional<CrmDonation> getDonationByTransactionIds(List<String> transactionIds, String accountId, String contactId) {
    return bloomerangClient.getDonation(
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

    return bloomerangClient.insertConstituent(constituent);
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
      Donation recurringDonation = bloomerangClient.getDonation(crmDonation.recurringDonation.id);
      designation.recurringDonationId = recurringDonation.designations.get(0).id;
      designation.isExtraPayment = false;
    } else {
      designation.type = "Donation";
    }

    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewayName, crmDonation.gatewayName, designation.customFields);
    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewayTransactionId, crmDonation.transactionId, designation.customFields);
    setProperty(env.getConfig().bloomerang.fieldDefinitions.paymentGatewayCustomerId, crmDonation.customerId, designation.customFields);

    donation.designations.add(designation);

    return bloomerangClient.insertDonation(donation);
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

    return bloomerangClient.insertRecurringDonation(donation);
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
  public List<CrmUser> getUsers() throws Exception {
    return Collections.emptyList();
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
  public List<CrmContact> getEmailContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public List<CrmAccount> getEmailAccounts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return Collections.emptyList();
  }

  @Override
  public List<CrmContact> getSmsContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
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
  public void addAccountToCampaign(CrmAccount crmAccount, String campaignId) throws Exception {

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

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId, String accountId, String contactId) throws Exception {
    return bloomerangClient.getDonation(
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
    PagedResults<CrmContact> contacts = searchContacts(contactSearch);

    List<CrmRecurringDonation> rds = new ArrayList<>();
    for (CrmContact contact : contacts.getResults()) {
      rds.addAll(
              bloomerangClient.getDonations(contact.id, "RecurringDonation").results.stream()
                      .map(rd -> toCrmRecurringDonation(rd, contact))
                      .collect(Collectors.toList())
      );
    }
    return rds;
  }
  @Override
  public void insertDonationDeposit(List<CrmDonation> crmDonations) throws Exception {
    // currently no deposit management
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    Donation recurringDonation = bloomerangClient.getDonation(id);
    return Optional.ofNullable(toCrmRecurringDonation(recurringDonation));
  }

  @Override
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    CrmRecurringDonation crmRecurringDonation = manageDonationEvent.getCrmRecurringDonation();
    // TODO: We need a refactor. Upstream (DonationService), we're already retrieving this once to confirm the RD's
    //  existence. But here we need it again in order to get the designations. Can we trust crmRecurringDonation.raw?
    Donation recurringDonation = bloomerangClient.getDonation(crmRecurringDonation.id);

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

    bloomerangClient.updateRecurringDonation(recurringDonation);
  }

  @Override
  public void closeRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
    // TODO: Avoid the double hit? Upstream already retrieved this once. Doesn't crmRecurringDonation.raw ALWAYS have Donation in it?
    Donation recurringDonation = bloomerangClient.getDonation(crmRecurringDonation.id);
    bloomerangClient.closeRecurringDonation(recurringDonation);
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
  public String insertActivity(CrmActivity crmActivity) throws Exception {
    // Unlikely to be relevant for Bloomerang.
    return null;
  }

  @Override
  public String updateActivity(CrmActivity crmActivity) throws Exception {
    return null;
  }

  @Override
  public Optional<CrmActivity> getActivityByExternalRef(String externalRef) throws Exception {
    return Optional.empty();
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
        bloomerangClient.getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewayName),
        null, // EnvironmentConfig.PaymentEventType paymentEventType,
        null, // String paymentMethod,
        null, // String refundId,
        null, // ZonedDateTime refundDate,
        CrmDonation.Status.SUCCESSFUL, // Bloomerang has no notion of non-successful transactions.
        null,
        false, // boolean transactionCurrencyConverted,
        null, // Double transactionExchangeRate,
        null, // Double transactionFeeInDollars,
        bloomerangClient.getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewayTransactionId),
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
        bloomerangClient.getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewayCustomerId),
        null, // String description,
        "Recurring Donation",
        CrmRecurringDonation.Frequency.fromName(designation.recurringDonationFrequency),
        bloomerangClient.getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewayName),
        null, // String ownerId,
        designation.recurringDonationStatus,
        null, // String subscriptionCurrency,
        bloomerangClient.getCustomFieldValue(donation, env.getConfig().bloomerang.fieldDefinitions.paymentGatewaySubscriptionId),
        getZonedDateTimeFromDateTimeString(designation.recurringDonationEndDate),
        getZonedDateTimeFromDateTimeString(designation.recurringDonationNextDate),
        getZonedDateTimeFromDateTimeString(designation.recurringDonationStartDate),
        donation,
        "https://crm.bloomerang.co/Constituent/" + donation.accountId + "/Transaction/Edit/" + donation.id
    );
  }
}
