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
import com.impactupgrade.nucleus.model.CRMImportEvent;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.MessagingWebhookEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
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
    Filter[] filters = new Filter[]{new Filter("email", "EQ", email)};
    ContactResults results = hsClient.contact().search(filters, getCustomPropertyNames());

    if (results == null || results.getTotal() == 0) {
      return Optional.empty();
    }

    Contact result = results.getResults().get(0);
    String id = result.getId();
    String accountId = result.getProperties().getAssociatedcompanyid();
    return Optional.of(new CrmContact(id, accountId));
  }

  @Override
  public Optional<CrmContact> getContactByPhone(String phone) throws Exception {
    // TODO: also need to include mobilephone
    Filter[] filters = new Filter[]{new Filter("phone", "EQ", phone)};
    ContactResults results = hsClient.contact().search(filters, getCustomPropertyNames());

    if (results == null || results.getTotal() == 0) {
      return Optional.empty();
    }

    Contact result = results.getResults().get(0);
    String id = result.getId();
    String accountId = result.getProperties().getAssociatedcompanyid();
    return Optional.of(new CrmContact(id, accountId));
  }

  @Override
  public Optional<CrmDonation> getDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Filter[] filters = new Filter[]{new Filter(env.config().hubspot.fieldDefinitions.paymentGatewayTransactionId, "EQ", paymentGatewayEvent.getTransactionId())};
    DealResults results = hsClient.deal().search(filters, getCustomPropertyNames());

    if (results == null || results.getTotal() == 0) {
      return Optional.empty();
    }

    Deal result = results.getResults().get(0);
    String id = result.getId();
    boolean successful = env.config().hubspot.donationPipeline.successStageId.equalsIgnoreCase(result.getProperties().getDealstage());
    return Optional.of(new CrmDonation(id, successful));
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    Filter[] filters = new Filter[]{new Filter(env.config().hubspot.fieldDefinitions.paymentGatewaySubscriptionId, "EQ", paymentGatewayEvent.getSubscriptionId())};
    DealResults results = hsClient.deal().search(filters, getCustomPropertyNames());

    if (results == null || results.getTotal() == 0) {
      return Optional.empty();
    }

    Deal result = results.getResults().get(0);
    String id = result.getId();
    return Optional.of(new CrmRecurringDonation(id));
  }

  @Override
  public void updateDonation(CrmDonation donation) throws Exception {
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
  public String insertAccount(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    CompanyProperties account = new CompanyProperties();
    setAccountFields(account, paymentGatewayEvent);
    Company response = hsClient.company().insert(account);
    return response == null ? null : response.getId();
  }

  protected void setAccountFields(CompanyProperties account, PaymentGatewayWebhookEvent paymentGatewayEvent) {
    account.setName(paymentGatewayEvent.getFullName());

    account.setAddress(paymentGatewayEvent.getStreet());
    account.setCity(paymentGatewayEvent.getCity());
    account.setState(paymentGatewayEvent.getState());
    account.setZip(paymentGatewayEvent.getZip());
    account.setCountry(paymentGatewayEvent.getCountry());
  }

  @Override
  public String insertContact(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    ContactProperties contact = new ContactProperties();
    setContactFields(contact, paymentGatewayEvent);
    Contact response = hsClient.contact().insert(contact);
    return response == null ? null : response.getId();
  }

  protected void setContactFields(ContactProperties contact, PaymentGatewayWebhookEvent paymentGatewayEvent) {
    contact.setAssociatedcompanyid(paymentGatewayEvent.getPrimaryCrmAccountId());
    contact.setFirstname(paymentGatewayEvent.getFirstName());
    contact.setLastname(paymentGatewayEvent.getLastName());
    contact.setEmail(paymentGatewayEvent.getEmail());
    contact.setMobilePhone(paymentGatewayEvent.getPhone());
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
      hsClient.association().insert("deal", response.getId(), "company", paymentGatewayEvent.getPrimaryCrmAccountId());
      hsClient.association().insert("deal", response.getId(), "contact", paymentGatewayEvent.getPrimaryCrmContactId());

      return response.getId();
    } else {
      return null;
    }
  }

  protected void setDonationFields(DealProperties deal, PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    // TODO: campaign

    deal.setPipeline(env.config().hubspot.donationPipeline.id);
    if (paymentGatewayEvent.isTransactionSuccess()) {
      deal.setDealstage(env.config().hubspot.donationPipeline.successStageId);
    } else {
      deal.setDealstage(env.config().hubspot.donationPipeline.failedStageId);
    }

    deal.setClosedate(paymentGatewayEvent.getTransactionDate());
    deal.setDescription(paymentGatewayEvent.getTransactionDescription());
    deal.setDealname("Donation: " + paymentGatewayEvent.getFullName());

    if (paymentGatewayEvent.isTransactionRecurring()) {
      deal.getCustomProperties().put(env.config().hubspot.fieldDefinitions.recurringDonationDealId, paymentGatewayEvent.getPrimaryCrmRecurringDonationId());
    }

    deal.getCustomProperties().put(env.config().hubspot.fieldDefinitions.paymentGatewayName, paymentGatewayEvent.getGatewayName());
    deal.getCustomProperties().put(env.config().hubspot.fieldDefinitions.paymentGatewayTransactionId, paymentGatewayEvent.getTransactionId());
    deal.getCustomProperties().put(env.config().hubspot.fieldDefinitions.paymentGatewayCustomerId, paymentGatewayEvent.getCustomerId());
    // Do NOT set subscriptionId! In getRecurringDonation, we search by that and expect only the RD to be returned.

    // TODO: Waiting to hear back from Josh about multi-currency support. The following was from LJI's SFDC support.
    // Will need something similar, in general.
//    if (paymentGatewayEvent.getTransactionOriginalCurrency() != null) {
//      // set the custom fields related for international donation
//      deal.setField("Original_Amount__c", paymentGatewayEvent.getTransactionOriginalAmountInDollars());
//      deal.setField("Original_Currency__c", paymentGatewayEvent.getTransactionOriginalCurrency());
//      deal.setField("Converted_Amount__c", paymentGatewayEvent.getTransactionAmountInDollars());
//      deal.setField("Converted_Currency__c", "usd");
//      deal.setField("Exchange_Rate__c", paymentGatewayEvent.getTransactionExchangeRate());
//    } else {
      deal.setAmount(paymentGatewayEvent.getTransactionAmountInDollars());
//      deal.setAmountInCompanyCurrency(paymentGatewayEvent.getTransactionAmountInDollars());
//      deal.setField("Original_Currency__c", "usd");
//    }
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
    deal.setDealstage(env.config().hubspot.donationPipeline.refundedStageId);

    deal.getCustomProperties().put(env.config().hubspot.fieldDefinitions.paymentGatewayRefundId, paymentGatewayEvent.getRefundId());
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
    deal.getCustomProperties().put(env.config().hubspot.fieldDefinitions.paymentGatewayDepositId, paymentGatewayEvent.getDepositId());
    deal.getCustomProperties().put(env.config().hubspot.fieldDefinitions.paymentGatewayDepositDate, paymentGatewayEvent.getDepositDate());
    deal.getCustomProperties().put(env.config().hubspot.fieldDefinitions.paymentGatewayDepositNetAmount, paymentGatewayEvent.getTransactionNetAmountInDollars());

    hsClient.deal().update(donation.get().getId(), deal);
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    // TODO: campaign

    DealProperties deal = new DealProperties();
    setRecurringDonationFields(deal, paymentGatewayEvent);

    Deal response = hsClient.deal().insert(deal);
    if (response != null) {
      hsClient.association().insert("deal", response.getId(), "company", paymentGatewayEvent.getPrimaryCrmAccountId());
      hsClient.association().insert("deal", response.getId(), "contact", paymentGatewayEvent.getPrimaryCrmContactId());

      return response.getId();
    } else {
      return null;
    }
  }

  protected void setRecurringDonationFields(DealProperties deal, PaymentGatewayWebhookEvent paymentGatewayEvent) throws Exception {
    // TODO: campaign

    deal.setPipeline(env.config().hubspot.recurringDonationPipeline.id);
    deal.setDealstage(env.config().hubspot.recurringDonationPipeline.openStageId);

    // TODO: Assumed to be monthly. If quarterly/yearly support needed, will need a custom field + divide the gift into the monthly rate.
    deal.setRecurringRevenueAmount(paymentGatewayEvent.getSubscriptionAmountInDollars());
    deal.setRecurringRevenueDealType("NEW_BUSINESS");
    deal.setClosedate(paymentGatewayEvent.getTransactionDate());
    deal.setDealname("Recurring Donation: " + paymentGatewayEvent.getFullName());

    deal.getCustomProperties().put(env.config().hubspot.fieldDefinitions.paymentGatewayName, paymentGatewayEvent.getGatewayName());
    deal.getCustomProperties().put(env.config().hubspot.fieldDefinitions.paymentGatewaySubscriptionId, paymentGatewayEvent.getSubscriptionId());
    deal.getCustomProperties().put(env.config().hubspot.fieldDefinitions.paymentGatewayCustomerId, paymentGatewayEvent.getCustomerId());
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
  public String insertContact(MessagingWebhookEvent messagingWebhookEvent) throws Exception {
    ContactProperties contact = new ContactProperties();
    contact.setFirstname(messagingWebhookEvent.getFirstName());
    contact.setLastname(messagingWebhookEvent.getLastName());
    contact.setEmail(messagingWebhookEvent.getEmail());
    contact.setPhone(messagingWebhookEvent.getPhone());
    contact.setMobilePhone(messagingWebhookEvent.getPhone());
    Contact response = hsClient.contact().insert(contact);
    return response == null ? null : response.getId();
  }

  @Override
  public void smsSignup(MessagingWebhookEvent messagingWebhookEvent) throws Exception {
    String listId = messagingWebhookEvent.getListId();
    if (Strings.isNullOrEmpty(listId)) {
      String defaultListId = env.config().hubspot.defaultSmsOptInList;
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
    HubSpotClientFactory.v1Client().contactList().addContactToList(Long.parseLong(listId), Long.parseLong(messagingWebhookEvent.getCrmContactId()));
    log.info("added HubSpot contact {} to list {}", messagingWebhookEvent.getCrmContactId(), listId);
  }

  @Override
  public void processImport(List<CRMImportEvent> importEvents) throws Exception {
    // TODO
  }

  // The HubSpot API will ignore irrelevant properties for specific objects, so just include everything we're expecting.
  private List<String> getCustomPropertyNames() {
    return Arrays.stream(EnvironmentConfig.CRMFieldDefinitions.class.getFields()).map(f -> {
      try {
        return f.get(env.config().hubspot.fieldDefinitions).toString();
      } catch (IllegalAccessException e) {
        log.error("failed to retrieve custom fields from schema", e);
        return "";
      }
    }).collect(Collectors.toList());
  }
}
