/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.integration.hubspot.v3.Company;
import com.impactupgrade.integration.hubspot.v3.CompanyProperties;
import com.impactupgrade.integration.hubspot.v3.Contact;
import com.impactupgrade.integration.hubspot.v3.ContactProperties;
import com.impactupgrade.integration.hubspot.v3.ContactResults;
import com.impactupgrade.integration.hubspot.v3.Deal;
import com.impactupgrade.integration.hubspot.v3.DealProperties;
import com.impactupgrade.integration.hubspot.v3.DealResults;
import com.impactupgrade.integration.hubspot.v3.Filter;
import com.impactupgrade.integration.hubspot.v3.HubSpotV3Client;
import com.impactupgrade.nucleus.client.HubSpotClientFactory;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.CrmUpdateEvent;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.OpportunityEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

// TODO: At the moment, this assumes field definitions are always present in env.json! However, if situations come up
//  similar to SFDC where that won't be the case (ex: LJI/TER's split between payment gateway fields), this will need
//  sanity checks like we have in SfdcCrmService.

public class HubSpotCrmService implements CrmService {

  private static final Logger log = LogManager.getLogger(HubSpotCrmService.class);

  protected final Environment env;
  protected final HubSpotV3Client hsClient;

  public HubSpotCrmService(Environment env) {
    this.env = env;
    hsClient = HubSpotClientFactory.v3Client();
  }

  @Override
  public Optional<CrmContact> getContactByEmail(String email) throws Exception {
    return findContact("email", "EQ", email);
  }

  @Override
  public Optional<CrmContact> getContactByPhone(String phone) throws Exception {
    phone = normalizePhoneNumber(phone);
    // TODO: also need to include mobilephone
    return findContact("phone", "EQ", phone);
  }

  protected Optional<CrmContact> findContact(String propertyName, String operator, String value) throws Exception {
    Filter[] filters = new Filter[]{new Filter(propertyName, operator, value)};
    ContactResults results = hsClient.contact().search(filters, getCustomPropertyNames());

    if (results == null || results.getTotal() == 0) {
      return Optional.empty();
    }

    Contact result = results.getResults().get(0);
    CrmContact crmContact = toCrmContact(result);
    return Optional.of(crmContact);
  }

  @Override
  public Optional<CrmDonation> getDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Filter[] filters = new Filter[]{new Filter(env.getConfig().hubspot.fieldDefinitions.paymentGatewayTransactionId, "EQ", paymentGatewayEvent.getTransactionId())};
    DealResults results = hsClient.deal().search(filters, getCustomPropertyNames());

    if (results == null || results.getTotal() == 0) {
      return Optional.empty();
    }

    Deal result = results.getResults().get(0);
    String id = result.getId();
    boolean successful = env.getConfig().hubspot.donationPipeline.successStageId.equalsIgnoreCase(result.getProperties().getDealstage());
    return Optional.of(new CrmDonation(id, successful));
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Filter[] filters = new Filter[]{new Filter(env.getConfig().hubspot.fieldDefinitions.paymentGatewaySubscriptionId, "EQ", paymentGatewayEvent.getSubscriptionId())};
    DealResults results = hsClient.deal().search(filters, getCustomPropertyNames());

    if (results == null || results.getTotal() == 0) {
      return Optional.empty();
    }

    Deal result = results.getResults().get(0);
    String id = result.getId();
    return Optional.of(new CrmRecurringDonation(id));
  }

  @Override
  public void insertDonationReattempt(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    // TODO
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    // TODO
    return Optional.empty();
  }

  @Override
  public String getSubscriptionId(ManageDonationEvent manageDonationEvent) throws Exception {
    // TODO
    return null;
  }

  @Override
  public void updateRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    // TODO
  }

  @Override
  public String insertAccount(PaymentGatewayWebhookEvent paymentGatewayWebhookEvent) throws Exception {
    CompanyProperties account = new CompanyProperties();
    setAccountFields(account, paymentGatewayWebhookEvent.getCrmAccount());
    Company response = hsClient.company().insert(account);
    return response == null ? null : response.getId();
  }

  public void closeRecurringDonation(ManageDonationEvent manageDonationEvent) throws Exception {
    // TODO
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
  public String insertContact(PaymentGatewayWebhookEvent paymentGatewayWebhookEvent) throws Exception {
    return insertContact(paymentGatewayWebhookEvent.getCrmContact());
  }

  @Override
  public String insertContact(OpportunityEvent opportunityEvent) throws Exception {
    return insertContact(opportunityEvent.getCrmContact());
  }

  private String insertContact(CrmContact crmContact) throws Exception {
    ContactProperties contact = new ContactProperties();
    setContactFields(contact, crmContact);
    Contact response = hsClient.contact().insert(contact);
    return response == null ? null : response.getId();
  }

  @Override
  public void updateContact(OpportunityEvent opportunityEvent) throws Exception {
    // TODO: test with Twilio flows
    ContactProperties contact = new ContactProperties();
    setContactFields(contact, opportunityEvent.getCrmContact());
    hsClient.contact().update(opportunityEvent.getCrmContact().id, contact);
  }

  @Override
  public void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception {
    // TODO
  }

  @Override
  public void smsOptOutContact(CrmContact crmContact) throws Exception {
    removeContactFromList(crmContact, null);
    log.info("opting HubSpot contact {} out of sms...", crmContact.id);
  }

  @Override
  public void smsOptInContact(CrmContact crmContact) throws Exception {
    addContactToList(crmContact, null);
    log.info("opting HubSpot contact {} in to sms...", crmContact.id);
  }

  protected void setContactFields(ContactProperties contact, CrmContact crmContact) {
    crmContact.phone = normalizePhoneNumber(crmContact.phone);

    contact.setAssociatedcompanyid(crmContact.accountId);
    contact.setFirstname(crmContact.firstName);
    contact.setLastname(crmContact.lastName);
    contact.setEmail(crmContact.email);
    contact.setPhone(crmContact.phone);
    contact.setMobilephone(crmContact.phone);

    if (crmContact.emailOptIn != null && crmContact.emailOptIn) {
      setProperty(env.getConfig().hubspot.fieldDefinitions.emailOptIn, crmContact.emailOptIn, contact.getCustomProperties());
    }
    if (crmContact.smsOptIn != null && crmContact.smsOptIn) {
      setProperty(env.getConfig().hubspot.fieldDefinitions.smsOptIn, crmContact.smsOptIn, contact.getCustomProperties());
    }
  }

  @Override
  public String insertDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
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

  protected void setDonationFields(DealProperties deal, PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
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
  public void refundDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> donation = getDonation(paymentGatewayEvent);

    if (donation.isEmpty()) {
      log.warn("unable to find HS donation using transaction {}", paymentGatewayEvent.getTransactionId());
      return;
    }

    DealProperties deal = new DealProperties();
    setDonationRefundFields(deal, paymentGatewayEvent);

    hsClient.deal().update(donation.get().getId(), deal);
  }

  protected void setDonationRefundFields(DealProperties deal, PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    deal.setDealstage(env.getConfig().hubspot.donationPipeline.refundedStageId);

    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayRefundId, paymentGatewayEvent.getRefundId(), deal.getCustomProperties());
    setProperty(env.getConfig().hubspot.fieldDefinitions.paymentGatewayRefundDate, paymentGatewayEvent.getRefundDate(), deal.getCustomProperties());
  }

  @Override
  public void insertDonationDeposit(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
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

    hsClient.deal().update(donation.get().getId(), deal);
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
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

  protected void setRecurringDonationFields(DealProperties deal, PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
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
  public void closeRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Optional<CrmRecurringDonation> recurringDonation = getRecurringDonation(paymentGatewayEvent);

    if (recurringDonation.isEmpty()) {
      log.warn("unable to find HS recurring donation using subscriptionId {}",
          paymentGatewayEvent.getSubscriptionId());
      return;
    }

    DealProperties deal = new DealProperties();
    setRecurringDonationFieldsForClose(deal, paymentGatewayEvent);

    hsClient.deal().update(recurringDonation.get().id(), deal);
  }

  // Give orgs an opportunity to clear anything else out that's unique to them, prior to the update
  protected void setRecurringDonationFieldsForClose(DealProperties deal, PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    deal.setRecurringRevenueInactiveDate(Calendar.getInstance());
    deal.setRecurringRevenueInactiveReason("CHURNED");
  }

  @Override
  public void addContactToList(CrmContact crmContact, String listId) throws Exception {
    if (Strings.isNullOrEmpty(listId)) {
      String defaultListId = env.getConfig().hubspot.defaultSmsOptInList;
      if (Strings.isNullOrEmpty(defaultListId)) {
        log.info("explicit HubSpot list ID not provided; skipping the list insert...");
        return;
      } else {
        log.info("explicit HubSpot list ID not provided; using the default {}", defaultListId);
        listId = defaultListId;
      }
    }
    // note that HubSpot auto-prevents duplicate entries in lists
    // TODO: shift to V3
    HubSpotClientFactory.v1Client().contactList().addContactToList(Long.parseLong(listId), Long.parseLong(crmContact.id));
    log.info("added HubSpot contact {} to list {}", crmContact.id, listId);
  }

  @Override
  public List<CrmContact> getContactsFromList(String listId) throws Exception {
    throw new RuntimeException("not implemented");
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
      HubSpotClientFactory.v1Client().contactList().removeContactFromList(Long.parseLong(listId), Long.parseLong(crmContact.id));
      log.info("removed HubSpot contact {} from list {}", crmContact.id, listId);
    }
  }

  @Override
  public String insertOpportunity(OpportunityEvent opportunityEvent) throws Exception {
    throw new RuntimeException("not implemented");
  }

  @Override
  public void processImport(List<CrmImportEvent> importEvents) throws Exception {
    throw new RuntimeException("not implemented");
  }

  @Override
  public void processUpdate(List<CrmUpdateEvent> updateEvents) throws Exception {
    throw new RuntimeException("not implemented");
  }

  protected CrmContact toCrmContact(Contact contact) {
    // TODO: likely enough, but may need the rest of the fields
    CrmContact crmContact = new CrmContact();
    crmContact.id = contact.getId();
    crmContact.accountId = contact.getProperties().getAssociatedcompanyid();
    crmContact.firstName = contact.getProperties().getFirstname();
    crmContact.lastName = contact.getProperties().getLastname();
    crmContact.email = contact.getProperties().getEmail();
    crmContact.phone = contact.getProperties().getPhone();
    if (Strings.isNullOrEmpty(crmContact.phone)) {
      // try the mobile, just in case
      crmContact.phone = contact.getProperties().getMobilephone();
    }
    return crmContact;
  }

  protected Optional<CrmContact> toCrmContact(Optional<Contact> contact) {
    return contact.map(this::toCrmContact);
  }

  protected List<CrmContact> toCrmContact(List<Contact> contacts) {
    return contacts.stream()
        .map(this::toCrmContact)
        .collect(Collectors.toList());
  }

  // The HubSpot API will ignore irrelevant properties for specific objects, so just include everything we're expecting.
  private List<String> getCustomPropertyNames() {
    return Arrays.stream(EnvironmentConfig.CRMFieldDefinitions.class.getFields()).map(f -> {
      try {
        return f.get(env.getConfig().hubspot.fieldDefinitions).toString();
      } catch (IllegalAccessException e) {
        log.error("failed to retrieve custom fields from schema", e);
        return "";
      }
    }).collect(Collectors.toList());
  }

  private void setProperty(String fieldName, Object value, Map<String, Object> customProperties) {
    // Optional field names may not be configured in env.json, so ensure we actually have a name first...
    // Likewise, don't set a null or empty value.
    if (Strings.isNullOrEmpty(fieldName) || value == null) {
      return;
    }

    customProperties.put(fieldName, value);
  }

  private String normalizePhoneNumber(String phone) {
    // Hubspot doesn't seem to support country codes when phone numbers are used to search. Strip it off.
    return phone.replace("+1", "");
  }
}
