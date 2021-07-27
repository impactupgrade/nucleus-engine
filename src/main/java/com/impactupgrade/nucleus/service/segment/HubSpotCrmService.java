/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.integration.hubspot.v1.model.ContactArray;
import com.impactupgrade.integration.hubspot.v1.model.HasValue;
import com.impactupgrade.integration.hubspot.v3.Company;
import com.impactupgrade.integration.hubspot.v3.CompanyProperties;
import com.impactupgrade.integration.hubspot.v3.Contact;
import com.impactupgrade.integration.hubspot.v3.ContactProperties;
import com.impactupgrade.integration.hubspot.v3.Deal;
import com.impactupgrade.integration.hubspot.v3.DealProperties;
import com.impactupgrade.integration.hubspot.v3.DealResults;
import com.impactupgrade.integration.hubspot.v3.Filter;
import com.impactupgrade.integration.hubspot.v3.HubSpotV3Client;
import com.impactupgrade.nucleus.client.HubSpotClientFactory;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.OpportunityEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  protected HubSpotV3Client hsClient;

  @Override
  public String name() { return "hubspot"; }

  @Override
  public void init(Environment env) {
    this.env = env;
    hsClient = HubSpotClientFactory.v3Client(env);
  }

  @Override
  public Optional<CrmAccount> getAccountById(String id) throws Exception {
    Company company = hsClient.company().read(id, getCustomPropertyNames());
    CrmAccount crmAccount = toCrmAccount(company);
    return Optional.of(crmAccount);
  }

  @Override
  public Optional<CrmContact> getContactById(String id) throws Exception {
    Contact contact = hsClient.contact().read(id, getCustomPropertyNames());
    CrmContact crmContact = toCrmContact(contact);
    return Optional.of(crmContact);
  }

  @Override
  public Optional<CrmContact> getContactByEmail(String email) throws Exception {
    return hsClient.contact().searchByEmail(email, getCustomPropertyNames()).getResults().stream().findFirst().map(this::toCrmContact);
  }

  @Override
  public Optional<CrmContact> getContactByPhone(String phone) throws Exception {
    return hsClient.contact().searchByPhone(phone, getCustomPropertyNames()).getResults().stream().findFirst().map(this::toCrmContact);
  }

  @Override
  public List<CrmDonation> getLastMonthDonationsByAccountId(String accountId) throws Exception {
    // TODO: will need to add query-by-association to HS lib
    return Collections.emptyList();
  }

  @Override
  public List<CrmDonation> getDonationsByAccountId(String accountId) throws Exception {
    // TODO: will need to add query-by-association to HS lib
    return Collections.emptyList();
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonationById(String id) throws Exception {
    Deal deal = hsClient.deal().read(id, getCustomPropertyNames());
    CrmRecurringDonation crmRecurringDonation = toCrmRecurringDonation(deal);
    return Optional.of(crmRecurringDonation);
  }

  @Override
  public List<CrmRecurringDonation> getOpenRecurringDonationsByAccountId(String accountId) throws Exception {
    // TODO: will need to add query-by-association to HS lib
    return Collections.emptyList();
  }

  @Override
  public Optional<CrmUser> getUserById(String id) throws Exception {
    // TODO: will need to add User support to HS lib, if even possible
    return Optional.empty();
  }

  @Override
  public Optional<CrmDonation> getDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Filter filter = new Filter(env.getConfig().hubspot.fieldDefinitions.paymentGatewayTransactionId, "EQ", paymentGatewayEvent.getTransactionId());
    DealResults results = hsClient.deal().search(List.of(filter), getCustomPropertyNames());

    if (results == null || results.getTotal() == 0) {
      return Optional.empty();
    }

    Deal result = results.getResults().get(0);
    String id = result.getId();
    String paymentGatewayName = (String) getProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayName, result.getProperties().getCustomProperties());
    CrmDonation.Status status;
    if (env.getConfig().hubspot.donationPipeline.successStageId.equalsIgnoreCase(result.getProperties().getDealstage())) {
      status = CrmDonation.Status.SUCCESSFUL;
    } else if (env.getConfig().hubspot.donationPipeline.failedStageId.equalsIgnoreCase(result.getProperties().getDealstage())) {
      status = CrmDonation.Status.FAILED;
    } else {
      status = CrmDonation.Status.PENDING;
    }
    return Optional.of(new CrmDonation(id, result.getProperties().getDealname(), result.getProperties().getAmount(),
        paymentGatewayName, status, result.getProperties().getClosedate()));
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Filter filter = new Filter(env.getConfig().hubspot.fieldDefinitions.paymentGatewaySubscriptionId, "EQ", paymentGatewayEvent.getSubscriptionId());
    DealResults results = hsClient.deal().search(List.of(filter), getCustomPropertyNames());

    if (results == null || results.getTotal() == 0) {
      return Optional.empty();
    }

    Deal deal = results.getResults().get(0);
    return Optional.of(toCrmRecurringDonation(deal));
  }

  @Override
  public void insertDonationReattempt(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
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

    // TODO: add/remove in default lists?
    if (crmContact.emailOptIn != null && crmContact.emailOptIn) {
      setProperty(env.getConfig().hubspot.fieldDefinitions.emailOptIn, true, contact.getCustomProperties());
      setProperty(env.getConfig().hubspot.fieldDefinitions.emailOptOut, false, contact.getCustomProperties());
    }
    if (crmContact.emailOptOut != null && crmContact.emailOptOut) {
      setProperty(env.getConfig().hubspot.fieldDefinitions.emailOptIn, false, contact.getCustomProperties());
      setProperty(env.getConfig().hubspot.fieldDefinitions.emailOptOut, true, contact.getCustomProperties());
    }

    if (crmContact.smsOptIn != null && crmContact.smsOptIn) {
      setProperty(env.getConfig().hubspot.fieldDefinitions.smsOptIn, true, contact.getCustomProperties());
      setProperty(env.getConfig().hubspot.fieldDefinitions.smsOptOut, false, contact.getCustomProperties());

      String defaultListId = env.getConfig().hubspot.defaultSmsOptInList;
      if (!Strings.isNullOrEmpty(defaultListId)) {
        log.info("opting into the default HubSpot list: {}", defaultListId);
        addContactToList(crmContact, defaultListId);
      }
    }
    if (crmContact.smsOptOut != null && crmContact.smsOptOut) {
      setProperty(env.getConfig().hubspot.fieldDefinitions.smsOptIn, false, contact.getCustomProperties());
      setProperty(env.getConfig().hubspot.fieldDefinitions.smsOptOut, true, contact.getCustomProperties());

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
      hsClient.association().insert("deal", response.getId(), "company", paymentGatewayEvent.getCrmAccount().id);
      hsClient.association().insert("deal", response.getId(), "contact", paymentGatewayEvent.getCrmContact().id);

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

    deal.setClosedate(paymentGatewayEvent.getTransactionDate());
    deal.setDescription(paymentGatewayEvent.getTransactionDescription());
    deal.setDealname("Donation: " + paymentGatewayEvent.getCrmAccount().name);

    if (paymentGatewayEvent.isTransactionRecurring()) {
      setProperty(env.getConfig().hubspot.fieldDefinitions.recurringDonationDealId, paymentGatewayEvent.getCrmRecurringDonationId(), deal.getCustomProperties());
    }

    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayName, paymentGatewayEvent.getGatewayName(), deal.getCustomProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayTransactionId, paymentGatewayEvent.getTransactionId(), deal.getCustomProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayCustomerId, paymentGatewayEvent.getCustomerId(), deal.getCustomProperties());
    // Do NOT set subscriptionId! In getRecurringDonation, we search by that and expect only the RD to be returned.

    deal.setAmount(paymentGatewayEvent.getTransactionAmountInDollars());
    if (paymentGatewayEvent.getTransactionOriginalCurrency() != null) {
      // set the custom fields related for international donation
      setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayAmountOriginal, paymentGatewayEvent.getTransactionOriginalAmountInDollars(), deal.getCustomProperties());
      setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayAmountOriginalCurrency, paymentGatewayEvent.getTransactionOriginalCurrency(), deal.getCustomProperties());
      setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayAmountExchangeRate, paymentGatewayEvent.getTransactionExchangeRate(), deal.getCustomProperties());
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

    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayRefundId, paymentGatewayEvent.getRefundId(), deal.getCustomProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayRefundDate, paymentGatewayEvent.getRefundDate(), deal.getCustomProperties());
  }

  @Override
  public void insertDonationDeposit(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: Break out a set-fields method? Or just allow this whole thing to be overridden?

    Optional<CrmDonation> donation = getDonation(paymentGatewayEvent);

    if (donation.isEmpty()) {
      log.warn("unable to find HS donation using transaction {}", paymentGatewayEvent.getTransactionId());
      return;
    }

    DealProperties deal = new DealProperties();
    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayDepositId, paymentGatewayEvent.getDepositId(), deal.getCustomProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayDepositDate, paymentGatewayEvent.getDepositDate(), deal.getCustomProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayDepositNetAmount, paymentGatewayEvent.getTransactionNetAmountInDollars(), deal.getCustomProperties());

    hsClient.deal().update(donation.get().id, deal);
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: campaign

    DealProperties deal = new DealProperties();
    setRecurringDonationFields(deal, paymentGatewayEvent);

    Deal response = hsClient.deal().insert(deal);
    if (response != null) {
      hsClient.association().insert("deal", response.getId(), "company", paymentGatewayEvent.getCrmAccount().id);
      hsClient.association().insert("deal", response.getId(), "contact", paymentGatewayEvent.getCrmContact().id);

      return response.getId();
    } else {
      return null;
    }
  }

  protected void setRecurringDonationFields(DealProperties deal, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: campaign

    deal.setPipeline(env.getConfig().hubspot.recurringDonationPipeline.id);
    deal.setDealstage(env.getConfig().hubspot.recurringDonationPipeline.openStageId);

    // TODO: Assumed to be monthly. If quarterly/yearly support needed, will need a custom field + divide the gift into the monthly rate.
    deal.setRecurringRevenueAmount(paymentGatewayEvent.getSubscriptionAmountInDollars());
    deal.setRecurringRevenueDealType("NEW_BUSINESS");
    deal.setClosedate(paymentGatewayEvent.getTransactionDate());
    deal.setDealname("Recurring Donation: " + paymentGatewayEvent.getCrmAccount().name);

    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayName, paymentGatewayEvent.getGatewayName(), deal.getCustomProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewaySubscriptionId, paymentGatewayEvent.getSubscriptionId(), deal.getCustomProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayCustomerId, paymentGatewayEvent.getCustomerId(), deal.getCustomProperties());
  }

  @Override
  public void closeRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = getRecurringDonation(paymentGatewayEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find HS recurring donation using subscriptionId {}",
          paymentGatewayEvent.getSubscriptionId());
      return;
    }

    DealProperties deal = new DealProperties();
    setRecurringDonationFieldsForClose(deal, paymentGatewayEvent);

    hsClient.deal().update(recurringDonation.get().id, deal);
  }

  // Give orgs an opportunity to clear anything else out that's unique to them, prior to the update
  protected void setRecurringDonationFieldsForClose(DealProperties deal, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    deal.setRecurringRevenueInactiveDate(Calendar.getInstance());
    deal.setRecurringRevenueInactiveReason("CHURNED");
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    return getRecurringDonationById(manageDonationEvent.getDonationId());
  }

  @Override
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    // TODO: duplicates nearly all of SfdcCrmService...

    Optional<CrmRecurringDonation> recurringDonation = getRecurringDonation(manageDonationEvent);

    if (recurringDonation.isEmpty()) {
      if (Strings.isNullOrEmpty(manageDonationEvent.getDonationId())) {
        log.warn("unable to find HS recurring donation using donationId {}", manageDonationEvent.getDonationId());
      } else {
        log.warn("unable to find HS recurring donation using donationName {}", manageDonationEvent.getDonationName());
      }
      return;
    }

    DealProperties dealProperties = new DealProperties();
    if (manageDonationEvent.getAmount() != null && manageDonationEvent.getAmount() > 0) {
      dealProperties.setAmount(manageDonationEvent.getAmount());
      log.info("Updating amount to {}...", manageDonationEvent.getAmount());
    }
    if (manageDonationEvent.getNextPaymentDate() != null) {
      // TODO
    }
    hsClient.deal().update(manageDonationEvent.getDonationId(), dealProperties);

    if (manageDonationEvent.getPauseDonation() == true) {
      pauseRecurringDonation(manageDonationEvent);
    }

    if (manageDonationEvent.getResumeDonation() == true) {
      resumeRecurringDonation(manageDonationEvent);
    }
  }

  @Override
  public void closeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = getRecurringDonation(manageDonationEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find HS recurring donation using donationId {}",
          manageDonationEvent.getDonationId());
      return;
    }

    DealProperties dealProperties = new DealProperties();
    setRecurringDonationFieldsForClose(dealProperties, manageDonationEvent);

    hsClient.deal().update(recurringDonation.get().id, dealProperties);
  }

  // Give orgs an opportunity to clear anything else out that's unique to them, prior to the update
  protected void setRecurringDonationFieldsForClose(DealProperties deal,
      ManageDonationEvent manageDonationEvent) throws Exception {
  }

  // Give orgs an opportunity to set anything else that's unique to them, prior to pause
  protected void setRecurringDonationFieldsForPause(DealProperties deal,
      ManageDonationEvent manageDonationEvent) throws Exception {
  }

  // Give orgs an opportunity to set anything else that's unique to them, prior to resume
  protected void setRecurringDonationFieldsForResume(DealProperties deal,
      ManageDonationEvent manageDonationEvent) throws Exception {
  }

  public void pauseRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = getRecurringDonation(manageDonationEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find HS recurring donation using donationId {}", manageDonationEvent.getDonationId());
      return;
    }

    DealProperties dealProperties = new DealProperties();
    dealProperties.setDealstage(env.getConfig().hubspot.recurringDonationPipeline.closedStageId);
    // TODO: Close reason?

    if (manageDonationEvent.getPauseDonationUntilDate() == null) {
      log.info("pausing {} indefinitely...", manageDonationEvent.getDonationId());
    } else {
      log.info("pausing {} until {}...", manageDonationEvent.getDonationId(), manageDonationEvent.getPauseDonationUntilDate().getTime());
    }
    setRecurringDonationFieldsForPause(dealProperties, manageDonationEvent);
    hsClient.deal().update(manageDonationEvent.getDonationId(), dealProperties);
  }

  public void resumeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = getRecurringDonation(manageDonationEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find HS recurring donation using donationId {}", manageDonationEvent.getDonationId());
      return;
    }

    // TODO: Likely a new Deal with type/dates set appropriately
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
    ContactArray contactArray = HubSpotClientFactory.v1Client(env).contactList().getContactsInList(Long.parseLong(listId));
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
  public String insertOpportunity(OpportunityEvent opportunityEvent) throws Exception {
    throw new RuntimeException("not implemented");
  }

  @Override
  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
    throw new RuntimeException("not implemented");
  }

  @Override
  public void processBulkUpdate(List<CrmUpdateEvent> updateEvents) throws Exception {
    throw new RuntimeException("not implemented");
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
        CrmAccount.Type.HOUSEHOLD,
        (Integer) getProperty(env.getConfig().hubspot.fieldDefinitions.donationCount, company.getProperties().getCustomProperties()),
        (Double) getProperty(env.getConfig().hubspot.fieldDefinitions.donationTotal, company.getProperties().getCustomProperties()),
        (Calendar) getProperty(env.getConfig().hubspot.fieldDefinitions.firstDonationDate, company.getProperties().getCustomProperties())
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
        contact.getProperties().getEmail(),
        // TODO: need the breakdown of phones here
        null,
        contact.getProperties().getMobilephone(),
        null,
        preferredPhone,
        crmAddress,
        (Boolean) getProperty(env.getConfig().hubspot.fieldDefinitions.emailOptIn, contact.getProperties().getCustomProperties()),
        (Boolean) getProperty(env.getConfig().hubspot.fieldDefinitions.emailOptOut, contact.getProperties().getCustomProperties()),
        (Boolean) getProperty(env.getConfig().hubspot.fieldDefinitions.smsOptIn, contact.getProperties().getCustomProperties()),
        (Boolean) getProperty(env.getConfig().hubspot.fieldDefinitions.smsOptOut, contact.getProperties().getCustomProperties()),
        contact.getProperties().getOwnerId()
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
        null,
        null,
        null,
        getValue(contact.getProperties().getPhone()),
        getValue(contact.getProperties().getMobilePhone()),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
    );
  }

  private String getValue(HasValue<String> value) {
    return value == null ? null : value.getValue();
  }

  // V1 option, temporarily
  protected List<CrmContact> toCrmContact(ContactArray contactArray) {
    return contactArray.getContacts().stream()
        .map(this::toCrmContact)
        .collect(Collectors.toList());
  }

  protected CrmRecurringDonation toCrmRecurringDonation(Deal deal) {
    return new CrmRecurringDonation(
        deal.getId(),
        (String) getProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewaySubscriptionId, deal.getProperties().getCustomProperties()),
        (String) getProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayCustomerId, deal.getProperties().getCustomProperties()),
        deal.getProperties().getAmount(),
        (String) getProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayName, deal.getProperties().getCustomProperties()),
        deal.getProperties().getDealstage().equalsIgnoreCase(env.getConfig().hubspot.recurringDonationPipeline.openStageId),
        CrmRecurringDonation.Frequency.MONTHLY // HubSpot supports monthly only, currently
    );
  }

  // The HubSpot API will ignore irrelevant properties for specific objects, so just include everything we're expecting.
  protected List<String> getCustomPropertyNames() {
    return Arrays.stream(EnvironmentConfig.CRMFieldDefinitions.class.getFields()).map(f -> {
      try {
        return f.get(env.getConfig().hubspot.fieldDefinitions).toString();
      } catch (IllegalAccessException e) {
        log.error("failed to retrieve custom fields from schema", e);
        return "";
      }
    }).collect(Collectors.toList());
  }

  protected Object getProperty(String fieldName, Map<String, Object> customProperties) {
    // Optional field names may not be configured in env.json, so ensure we actually have a name first...
    if (Strings.isNullOrEmpty(fieldName)) {
      return null;
    }

    return customProperties.get(fieldName);
  }

  protected void setProperty(String fieldName, Object value, Map<String, Object> customProperties) {
    // Optional field names may not be configured in env.json, so ensure we actually have a name first...
    // Likewise, don't set a null or empty value.
    if (Strings.isNullOrEmpty(fieldName) || value == null) {
      return;
    }

    customProperties.put(fieldName, value);
  }

  // TODO: leaving this here in case it's helpful for eventual bulk import support
//  public static void main(String[] args) throws IOException, InterruptedException {
//    HubSpotV3Client hsV3Client = HubSpotClientFactory.v3Client();
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
//        contactProperties.getCustomProperties().put("close_contact_id", data.get("id"));
//        contactProperties.setFirstname(data.get("first_name"));
//        contactProperties.setFirstname(data.get("last_name"));
//        contactProperties.getCustomProperties().put("jobtitle", data.get("title"));
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
