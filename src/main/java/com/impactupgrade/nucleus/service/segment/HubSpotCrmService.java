/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.google.common.base.Strings;
import com.impactupgrade.integration.hubspot.AssociationSearchResults;
import com.impactupgrade.integration.hubspot.ColumnMapping;
import com.impactupgrade.integration.hubspot.Company;
import com.impactupgrade.integration.hubspot.CompanyProperties;
import com.impactupgrade.integration.hubspot.Contact;
import com.impactupgrade.integration.hubspot.ContactProperties;
import com.impactupgrade.integration.hubspot.Deal;
import com.impactupgrade.integration.hubspot.DealProperties;
import com.impactupgrade.integration.hubspot.DealResults;
import com.impactupgrade.integration.hubspot.FileImportPage;
import com.impactupgrade.integration.hubspot.Filter;
import com.impactupgrade.integration.hubspot.FilterGroup;
import com.impactupgrade.integration.hubspot.HasId;
import com.impactupgrade.integration.hubspot.ImportFile;
import com.impactupgrade.integration.hubspot.ImportRequest;
import com.impactupgrade.integration.hubspot.ImportResponse;
import com.impactupgrade.integration.hubspot.PropertiesResponse;
import com.impactupgrade.integration.hubspot.Property;
import com.impactupgrade.integration.hubspot.crm.v3.HubSpotCrmV3Client;
import com.impactupgrade.integration.hubspot.crm.v3.ImportsCrmV3Client;
import com.impactupgrade.integration.hubspot.crm.v3.PropertiesCrmV3Client;
import com.impactupgrade.integration.hubspot.v1.EngagementV1Client;
import com.impactupgrade.integration.hubspot.v1.model.ContactArray;
import com.impactupgrade.integration.hubspot.v1.model.Engagement;
import com.impactupgrade.integration.hubspot.v1.model.EngagementAssociations;
import com.impactupgrade.integration.hubspot.v1.model.EngagementRequest;
import com.impactupgrade.integration.hubspot.v1.model.EngagementTaskMetadata;
import com.impactupgrade.integration.hubspot.v1.model.HasValue;
import com.impactupgrade.nucleus.client.HubSpotClientFactory;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmCustomField;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.model.CrmContact.PreferredPhone.HOME;
import static com.impactupgrade.nucleus.model.CrmContact.PreferredPhone.MOBILE;
import static com.impactupgrade.nucleus.model.CrmContact.PreferredPhone.WORK;

// TODO: At the moment, this assumes field definitions are always present in env.json! However, if situations come up
//  similar to SFDC where that won't be the case (ex: LJI/TER's split between payment gateway fields), this will need
//  sanity checks like we have in SfdcCrmService.

public class HubSpotCrmService implements CrmService {

  private static final Logger log = LogManager.getLogger(HubSpotCrmService.class);

  protected Environment env;
  protected HubSpotCrmV3Client hsClient;
  protected EngagementV1Client engagementClient;
  protected ImportsCrmV3Client importsClient;
  protected PropertiesCrmV3Client propertiesClient;

  protected Set<String> companyFields;
  protected Set<String> contactFields;
  protected Set<String> dealFields;

  @Override
  public String name() { return "hubspot"; }

  @Override
  public boolean isConfigured(Environment env) {
    return !Strings.isNullOrEmpty(env.getConfig().hubspot.secretKey);
  }

  @Override
  public void init(Environment env) {
    this.env = env;
    hsClient = HubSpotClientFactory.crmV3Client(env);
    engagementClient = HubSpotClientFactory.engagementV1Client(env);
    importsClient = HubSpotClientFactory.importsCrmV3Client(env);
    propertiesClient = HubSpotClientFactory.propertiesCrmV3Client(env);

    companyFields = getCustomFieldNames();
    companyFields.addAll(env.getConfig().hubspot.customQueryFields.company.stream().toList());
    contactFields = getCustomFieldNames();
    contactFields.addAll(env.getConfig().hubspot.customQueryFields.contact.stream().toList());
    dealFields = getCustomFieldNames();
    dealFields.addAll(env.getConfig().hubspot.customQueryFields.deal.stream().toList());
  }

  @Override
  public Optional<CrmAccount> getAccountById(String id) throws Exception {
    Company company = hsClient.company().read(id, companyFields);
    CrmAccount crmAccount = toCrmAccount(company);
    return Optional.of(crmAccount);
  }

  @Override
  public Optional<CrmAccount> getAccountByCustomerId(String customerId) throws Exception {
    if (Strings.isNullOrEmpty(customerId) || Strings.isNullOrEmpty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayCustomerId)) {
      return Optional.empty();
    }

    // TODO: This is a little nuts -- have to do 3 different queries. But unlike SFDC, we're not storing the customerId
    //  on the company (should we even be doing that there?).

    Filter filter = new Filter(env.getConfig().hubspot.fieldDefinitions.paymentGatewayCustomerId, "EQ", customerId);
    List<FilterGroup> filterGroups = List.of(new FilterGroup(List.of(filter)));
    DealResults dealResults = hsClient.deal().search(filterGroups, dealFields);

    if (Objects.isNull(dealResults) || dealResults.getTotal() == 0) {
      return Optional.empty();
    }
    AssociationSearchResults associations = hsClient.association().search("deal", dealResults.getResults().get(0).getId(), "company");
    if (Objects.isNull(associations) || associations.getResults().size() == 0 || associations.getResults().get(0).getTo().size() == 0) {
      return Optional.empty();
    }

    return getAccountById(associations.getResults().get(0).getTo().get(0).getId());
  }

  @Override
  public Optional<CrmContact> getContactById(String id) throws Exception {
    Contact contact = hsClient.contact().read(id, contactFields);
    CrmContact crmContact = toCrmContact(contact);
    return Optional.of(crmContact);
  }
  @Override
  public Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception {
    //TODO Not currently implemented
    return Optional.empty();
  }
  @Override
  public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) {
    // TODO: For now, supporting the individual use cases, but this needs reworked at the client level. Add support for
    //  combining clauses, owner, keyword search, pagination, etc.

    if (!Strings.isNullOrEmpty(contactSearch.email)) {
      List<CrmContact> contacts = toCrmContact(hsClient.contact().searchByEmail(contactSearch.email, contactFields).getResults());
      return PagedResults.getPagedResultsFromCurrentOffset(contacts, contactSearch);
    } else if (!Strings.isNullOrEmpty(contactSearch.phone)) {
      List<CrmContact> contacts =  toCrmContact(hsClient.contact().searchByPhone(contactSearch.phone, contactFields).getResults());
      return PagedResults.getPagedResultsFromCurrentOffset(contacts, contactSearch);
    } else {
      return null;
    }
  }

  // Needed by an IT.
  public List<CrmDonation> getDonationsByAccountId(String accountId) throws Exception {
    AssociationSearchResults associations = hsClient.association().search("company", accountId, "deal");
    List<String> dealIds = associations.getResults().stream().flatMap(r -> r.getTo().stream()).map(HasId::getId).collect(Collectors.toList());
    List<Deal> deals = hsClient.deal().batchRead(dealIds, dealFields).getResults().stream().filter(deal ->
        !deal.getProperties().getOtherProperties().containsKey(env.getConfig().hubspot.fieldDefinitions.recurringDonationFrequency)
            || Strings.isNullOrEmpty((String) deal.getProperties().getOtherProperties().get(env.getConfig().hubspot.fieldDefinitions.recurringDonationFrequency))
    ).collect(Collectors.toList());
    return toCrmDonation(deals);
  }

  @Override
  public Optional<CrmDonation> getDonationByTransactionId(String transactionId) throws Exception {
    Filter filter = new Filter(env.getConfig().hubspot.fieldDefinitions.paymentGatewayTransactionId, "EQ", transactionId);
    List<FilterGroup> filterGroups = List.of(new FilterGroup(List.of(filter)));
    DealResults results = hsClient.deal().search(filterGroups, dealFields);

    if (results == null || results.getTotal() == 0) {
      return Optional.empty();
    }

    Deal deal = results.getResults().get(0);
    return Optional.of(toCrmDonation(deal));
  }

  @Override
  public List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception {
    AssociationSearchResults associations = hsClient.association().search("company", accountId, "deal");
    List<String> dealIds = associations.getResults().stream().flatMap(r -> r.getTo().stream()).map(HasId::getId).collect(Collectors.toList());
    List<Deal> deals = hsClient.deal().batchRead(dealIds, dealFields).getResults().stream().filter(deal ->
        deal.getProperties().getOtherProperties().containsKey(env.getConfig().hubspot.fieldDefinitions.recurringDonationFrequency)
            && !Strings.isNullOrEmpty((String) deal.getProperties().getOtherProperties().get(env.getConfig().hubspot.fieldDefinitions.recurringDonationFrequency))
            && deal.getProperties().getDealstage().equalsIgnoreCase(env.getConfig().hubspot.recurringDonationPipeline.openStageId)
    ).collect(Collectors.toList());
    return toCrmRecurringDonation(deals);
  }

  @Override
  public List<CrmRecurringDonation> searchAllRecurringDonations(Optional<String> name, Optional<String> email, Optional<String> phone) throws Exception {
    List<CrmRecurringDonation> results = new ArrayList<>();
    List<Contact> contactResults = new ArrayList<>();


    //Find contacts using the search params

    if (name.isPresent()) {
      FilterGroup filterGroup = new FilterGroup(List.of(
              new Filter("firstname", "CONTAINS_TOKEN", "*" + name.get() + "*"),
              new Filter("lastname", "CONTAINS_TOKEN", "*" + name.get() + "*")));
      List<FilterGroup> filterGroups = List.of(filterGroup);
      contactResults.addAll(hsClient.contact().search(filterGroups, contactFields).getResults());
    }

    if (email.isPresent()) {
      contactResults.addAll(hsClient.contact().searchByEmail(email.get(), contactFields).getResults());
    }

    if (phone.isPresent()) {
      contactResults.addAll(hsClient.contact().searchByPhone(phone.get(), contactFields).getResults());
    }
    if(contactResults.isEmpty()){
      log.info("No results found");
      return Collections.emptyList();
    }

    List<FilterGroup> filters = new ArrayList<>();
    for(Contact contact : contactResults){
      filters.add(new FilterGroup(List.of(new Filter("associations.contact", "EQ", contact.getId()))));
    }

    return toCrmRecurringDonation(hsClient.deal().search(filters, dealFields).getResults());
  }
  @Override
  public Optional<CrmUser> getUserById(String id) throws Exception {
    // TODO: will need to add User support to HS lib, if even possible
    return Optional.empty();
  }

  @Override
  public Optional<CrmUser> getUserByEmail(String id) throws Exception {
    // TODO: will need to add User support to HS lib, if even possible
    return Optional.empty();
  }

  @Override
  public String insertTask(CrmTask crmTask) throws Exception {
    EngagementRequest engagementRequest = new EngagementRequest();
    setTaskFields(engagementRequest, crmTask);
    EngagementRequest response = engagementClient.insert(engagementRequest);
    return response == null ? null : response.getId() + "";
  }

  @Override
  public List<CrmCustomField> insertCustomFields(String layoutName, List<CrmCustomField> crmCustomFields) {
    Map<String, List<CrmCustomField>> propertiesByObjectTypes = crmCustomFields.stream()
                    .collect(Collectors.groupingBy(crmCustomField -> crmCustomField.objectName));
    List<Property> insertedProperties = new ArrayList<>();
    propertiesByObjectTypes.entrySet().forEach(entry -> {
      String objectName = entry.getKey();
      List<CrmCustomField> customFields = entry.getValue();
      List<Property> properties = customFields.stream()
              .map(this::toProperty)
              .collect(Collectors.toList());

      PropertiesResponse propertiesResponse = propertiesClient.batchInsert(objectName, properties);
      insertedProperties.addAll(propertiesResponse.getResults());
    });
    return crmCustomFields; // TODO: parse from insertedProperties?
  }

  protected Property toProperty(CrmCustomField crmCustomField) {
    Property property = new Property();
    property.setName(crmCustomField.name);
    property.setLabel(crmCustomField.label);
    Pair<String, String> typePair = toPropertyType(crmCustomField.type);
    property.setType(typePair.getLeft());
    property.setFieldType(typePair.getRight());
    property.setGroupName(crmCustomField.groupName);
    property.setShowCurrencySymbol(crmCustomField.type == CrmCustomField.Type.CURRENCY);
    return property;
  }

  protected Pair<String,String> toPropertyType(CrmCustomField.Type type) {
    // HS types (The data types of the property):
    // string, number, date, datetime, enumeration
    // HS field types (Controls how the property appears in HubSpot):
    // textarea, text, date, file, number, select, radio, checkbox, booleancheckbox

    // Pair.of(type, fieldType):
    return switch(type) {
      //case TEXT -> FieldType.Text;
      case DATE -> Pair.of("datetime", "date");
      case CURRENCY -> Pair.of("number", "number");
      default -> Pair.of("text", "text"); // TODO: defaults?
    };
  }

  @Override
  public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
    return this.env.getConfig().hubspot.fieldDefinitions;
  }

  protected void setTaskFields(EngagementRequest engagementRequest, CrmTask crmTask) {
    Engagement engagement = new Engagement();
    engagement.setActive(true);
    engagement.setType("TASK");
    engagement.setOwnerId(crmTask.assignTo);
    engagementRequest.setEngagement(engagement);

    EngagementAssociations associations = new EngagementAssociations();
    try {
      Long contactId = Long.parseLong(crmTask.targetId);
      associations.setContactIds(List.of(contactId));
    } catch (NumberFormatException nfe) {
      throw new IllegalArgumentException("Failed to parse contact id from target id " + crmTask.targetId + " !");
    }
    engagementRequest.setAssociations(associations);

    EngagementTaskMetadata metadata = new EngagementTaskMetadata();
    metadata.setBody(crmTask.description);

    switch (crmTask.status) {
      case TO_DO -> metadata.setStatus(EngagementTaskMetadata.Status.NOT_STARTED);
      case IN_PROGRESS -> metadata.setStatus(EngagementTaskMetadata.Status.IN_PROGRESS);
      case DONE -> metadata.setStatus(EngagementTaskMetadata.Status.COMPLETED);
      default -> metadata.setStatus(EngagementTaskMetadata.Status.NOT_STARTED);
    }
    metadata.setSubject(crmTask.subject);
    engagementRequest.setMetadata(metadata);
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationBySubscriptionId(String subscriptionId) throws Exception {
    Filter filter = new Filter(env.getConfig().hubspot.fieldDefinitions.paymentGatewaySubscriptionId, "EQ", subscriptionId);
    List<FilterGroup> filterGroups = List.of(new FilterGroup(List.of(filter)));
    DealResults results = hsClient.deal().search(filterGroups, dealFields);

    if (results == null || results.getTotal() == 0) {
      return Optional.empty();
    }

    Deal deal = results.getResults().get(0);
    return Optional.of(toCrmRecurringDonation(deal));
  }

  @Override
  public void updateDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO
  }

  @Override
  public String insertAccount(CrmAccount crmAccount) throws Exception {
    CompanyProperties account = new CompanyProperties();
    setAccountFields(account, crmAccount);
    Company response = hsClient.company().insert(account);
    return response == null ? null : response.getId();
  }

  @Override
  public void updateAccount(CrmAccount crmAccount) throws Exception {
    CompanyProperties account = new CompanyProperties();
    setAccountFields(account, crmAccount);
    hsClient.company().update(crmAccount.id, account);
  }

  protected void setAccountFields(CompanyProperties account, CrmAccount crmAccount) {
    account.setName(crmAccount.name);

    account.setAddress(crmAccount.address.street);
    account.setCity(crmAccount.address.city);
    account.setState(crmAccount.address.state);
    account.setZip(crmAccount.address.postalCode);
    account.setCountry(crmAccount.address.country);
  }

  @Override
  public void deleteAccount(String accountId) throws Exception {
    hsClient.company().delete(accountId);
  }

  @Override
  public String insertContact(CrmContact crmContact) throws Exception {
    ContactProperties contact = new ContactProperties();
    setContactFields(contact, crmContact);
    Contact response = hsClient.contact().insert(contact);
    return response == null ? null : response.getId();
  }

  @Override
  public void updateContact(CrmContact crmContact) throws Exception {
    ContactProperties contact = new ContactProperties();
    setContactFields(contact, crmContact);
    hsClient.contact().update(crmContact.id, contact);
  }

  @Override
  public void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception {
    // TODO
  }

  protected void setContactFields(ContactProperties contact, CrmContact crmContact) throws Exception {
    contact.setAssociatedcompanyid(crmContact.accountId);
    contact.setFirstname(crmContact.firstName);
    contact.setLastname(crmContact.lastName);
    contact.setEmail(crmContact.email);
    // TODO: home and work phones are customer to LJI, but we may want to add this as a customization in env.json
//    contact.setHomephone(crmContact.homePhone);
    contact.setMobilephone(crmContact.mobilePhone);
//    contact.setWorkphone(crmContact.workPhone);
    if (crmContact.preferredPhone == HOME) {
      contact.setPhone(crmContact.homePhone);
//      contact.setPreferredPhone("Home");
    } else if (crmContact.preferredPhone == MOBILE) {
      contact.setPhone(crmContact.mobilePhone);
//      contact.setPreferredPhone("Mobile");
    } else if (crmContact.preferredPhone == WORK) {
      contact.setPhone(crmContact.workPhone);
//      contact.setPreferredPhone("Work");
    }
    setProperty(env.getConfig().hubspot.fieldDefinitions.contactLanguage, crmContact.contactLanguage, contact.getOtherProperties());

    // TODO: add/remove in default lists?
    if (crmContact.emailOptIn != null && crmContact.emailOptIn) {
      setProperty(env.getConfig().hubspot.fieldDefinitions.emailOptIn, true, contact.getOtherProperties());
      setProperty(env.getConfig().hubspot.fieldDefinitions.emailOptOut, false, contact.getOtherProperties());
    }
    if (crmContact.emailOptOut != null && crmContact.emailOptOut) {
      setProperty(env.getConfig().hubspot.fieldDefinitions.emailOptIn, false, contact.getOtherProperties());
      setProperty(env.getConfig().hubspot.fieldDefinitions.emailOptOut, true, contact.getOtherProperties());
    }

    if (crmContact.smsOptIn != null && crmContact.smsOptIn) {
      setProperty(env.getConfig().hubspot.fieldDefinitions.smsOptIn, true, contact.getOtherProperties());
      setProperty(env.getConfig().hubspot.fieldDefinitions.smsOptOut, false, contact.getOtherProperties());

      String defaultListId = env.getConfig().hubspot.defaultSmsOptInList;
      if (!Strings.isNullOrEmpty(defaultListId)) {
        log.info("opting into the default HubSpot list: {}", defaultListId);
        addContactToList(crmContact, defaultListId);
      }
    }
    if (crmContact.smsOptOut != null && crmContact.smsOptOut) {
      setProperty(env.getConfig().hubspot.fieldDefinitions.smsOptIn, false, contact.getOtherProperties());
      setProperty(env.getConfig().hubspot.fieldDefinitions.smsOptOut, true, contact.getOtherProperties());

      String defaultListId = env.getConfig().hubspot.defaultSmsOptInList;
      if (!Strings.isNullOrEmpty(defaultListId)) {
        log.info("opting out of the default HubSpot list: {}", defaultListId);
        removeContactFromList(crmContact, defaultListId);
      }
    }
  }

  @Override
  public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: campaign

    DealProperties deal = new DealProperties();
    setDonationFields(deal, paymentGatewayEvent);

    Deal response = hsClient.deal().insert(deal);
    if (response != null) {
      if (paymentGatewayEvent.isTransactionRecurring()) {
        // TODO: This would be ideal, but not currently supported by HS. However, supposedly it might be in the future.
        //  Keeping this in case that happens. But for now, see setDonationFields -- we set a custom field.
//        Association recurringDonationAssociation = new Association(response.getId(), paymentGatewayEvent.getPrimaryCrmRecurringDonationId(),
//            "deal_to_deal");
//        hsClient.association().insert(recurringDonationAssociation);
      }
      if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().id)) {
        hsClient.association().insert("deal", response.getId(), "company", paymentGatewayEvent.getCrmAccount().id);
      }
      if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().id)) {
        hsClient.association().insert("deal", response.getId(), "contact", paymentGatewayEvent.getCrmContact().id);
      }

      return response.getId();
    } else {
      return null;
    }
  }

  protected void setDonationFields(DealProperties deal, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: campaign

    deal.setPipeline(env.getConfig().hubspot.donationPipeline.id);
    if (paymentGatewayEvent.isTransactionSuccess()) {
      deal.setDealstage(env.getConfig().hubspot.donationPipeline.successStageId);
    } else {
      deal.setDealstage(env.getConfig().hubspot.donationPipeline.failedStageId);
    }

    deal.setClosedate(GregorianCalendar.from(paymentGatewayEvent.getTransactionDate()));
    deal.setDescription(paymentGatewayEvent.getTransactionDescription());
    deal.setDealname("Donation: " + paymentGatewayEvent.getCrmContact().fullName());

    if (paymentGatewayEvent.isTransactionRecurring()) {
      setProperty(env.getConfig().hubspot.fieldDefinitions.recurringDonationDealId, paymentGatewayEvent.getCrmRecurringDonationId(), deal.getOtherProperties());
    }

    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayName, paymentGatewayEvent.getGatewayName(), deal.getOtherProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayTransactionId, paymentGatewayEvent.getTransactionId(), deal.getOtherProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayCustomerId, paymentGatewayEvent.getCustomerId(), deal.getOtherProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.fund, paymentGatewayEvent.getMetadataValue(env.getConfig().metadataKeys.fund), deal.getOtherProperties());
    // Do NOT set subscriptionId! In getRecurringDonation, we search by that and expect only the RD to be returned.

    deal.setAmount(paymentGatewayEvent.getTransactionAmountInDollars());
    if (paymentGatewayEvent.getTransactionOriginalCurrency() != null) {
      // set the custom fields related for international donation
      setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayAmountOriginal, paymentGatewayEvent.getTransactionOriginalAmountInDollars(), deal.getOtherProperties());
      setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayAmountOriginalCurrency, paymentGatewayEvent.getTransactionOriginalCurrency(), deal.getOtherProperties());
      setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayAmountExchangeRate, paymentGatewayEvent.getTransactionExchangeRate(), deal.getOtherProperties());
    }
  }

  @Override
  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> donation = getDonation(paymentGatewayEvent);

    if (donation.isEmpty()) {
      log.warn("unable to find HS donation using transaction {}", paymentGatewayEvent.getTransactionId());
      return;
    }

    DealProperties deal = new DealProperties();
    setDonationRefundFields(deal, paymentGatewayEvent);

    hsClient.deal().update(donation.get().id, deal);
  }

  protected void setDonationRefundFields(DealProperties deal, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    deal.setDealstage(env.getConfig().hubspot.donationPipeline.refundedStageId);

    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayRefundId, paymentGatewayEvent.getRefundId(), deal.getOtherProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayRefundDate, GregorianCalendar.from(paymentGatewayEvent.getRefundDate()), deal.getOtherProperties());
  }

  @Override
  public void insertDonationDeposit(List<PaymentGatewayEvent> paymentGatewayEvents) throws Exception {
    for (PaymentGatewayEvent paymentGatewayEvent : paymentGatewayEvents) {
      Optional<CrmDonation> donation = getDonation(paymentGatewayEvent);

      if (donation.isEmpty()) {
        log.warn("unable to find HS donation using transaction {}", paymentGatewayEvent.getTransactionId());
        continue;
      }

      Deal deal = (Deal) donation.get().rawObject;

      // If the payment gateway event has a refund ID, this item in the payout was a refund. Mark it as such!
      if (!Strings.isNullOrEmpty(paymentGatewayEvent.getRefundId())) {
        if (!Strings.isNullOrEmpty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayRefundId)
            && deal.getProperties().getOtherProperties().get(env.getConfig().hubspot.fieldDefinitions.paymentGatewayRefundId) == null) {
          DealProperties dealProperties = new DealProperties();
          setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayRefundDepositDate, GregorianCalendar.from(paymentGatewayEvent.getDepositDate()), dealProperties.getOtherProperties());
          setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayRefundDepositId, paymentGatewayEvent.getDepositId(), dealProperties.getOtherProperties());
          hsClient.deal().update(donation.get().id, dealProperties);
        } else {
          log.info("skipping refund {}; already marked with refund deposit info", donation.get().id);
        }
        // Otherwise, assume it was a standard charge.
      } else {
        if (!Strings.isNullOrEmpty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayDepositId)
            && deal.getProperties().getOtherProperties().get(env.getConfig().hubspot.fieldDefinitions.paymentGatewayDepositId) == null) {
          DealProperties dealProperties = new DealProperties();

          setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayDepositId, paymentGatewayEvent.getDepositId(), dealProperties.getOtherProperties());
          setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayDepositDate, GregorianCalendar.from(paymentGatewayEvent.getDepositDate()), dealProperties.getOtherProperties());
          setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayDepositNetAmount, paymentGatewayEvent.getTransactionNetAmountInDollars(), dealProperties.getOtherProperties());
          setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayDepositFee, paymentGatewayEvent.getTransactionFeeInDollars(), dealProperties.getOtherProperties());

          hsClient.deal().update(donation.get().id, dealProperties);
        } else {
          log.info("skipping {}; already marked with deposit info", donation.get().id);
        }
      }
    }

    // TODO: Break out a set-fields method? Or just allow this whole thing to be overridden?
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: campaign

    DealProperties deal = new DealProperties();
    setRecurringDonationFields(deal, paymentGatewayEvent);

    Deal response = hsClient.deal().insert(deal);
    if (response != null) {
      if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().id)) {
        hsClient.association().insert("deal", response.getId(), "company", paymentGatewayEvent.getCrmAccount().id);
      }
      if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().id)) {
        hsClient.association().insert("deal", response.getId(), "contact", paymentGatewayEvent.getCrmContact().id);
      }

      return response.getId();
    } else {
      return null;
    }
  }

  protected void setRecurringDonationFields(DealProperties deal, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: campaign

    deal.setPipeline(env.getConfig().hubspot.recurringDonationPipeline.id);
    deal.setDealstage(env.getConfig().hubspot.recurringDonationPipeline.openStageId);

    deal.setClosedate(GregorianCalendar.from(paymentGatewayEvent.getTransactionDate()));
    deal.setDealname("Recurring Donation: " + paymentGatewayEvent.getCrmContact().fullName());

    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayName, paymentGatewayEvent.getGatewayName(), deal.getOtherProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewaySubscriptionId, paymentGatewayEvent.getSubscriptionId(), deal.getOtherProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayCustomerId, paymentGatewayEvent.getCustomerId(), deal.getOtherProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.fund, paymentGatewayEvent.getMetadataValue(env.getConfig().metadataKeys.fund), deal.getOtherProperties());

    CrmRecurringDonation.Frequency frequency = CrmRecurringDonation.Frequency.fromName(paymentGatewayEvent.getSubscriptionInterval());
    setProperty(env.getConfig().hubspot.fieldDefinitions.recurringDonationFrequency, frequency.name().toLowerCase(Locale.ROOT), deal.getOtherProperties());

    double amount = paymentGatewayEvent.getSubscriptionAmountInDollars();
    // HS doesn't support non-monthly intervals natively. So, we must divide the amount into a monthly rate for
    // recurring revenue forecasts to work correctly. Ex: Quarterly gift of $90 becomes $30/month.
    switch (frequency) {
      case QUARTERLY -> amount = amount / 4.0;
      case YEARLY ->  amount = amount / 12.0;
      case BIANNUALLY -> amount = amount / 24.0;
    }

    if (env.getConfig().hubspot.enableRecurring) {
      deal.setRecurringRevenueDealType("NEW_BUSINESS");
      deal.setRecurringRevenueAmount(amount);
      // set the original amount as well, needed for display purposes
      setProperty(env.getConfig().hubspot.fieldDefinitions.recurringDonationRealAmount, paymentGatewayEvent.getSubscriptionAmountInDollars(), deal.getOtherProperties());
    } else {
      deal.setAmount(amount);
    }
  }

  @Override
  public void closeRecurringDonation(CrmRecurringDonation crmRecurringDonation) throws Exception {
    DealProperties dealProperties = new DealProperties();
    dealProperties.setDealstage(env.getConfig().hubspot.recurringDonationPipeline.closedStageId);
    if (env.getConfig().hubspot.enableRecurring) {
      dealProperties.setRecurringRevenueInactiveDate(Calendar.getInstance());
      dealProperties.setRecurringRevenueInactiveReason("CHURNED");
    }
    setRecurringDonationFieldsForClose(dealProperties, crmRecurringDonation);

    hsClient.deal().update(crmRecurringDonation.id, dealProperties);
  }

  // Give orgs an opportunity to clear anything else out that's unique to them, prior to the update
  protected void setRecurringDonationFieldsForClose(DealProperties deal,
      CrmRecurringDonation crmRecurringDonation) throws Exception {
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    Deal deal = hsClient.deal().read(id, dealFields);
    CrmRecurringDonation crmRecurringDonation = toCrmRecurringDonation(deal);
    return Optional.of(crmRecurringDonation);
  }

  @Override
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    DealProperties dealProperties = new DealProperties();

    CrmRecurringDonation crmRecurringDonation = manageDonationEvent.getCrmRecurringDonation();

    if (crmRecurringDonation.amount != null && crmRecurringDonation.amount > 0) {
      dealProperties.setAmount(crmRecurringDonation.amount);
      log.info("Updating amount to {}...", crmRecurringDonation.amount);
    }
    if (manageDonationEvent.getNextPaymentDate() != null) {
      // TODO
    }

    if (manageDonationEvent.getPauseDonation() == true) {
      dealProperties.setDealstage(env.getConfig().hubspot.recurringDonationPipeline.closedStageId);
      // TODO: Close reason?

      if (manageDonationEvent.getPauseDonationUntilDate() == null) {
        log.info("pausing {} indefinitely...", crmRecurringDonation.id);
      } else {
        log.info("pausing {} until {}...", crmRecurringDonation.id, manageDonationEvent.getPauseDonationUntilDate().getTime());
      }
      setRecurringDonationFieldsForPause(dealProperties, manageDonationEvent);
    }

    if (manageDonationEvent.getResumeDonation() == true) {
      // TODO: Likely a new Deal with type/dates set appropriately
    }

    hsClient.deal().update(crmRecurringDonation.id, dealProperties);
  }

  // Give orgs an opportunity to set anything else that's unique to them, prior to pause
  protected void setRecurringDonationFieldsForPause(DealProperties deal,
      ManageDonationEvent manageDonationEvent) throws Exception {
  }

  @Override
  public void addContactToList(CrmContact crmContact, String listId) throws Exception {
    // note that HubSpot auto-prevents duplicate entries in lists
    // TODO: shift to V3
    HubSpotClientFactory.v1Client(env).contactList().addContactToList(Long.parseLong(listId), Long.parseLong(crmContact.id));
    log.info("added HubSpot contact {} to list {}", crmContact.id, listId);
  }

  @Override
  public List<CrmContact> getContactsFromList(String listId) throws Exception {
    ContactArray contactArray = HubSpotClientFactory.v1Client(env).contactList().getContactsInList(Long.parseLong(listId), env.getConfig().hubspot.customQueryFields.contact);
    return toCrmContact(contactArray);
  }

  @Override
  public void removeContactFromList(CrmContact crmContact, String listId) throws Exception {
    if (Strings.isNullOrEmpty(listId)) {
      String defaultListId = env.getConfig().hubspot.defaultSmsOptInList;
      if (Strings.isNullOrEmpty(defaultListId)) {
        log.info("explicit HubSpot list ID not provided; skipping the list removal...");
        return;
      } else {
        log.info("explicit HubSpot list ID not provided; using the default {}", defaultListId);
        listId = defaultListId;
      }

      // TODO: shift to V3
      HubSpotClientFactory.v1Client(env).contactList().removeContactFromList(Long.parseLong(listId), Long.parseLong(crmContact.id));
      log.info("removed HubSpot contact {} from list {}", crmContact.id, listId);
    }
  }

  @Override
  public String insertOpportunity(CrmOpportunity crmOpportunity) throws Exception {
    throw new RuntimeException("not implemented");
  }

  // TODO: imports are being reworked in a different PR, so purely commenting these out for now

  @Override
  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
//    String contactKeyPrefix = "contact_";
//    String dealKeyPrefix = "deal_";
//
//    List<Map<String, String>> listOfMap = toImportList(importEvents, contactKeyPrefix, dealKeyPrefix);
//    importRecords(listOfMap, "contact-deal-bulk-import", contactKeyPrefix, dealKeyPrefix);
//
//    log.info("bulk insert complete");
  }

//  @Override
//  public void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception {
//    String contactKeyPrefix = "contact_";
//    String dealKeyPrefix = "deal_";
//
//    // Updating contacts / deals in a separate requests
//    List<Map<String, String>> listOfContactMap = toUpdateContactsList(updateEvents, contactKeyPrefix);
//    importRecords(listOfContactMap, "contact-bulk-update", contactKeyPrefix, dealKeyPrefix);
//
//    List<Map<String, String>> listOfDealMaps = toUpdateDealsList(updateEvents, dealKeyPrefix);
//    importRecords(listOfDealMaps, "deal-bulk-update", contactKeyPrefix, dealKeyPrefix);
//
//    log.info("bulk update complete");
//  }
//
//  private List<Map<String, String>> toImportList(List<CrmImportEvent> importEvents, String contactKeyPrefix, String dealKeyPrefix) {
//    List<Map<String, String>> listOfMap = importEvents.stream()
//        .map(importEvent -> {
//          Map<String, String> contactPropertiesMap = toContactPropertiesMap(importEvent);
//          Map<String, String> dealPropertiesMap = toDealPropertiesMap(importEvent);
//
//          Map<String, String> csvMap = new HashMap<>();
//          contactPropertiesMap.forEach((k, v) -> csvMap.put(contactKeyPrefix + k, v));
//          dealPropertiesMap.forEach((k, v) -> csvMap.put(dealKeyPrefix + k, v));
//          return csvMap;
//        })
//        .collect(Collectors.toList());
//    return listOfMap;
//  }
//
//  private List<Map<String, String>> toUpdateContactsList(List<CrmUpdateEvent> updateEvents, String contactKeyPrefix) {
//    List<Map<String, String>> listOfMap = updateEvents.stream()
//        .filter(updateEvent ->
//            !Strings.isNullOrEmpty(updateEvent.getContactId())
//                || !Strings.isNullOrEmpty(updateEvent.getContactEmail()))
//        .map(updateEvent -> {
//          Map<String, String> contactPropertiesMap = toContactPropertiesUpdateMap(updateEvent);
//
//          Map<String, String> csvMap = new HashMap<>();
//          contactPropertiesMap.forEach((k, v) -> csvMap.put(contactKeyPrefix + k, v));
//          return csvMap;
//        })
//        .collect(Collectors.toList());
//    return listOfMap;
//  }
//
//  private List<Map<String, String>> toUpdateDealsList(List<CrmUpdateEvent> updateEvents, String dealKeyPrefix) {
//    List<Map<String, String>> listOfMap = updateEvents.stream()
//        .filter(updateEvent -> !Strings.isNullOrEmpty(updateEvent.getOpportunityId()))
//        .map(updateEvent -> {
//          Map<String, String> dealPropertiesMap = toDealPropertiesUpdateMap(updateEvent);
//
//          Map<String, String> csvMap = new HashMap<>();
//          dealPropertiesMap.forEach((k, v) -> csvMap.put(dealKeyPrefix + k, v));
//          return csvMap;
//        })
//        .collect(Collectors.toList());
//    return listOfMap;
//  }
//
//  private Map<String, String> toContactPropertiesMap(CrmImportEvent crmImportEvent) {
//    Map<String, String> map = new HashMap<>();
//    map.put("hubspot_owner_id", crmImportEvent.getOwnerId());
//    if (crmImportEvent.getContactId() != null) {
//      map.put("id", crmImportEvent.getContactId());
//    }
//    if (Strings.isNullOrEmpty(crmImportEvent.getContactLastName())) {
//      map.put("lastname", "Anonymous");
//    } else {
//      map.put("firstname", crmImportEvent.getContactFirstName());
//      map.put("lastname", crmImportEvent.getContactLastName());
//    }
//    // address
//    map.put("address", crmImportEvent.getContactMailingStreet());
//    map.put("city", crmImportEvent.getContactMailingCity());
//    map.put("state", crmImportEvent.getContactMailingState());
//    map.put("zip", crmImportEvent.getContactMailingZip());
//    map.put("country", crmImportEvent.getContactMailingCountry());
//    // phone/email
//    map.put("phone", crmImportEvent.getContactHomePhone());
//    map.put("mobilephone", crmImportEvent.getContactMobilePhone());
//    map.put("email", crmImportEvent.getContactEmail());
//
//    crmImportEvent.getRaw().entrySet().stream()
//        .filter(entry -> entry.getKey().startsWith("Contact Custom "))
//        .forEach(entry -> map.put(
//            entry.getKey().replace("Contact Custom ", ""),
//            entry.getValue()));
//    return map;
//  }
//
//  private Map<String, String> toDealPropertiesMap(CrmImportEvent crmImportEvent) {
//    Map<String, String> map = new HashMap<>();
//    map.put("hubspot_owner_id", crmImportEvent.getOwnerId());
//    if (Strings.isNullOrEmpty(crmImportEvent.getOpportunityId())) {
//      map.put("id", crmImportEvent.getOpportunityId());
//    }
//    map.put("dealname", crmImportEvent.getOpportunityName());
//    map.put("description", crmImportEvent.getOpportunityDescription());
//    if (crmImportEvent.getOpportunityAmount() != null) {
//      map.put("amount", "" + crmImportEvent.getOpportunityAmount().doubleValue());
//    }
//    map.put("dealstage", crmImportEvent.getOpportunityStageName());
//    if (crmImportEvent.getOpportunityDate() != null) {
//      DateFormat dateFormat = new SimpleDateFormat("MM/dd/yyyy");
//      dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
//      map.put("closedate", dateFormat.format(crmImportEvent.getOpportunityDate().getTime()));
//    }
//
//    crmImportEvent.getRaw().entrySet().stream()
//        .filter(entry -> entry.getKey().startsWith("Opportunity Custom "))
//        .forEach(entry -> map.put(entry.getKey().replace("Opportunity Custom ", ""), entry.getValue()));
//    return map;
//  }

  private File toCsvFile(String prefix, List<Map<String, String>> listOfMap) throws Exception {
    // Create temp csv file out of mapped data (key -> header, value -> row)
    Set<String> keys = listOfMap.stream()
      .map(Map::keySet)
      .flatMap(Collection::stream)
      .collect(Collectors.toSet());

    CsvSchema.Builder schemaBuilder = CsvSchema.builder();
    for (String key : keys) {
      schemaBuilder.addColumn(key);
    }
    CsvSchema csvSchema = schemaBuilder.build().withHeader();

    log.debug("Creating temp file...");
    File csvTempFile = File.createTempFile(prefix, ".csv", new File("."));
    log.debug("Temp file created. File path: {}", csvTempFile.getAbsolutePath());

    Writer writer = new FileWriter(csvTempFile);
    CsvMapper csvMapper = new CsvMapper();
    csvMapper.configure(JsonGenerator.Feature.IGNORE_UNKNOWN, true);
    csvMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
    csvMapper.writer(csvSchema).writeValues(writer).writeAll(listOfMap);
    writer.flush();
    return csvTempFile;
  }

  private ImportRequest toImportRequest(File csvTempFile, List<Map<String, String>> listOfMap, String contactKeyPrefix, String dealKeyPrefix) {
    // Create import request mappings
    Set<String> keys = listOfMap.stream()
        .map(Map::keySet)
        .flatMap(Collection::stream)
        .collect(Collectors.toSet());
    List<ColumnMapping> columnMappings = new ArrayList<>();

    for (String key : keys) {
      ColumnMapping columnMapping = new ColumnMapping();
      columnMapping.setColumnName(key);

      if (key.startsWith(contactKeyPrefix)) {
        // Contact column
        columnMapping.setColumnObjectTypeId("0-1"); // Contact
        String propertyName = key.replace(contactKeyPrefix, "");
        columnMapping.setPropertyName(propertyName);

        if ("id".equalsIgnoreCase(propertyName)) {
          columnMapping.setIdColumnType("HUBSPOT_OBJECT_ID");
        }
        if ("email".equalsIgnoreCase(propertyName)) {
          columnMapping.setIdColumnType("HUBSPOT_ALTERNATE_ID");
        }
        columnMappings.add(columnMapping);
      } else if (key.startsWith(dealKeyPrefix)) {
        // Deal column
        columnMapping.setColumnObjectTypeId("0-3"); // Deal
        String propertyName = key.replace(dealKeyPrefix, "");
        columnMapping.setPropertyName(propertyName);

        if ("id".equalsIgnoreCase(propertyName)) {
          columnMapping.setIdColumnType("HUBSPOT_OBJECT_ID");
        }

        columnMappings.add(columnMapping);
      } else {
        // ignore unknown keys
      }
    }

    FileImportPage fileImportPage = new FileImportPage();
    fileImportPage.setHasHeader(Boolean.TRUE);
    fileImportPage.setColumnMappings(columnMappings);

    ImportFile importFile = new ImportFile();
    importFile.setFileName(csvTempFile.getName());
    importFile.setFileFormat("CSV");
    importFile.setDateFormat("MONTH_DAY_YEAR");
    importFile.setFileImportPage(fileImportPage);

    ImportRequest importRequest = new ImportRequest();
    importRequest.setName("CRM Contact-Deal Import " + new Date());
    importRequest.setFiles(List.of(importFile));

    return importRequest;
  }

  private ImportResponse importRecords(List<Map<String, String>> listOfMap, String fileNamePrefix, String contactKeyPrefix, String dealKeyPrefix) throws Exception {
    if (CollectionUtils.isEmpty(listOfMap)) {
      return null;
    }
    File csv = null;
    try {
      // Generate temp csv file
      csv = toCsvFile(fileNamePrefix, listOfMap);
      ImportRequest importRequest = toImportRequest(csv, listOfMap, contactKeyPrefix, dealKeyPrefix);
      ImportResponse importResponse = importsClient.importFiles(importRequest, csv);
      log.info("importResponse: {}", importResponse);
      return importResponse;

    } catch (Exception e) {
      if (csv != null) {
        log.debug("Deleting temp file {}...", csv.getName());
        csv.delete();
        log.debug("Temp file {} deleted.", csv.getName());
      }
      throw e;
    }
  }

  @Override
  public List<CrmContact> getEmailContacts(Calendar updatedSince, String filter) throws Exception {
    List<Filter> filters = new ArrayList<>();
    filters.add(new Filter("email", "HAS_PROPERTY", null));
    if (updatedSince != null) {
      String dateString = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(updatedSince.getTime());
      filters.add(new Filter("lastmodifieddate", "gte", dateString));
    }

    // TODO: more than one query? support and vs. or?
    if (!Strings.isNullOrEmpty(filter)) {
      // TODO: more than one query? support and vs. or?
      // ex: type eq Foo Bar <-- note that Foo Bar needs to be reassembled back into one value
      String[] filterSplit = filter.split(" ");
      String filterValue = filter.replace(filterSplit[0], "").replace(filterSplit[1], "").trim();
      filters.add(new Filter(filterSplit[0], filterSplit[1], filterValue));
    }

//    List<FilterGroup> filterGroups = List.of(new FilterGroup(filters));
//    ContactResults results = hsClient.contact().search(filterGroups, getCustomFieldNames());
//    return results.getResults().stream().map(this::toCrmContact).collect(Collectors.toList());

    List<FilterGroup> filterGroups = List.of(new FilterGroup(filters));
    List<Contact> results = hsClient.contact().searchAutoPaging(filterGroups, getCustomFieldNames());
    return results.stream().map(this::toCrmContact).collect(Collectors.toList());
  }

  @Override
  public Map<String, List<String>> getActiveCampaignsByContactIds(List<String> contactIds) throws Exception {
    // TODO
    return Collections.emptyMap();
  }

  @Override
  public double getDonationsTotal(String filter) throws Exception {
    if (Strings.isNullOrEmpty(filter)) {
      log.warn("no filter provided; out of caution, skipping the query to protect API limits");
      return 0.0;
    }

    List<Filter> filters = new ArrayList<>();

    // TODO: more than one query? support and vs. or?
    // ex: type eq Foo Bar <-- note that Foo Bar needs to be reassembled back into one value
    String[] filterSplit = filter.split(" ");
    String filterValue = filter.replace(filterSplit[0], "").replace(filterSplit[1], "").trim();
    filters.add(new Filter(filterSplit[0], filterSplit[1], filterValue));

    // only successful transaction deals
    filters.add(new Filter("pipeline", "EQ", env.getConfig().hubspot.donationPipeline.id));
    filters.add(new Filter("dealstage", "EQ", env.getConfig().hubspot.donationPipeline.successStageId));

    List<FilterGroup> filterGroups = List.of(new FilterGroup(filters));
    List<Deal> results = hsClient.deal().searchAutoPaging(filterGroups, getCustomFieldNames());
    return results.stream().map(d -> d.getProperties().getAmount()).reduce(0.0, Double::sum);
  }

  protected CrmAccount toCrmAccount(Company company) {
    CrmAddress crmAddress = new CrmAddress(
        company.getProperties().getAddress(),
        company.getProperties().getCity(),
        company.getProperties().getState(),
        company.getProperties().getZip(),
        company.getProperties().getCountry()
    );

    return new CrmAccount(
        company.getId(),
        company.getProperties().getName(),
        crmAddress,
        // TODO: Differentiate between Household and Organization?
        EnvironmentConfig.AccountType.HOUSEHOLD,
        company,
        "https://app.hubspot.com/contacts/" + env.getConfig().hubspot.portalId + "/company/" + company.getId()
    );
  }

  protected CrmContact toCrmContact(Contact contact) {
    CrmAddress crmAddress = new CrmAddress(
        contact.getProperties().getAddress(),
        contact.getProperties().getCity(),
        contact.getProperties().getState(),
        contact.getProperties().getZip(),
        contact.getProperties().getCountry()
    );

    // TODO
    CrmContact.PreferredPhone preferredPhone = null;

    return new CrmContact(
        contact.getId(),
        contact.getProperties().getAssociatedcompanyid(),
        contact.getProperties().getFirstname(),
        contact.getProperties().getLastname(),
        null,
        contact.getProperties().getEmail(),
        // TODO: need the breakdown of phones here
        null, // home phone
        contact.getProperties().getMobilephone(),
        null, // work phone
        preferredPhone,
        crmAddress,
        getPropertyBoolean(env.getConfig().hubspot.fieldDefinitions.emailOptIn, contact.getProperties().getOtherProperties()),
        getPropertyBoolean(env.getConfig().hubspot.fieldDefinitions.emailOptOut, contact.getProperties().getOtherProperties()),
        getPropertyBoolean(env.getConfig().hubspot.fieldDefinitions.smsOptIn, contact.getProperties().getOtherProperties()),
        getPropertyBoolean(env.getConfig().hubspot.fieldDefinitions.smsOptOut, contact.getProperties().getOtherProperties()),
        contact.getProperties().getOwnerId(),
        // TODO: tagging fields
        null, null, null, null, null,
        // TODO: email groups
        Collections.emptyList(),
        (String) getProperty(env.getConfig().hubspot.fieldDefinitions.contactLanguage, contact.getProperties().getOtherProperties()),
        contact,
        "https://app.hubspot.com/contacts/" + env.getConfig().hubspot.portalId + "/contact/" + contact.getId(),
        fieldName -> contact.getProperties().getOtherProperties().get(fieldName)
    );
  }

  protected Optional<CrmContact> toCrmContact(Optional<Contact> contact) {
    return contact.map(this::toCrmContact);
  }

  protected List<CrmContact> toCrmContact(List<Contact> contacts) {
    return contacts.stream()
        .map(this::toCrmContact)
        .collect(Collectors.toList());
  }

  // V1 option, temporarily
  protected CrmContact toCrmContact(com.impactupgrade.integration.hubspot.v1.model.Contact contact) {
    // TODO: Data missing from our Java client's v1 flavor, but this list is ultimately needed
    //  for simple use cases like outbound messages.
    return new CrmContact(
        contact.getVid() + "",
        null,
        getValue(contact.getProperties().getFirstname()),
        getValue(contact.getProperties().getLastname()),
        null,
        getValue(contact.getProperties().getEmail()),
        getValue(contact.getProperties().getPhone()),
        getValue(contact.getProperties().getMobilePhone()),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Collections.emptyList(),
        getValue(env.getConfig().hubspot.fieldDefinitions.contactLanguage, contact.getProperties().getOtherProperties()),
        null,
        null,
        null
    );
  }

  private String getValue(HasValue<String> value) {
    return value == null ? null : value.getValue();
  }

  protected String getValue(String fieldName, Map<String, HasValue<String>> customProperties) {
    // Optional field names may not be configured in env.json, so ensure we actually have a name first...
    if (Strings.isNullOrEmpty(fieldName)) {
      return null;
    }

    return getValue(customProperties.get(fieldName));
  }

  // V1 option, temporarily
  protected List<CrmContact> toCrmContact(ContactArray contactArray) {
    return contactArray.getContacts().stream()
        .map(this::toCrmContact)
        .collect(Collectors.toList());
  }

  protected List<CrmDonation> toCrmDonation(List<Deal> deals) {
    return deals.stream()
        .map(this::toCrmDonation)
        .collect(Collectors.toList());
  }

  protected CrmDonation toCrmDonation(Deal deal) {
    String paymentGatewayName = (String) getProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayName, deal.getProperties().getOtherProperties());
    CrmDonation.Status status;
    if (env.getConfig().hubspot.donationPipeline.successStageId.equalsIgnoreCase(deal.getProperties().getDealstage())) {
      status = CrmDonation.Status.SUCCESSFUL;
    } else if (env.getConfig().hubspot.donationPipeline.failedStageId.equalsIgnoreCase(deal.getProperties().getDealstage())) {
      status = CrmDonation.Status.FAILED;
    } else {
      status = CrmDonation.Status.PENDING;
    }
    return new CrmDonation(
        deal.getId(),
        deal.getProperties().getDealname(),
        deal.getProperties().getAmount(),
        paymentGatewayName,
        null, // TODO: transactionId, may need otherProperties added to the HS lib?
        status,
        deal.getProperties().getClosedate(),
        deal.getProperties().getDescription(),
        null, // campaignId
        deal.getProperties().getOwnerId(),
        null, // recordTypeId
        null, null,
        deal,
        "https://app.hubspot.com/contacts/" + env.getConfig().hubspot.portalId + "/deal/" + deal.getId()
    );
  }

  protected List<CrmRecurringDonation> toCrmRecurringDonation(List<Deal> deals) {
    return deals.stream()
        .map(this::toCrmRecurringDonation)
        .collect(Collectors.toList());
  }

  protected CrmRecurringDonation toCrmRecurringDonation(Deal deal) {
    CrmRecurringDonation.Frequency frequency = CrmRecurringDonation.Frequency.fromName(
        (String) getProperty(env.getConfig().hubspot.fieldDefinitions.recurringDonationFrequency, deal.getProperties().getOtherProperties()));

    return new CrmRecurringDonation(
        deal.getId(),
        (String) getProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewaySubscriptionId, deal.getProperties().getOtherProperties()),
        (String) getProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayCustomerId, deal.getProperties().getOtherProperties()),
        deal.getProperties().getAmount(),
        (String) getProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayName, deal.getProperties().getOtherProperties()),
        deal.getProperties().getDealstage().equalsIgnoreCase(env.getConfig().hubspot.recurringDonationPipeline.openStageId),
        frequency,
        deal.getProperties().getDealname(),
        null,
        null,
        deal,
        "https://app.hubspot.com/contacts/" + env.getConfig().hubspot.portalId + "/deal/" + deal.getId()
    );
  }

  protected Object getProperty(String fieldName, Map<String, Object> customProperties) {
    // Optional field names may not be configured in env.json, so ensure we actually have a name first...
    if (Strings.isNullOrEmpty(fieldName)) {
      return null;
    }

    return customProperties.get(fieldName);
  }

  protected Boolean getPropertyBoolean(String fieldName, Map<String, Object> customProperties) {
    String value = (String) getProperty(fieldName, customProperties);

    if (Strings.isNullOrEmpty(value)) {
      return null;
    }

    return Boolean.valueOf(value);
  }

  protected void setProperty(String fieldName, Object value, Map<String, Object> customProperties) {
    // Optional field names may not be configured in env.json, so ensure we actually have a name first...
    // Likewise, don't set a null or empty value.
    if (Strings.isNullOrEmpty(fieldName) || value == null) {
      return;
    }

    customProperties.put(fieldName, value);
  }

  protected Set<String> getCustomFieldNames() {
    return Arrays.stream(EnvironmentConfig.CRMFieldDefinitions.class.getFields()).map(f -> {
      try {
        return f.get(env.getConfig().hubspot.fieldDefinitions).toString();
      } catch (IllegalAccessException e) {
        log.error("failed to retrieve custom field names from schema", e);
        return "";
      }
    }).filter(f -> !Strings.isNullOrEmpty(f)).collect(Collectors.toSet());
  }

  // TODO: leaving this here in case it's helpful for eventual bulk import support
//  public static void main(String[] args) throws IOException, InterruptedException {
//    HubSpotCrmV3Client hsV3Client = HubSpotClientFactory.v3Client();
//    HubSpotV1Client hsV1Client = HubSpotClientFactory.v1Client();
//
//    CSVParser csvParser = CSVParser.parse(
//        new File("/home/brmeyer/Downloads/Impact Upgrade contacts 2021-07-17 19-27.csv"),
//        Charset.defaultCharset(),
//        CSVFormat.DEFAULT
//            .withFirstRecordAsHeader()
//            .withIgnoreHeaderCase()
//            .withTrim()
//    );
//    for (CSVRecord csvRecord : csvParser) {
//      try {
//        Map<String, String> data = csvRecord.toMap();
//
//        Filter[] filters = new Filter[]{new Filter("close_lead_id", "EQ", data.get("lead_id"))};
//        CompanyResults companies = hsV3Client.company().search(filters, Collections.emptyList());
//        Company company = companies.getResults().get(0);
//
//        ContactProperties contactProperties = new ContactProperties();
//        contactProperties.setAssociatedcompanyid(company.getId());
//        contactProperties.getOtherProperties().put("close_contact_id", data.get("id"));
//        contactProperties.setFirstname(data.get("first_name"));
//        contactProperties.setFirstname(data.get("last_name"));
//        contactProperties.getOtherProperties().put("jobtitle", data.get("title"));
//        contactProperties.setPhone(data.get("primary_phone"));
//        contactProperties.setEmail(data.get("primary_email"));
//
//        System.out.println(company.getProperties().getName() + ": " + contactProperties.getEmail());
//        Thread.sleep(200);
//
//        hsV3Client.contact().insert(contactProperties);
//      } catch (Exception e) {
//        e.printStackTrace();
//      }
//    }
//
//    HttpAuthenticationFeature auth = HttpAuthenticationFeature.basic("api_66jMEXUK2CAyVwuNs3v7Rj.6FUfLAGSGRA3QGfICQw8Px", "");
//    int offset = 0;
//    boolean hasMore = true;
//    while (hasMore) {
//      try {
//        Client client = ClientBuilder.newClient().register(auth);
//        WebTarget webTarget = client.target("https://api.close.com/api/v1/activity/note/?_skip=" + offset + "&_limit=10");
//        Invocation.Builder invocationBuilder = webTarget.request(MediaType.TEXT_PLAIN);
//        Response response = invocationBuilder.get();
//        String json = response.readEntity(String.class);
//        Gson gson = new GsonBuilder().create();
//        NoteResponse noteResponse = gson.fromJson(json, NoteResponse.class);
//
//        for (Note note : noteResponse.data) {
//          try {
//            Filter[] filters = new Filter[]{new Filter("close_lead_id", "EQ", note.lead_id)};
//            CompanyResults companies = hsV3Client.company().search(filters, Collections.emptyList());
//            Company company = companies.getResults().get(0);
//
//            System.out.println(offset + " " + note.lead_id + " " + company.getId() + ": " + note.note + " " + note.date_created);
//
//            EngagementRequest engagementRequest = new EngagementRequest();
//            Engagement engagement = new Engagement();
//            engagement.setType("NOTE");
//            engagementRequest.setEngagement(engagement);
//            EngagementAssociations associations = new EngagementAssociations();
//            associations.setCompanyIds(List.of(Long.parseLong(company.getId())));
//            engagementRequest.setAssociations(associations);
//            EngagementNoteMetadata metadata = new EngagementNoteMetadata();
//            metadata.setBody(note.date_created + ": " + note.note);
//            engagementRequest.setMetadata(metadata);
//            hsV1Client.engagement().insert(engagementRequest);
//
//            Thread.sleep(200);
//          } catch (Exception e2) {
//            e2.printStackTrace();
//          }
//        }
//
//        hasMore = noteResponse.has_more;
//        offset += 10;
//      } catch (Exception e1) {
//        e1.printStackTrace();;
//      }
//    }
//
//    HttpAuthenticationFeature auth = HttpAuthenticationFeature.basic("api_66jMEXUK2CAyVwuNs3v7Rj.6FUfLAGSGRA3QGfICQw8Px", "");
//    int offset = 0;
//    boolean hasMore = true;
//    while (hasMore) {
//      try {
//        Client client = ClientBuilder.newClient().register(auth);
//        WebTarget webTarget = client.target("https://api.close.com/api/v1/activity/call/?_skip=" + offset + "&_limit=10");
//        Invocation.Builder invocationBuilder = webTarget.request(MediaType.TEXT_PLAIN);
//        Response response = invocationBuilder.get();
//        String json = response.readEntity(String.class);
//        Gson gson = new GsonBuilder().create();
//        CallResponse callResponse = gson.fromJson(json, CallResponse.class);
//
//        for (Call call : callResponse.data) {
//          try {
//            if (Strings.isNullOrEmpty(call.lead_id)) {
//              continue;
//            }
//            Filter[] filters = new Filter[]{new Filter("close_lead_id", "EQ", call.lead_id)};
//            CompanyResults companies = hsV3Client.company().search(filters, Collections.emptyList());
//            Company company = companies.getResults().get(0);
//
//            System.out.println(offset + " " + call.lead_id + " " + company.getId() + ": " + call.note + " " + call.date_created);
//
//            EngagementRequest engagementRequest = new EngagementRequest();
//            Engagement engagement = new Engagement();
//            engagement.setType("NOTE");
//            engagementRequest.setEngagement(engagement);
//            EngagementAssociations associations = new EngagementAssociations();
//            associations.setCompanyIds(List.of(Long.parseLong(company.getId())));
//            engagementRequest.setAssociations(associations);
//            EngagementNoteMetadata metadata = new EngagementNoteMetadata();
//            metadata.setBody("CALLED " + call.date_created + ": " + call.note);
//            engagementRequest.setMetadata(metadata);
//            hsV1Client.engagement().insert(engagementRequest);
//
//            Thread.sleep(200);
//          } catch (Exception e2) {
//            e2.printStackTrace();
//          }
//        }
//
//        hasMore = callResponse.has_more;
//        offset += 10;
//      } catch (Exception e1) {
//        e1.printStackTrace();;
//      }
//    }
//
//    CSVParser csvParser = CSVParser.parse(
//        new File("/home/brmeyer/Downloads/Impact Upgrade contacts 2021-07-17 19-27.csv"),
//        Charset.defaultCharset(),
//        CSVFormat.DEFAULT
//            .withFirstRecordAsHeader()
//            .withIgnoreHeaderCase()
//            .withTrim()
//    );
//    for (CSVRecord csvRecord : csvParser) {
//      try {
//        Map<String, String> data = csvRecord.toMap();
//
//        Filter[] filters = new Filter[]{new Filter("email", "EQ", data.get("primary_email"))};
//        ContactResults contacts = hsV3Client.contact().search(filters, Collections.emptyList());
//        Contact contact = contacts.getResults().get(0);
//
//        ContactProperties update = new ContactProperties();
//        update.setFirstname(data.get("first_name"));
//        update.setLastname(data.get("last_name"));
//
//        hsV3Client.contact().update(contact.getId(), update);
//
//        Thread.sleep(200);
//      } catch (Exception e) {
//        e.printStackTrace();
//      }
//    }
//  }
//
//  public static class NoteResponse {
//    public List<Note> data;
//    public Boolean has_more;
//  }
//
//  public static class Note {
//    public String note;
//    public String date_created;
//    public String lead_id;
//  }
//
//  public static class CallResponse {
//    public List<Call> data;
//    public Boolean has_more;
//  }
//
//  public static class Call {
//    public String note;
//    public String date_created;
//    public String lead_id;
//  }
}
