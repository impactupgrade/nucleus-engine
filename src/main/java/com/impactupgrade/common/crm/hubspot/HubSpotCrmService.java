package com.impactupgrade.common.crm.hubspot;

import com.google.common.base.Strings;
import com.impactupgrade.common.crm.CrmDestinationService;
import com.impactupgrade.common.crm.CrmSourceService;
import com.impactupgrade.common.crm.model.CrmContact;
import com.impactupgrade.common.crm.model.CrmDonation;
import com.impactupgrade.common.crm.model.CrmRecurringDonation;
import com.impactupgrade.common.crm.model.ImportEvent;
import com.impactupgrade.common.environment.Environment;
import com.impactupgrade.common.messaging.MessagingWebhookEvent;
import com.impactupgrade.common.paymentgateway.model.PaymentGatewayEvent;
import com.impactupgrade.integration.hubspot.v3.Association;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;

public class HubSpotCrmService implements CrmDestinationService, CrmSourceService {

  private static final Logger log = LogManager.getLogger(HubSpotCrmService.class);

  // TODO: move this to env.json
  private static final String DEFAULT_HUBSPOT_SMS_LIST_ID = System.getenv("HUBSPOT_SMSLISTID");

  protected final Environment env;
  protected final HubSpotV3Client hsClient;

  public HubSpotCrmService(Environment env) {
    this.env = env;
    hsClient = HubSpotClientFactory.v3Client();
  }

  @Override
  public Optional<CrmContact> getContactByEmail(String email) throws Exception {
    Filter filter = new Filter("email", "EQ", email);
    ContactResults results = hsClient.contact().search(filter);

    if (results == null || results.getTotal() == 0) {
      return Optional.empty();
    }

    Contact result = results.getResults().get(0);
    String id = result.getId();
    String accountId = result.getProperties().getCompanyId();
    return Optional.of(new CrmContact(id, accountId));
  }

  @Override
  public Optional<CrmContact> getContactByPhone(String phone) throws Exception {
    // TODO: also need to include mobilephone
    Filter filter = new Filter("phone", "EQ", phone);
    ContactResults results = hsClient.contact().search(filter);

    if (results == null || results.getTotal() == 0) {
      return Optional.empty();
    }

    Contact result = results.getResults().get(0);
    String id = result.getId();
    String accountId = result.getProperties().getCompanyId();
    return Optional.of(new CrmContact(id, accountId));
  }

  @Override
  public Optional<CrmDonation> getDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Filter filter = new Filter(env.config().hubSpot.fields.paymentGatewayTransactionId, "EQ", paymentGatewayEvent.getTransactionId());
    DealResults results = hsClient.deal().search(filter);

    if (results == null || results.getTotal() == 0) {
      return Optional.empty();
    }

    Deal result = results.getResults().get(0);
    String id = result.getId();
    boolean successful = env.config().hubSpot.donationPipeline.successStage.equalsIgnoreCase(result.getProperties().getDealstage());
    return Optional.of(new CrmDonation(id, successful));
  }

  @Override
  public Optional<CrmRecurringDonation> getRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Filter filter = new Filter(env.config().hubSpot.fields.paymentGatewaySubscriptionId, "EQ", paymentGatewayEvent.getSubscriptionId());
    DealResults results = hsClient.deal().search(filter);

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
  public String insertAccount(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    CompanyProperties account = new CompanyProperties();
    setAccountFields(account, paymentGatewayEvent);
    Company response = hsClient.company().insert(account);
    return response == null ? null : response.getId();
  }

  protected void setAccountFields(CompanyProperties account, PaymentGatewayEvent paymentGatewayEvent) {
    account.setName(paymentGatewayEvent.getFullName());

    account.setAddress(paymentGatewayEvent.getStreet());
    account.setCity(paymentGatewayEvent.getCity());
    account.setState(paymentGatewayEvent.getState());
    account.setZip(paymentGatewayEvent.getZip());
    account.setCountry(paymentGatewayEvent.getCountry());
  }

  @Override
  public String insertContact(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    ContactProperties contact = new ContactProperties();
    setContactFields(contact, paymentGatewayEvent);
    Contact response = hsClient.contact().insert(contact);
    return response == null ? null : response.getId();
  }

  protected void setContactFields(ContactProperties contact, PaymentGatewayEvent paymentGatewayEvent) {
    contact.setCompanyId(paymentGatewayEvent.getPrimaryCrmAccountId());
    contact.setFirstname(paymentGatewayEvent.getFirstName());
    contact.setLastname(paymentGatewayEvent.getLastName());
    contact.setEmail(paymentGatewayEvent.getEmail());
    contact.setMobilePhone(paymentGatewayEvent.getPhone());
  }

  @Override
  public String insertDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: campaign

    DealProperties deal = new DealProperties();
    setDonationFields(deal, paymentGatewayEvent);

    Deal response = hsClient.deal().insert(deal);
    if (response != null) {
      if (paymentGatewayEvent.isTransactionRecurring()) {
        Association recurringDonationAssociation = new Association(response.getId(), paymentGatewayEvent.getPrimaryCrmRecurringDonationId(),
            "deal_to_deal");
        hsClient.association().insert(recurringDonationAssociation);
      }
      Association companyAssociation = new Association(response.getId(), paymentGatewayEvent.getPrimaryCrmAccountId(),
          "deal_to_company"); // TODO: make sure this also creates company_to_deal
      hsClient.association().insert(companyAssociation);
      Association contactAssociation = new Association(response.getId(), paymentGatewayEvent.getPrimaryCrmContactId(),
          "deal_to_contact"); // TODO: make sure this also creates contact_to_deal
      hsClient.association().insert(contactAssociation);

      return response.getId();
    } else {
      return null;
    }
  }

  protected void setDonationFields(DealProperties deal, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: campaign

    deal.setPipeline(env.config().hubSpot.donationPipeline.name);
    if (paymentGatewayEvent.isTransactionSuccess()) {
      deal.setDealstage(env.config().hubSpot.donationPipeline.successStage);
    } else {
      deal.setDealstage(env.config().hubSpot.donationPipeline.failedStage);
    }

    deal.setClosedate(paymentGatewayEvent.getTransactionDate());
    deal.setDescription(paymentGatewayEvent.getTransactionDescription());
    // purely a default, but we generally expect this to be overridden
    deal.setDealname(paymentGatewayEvent.getFullName() + " Donation");

    deal.getCustomProperties().put(env.config().hubSpot.fields.paymentGatewayName, paymentGatewayEvent.getGatewayName());
    deal.getCustomProperties().put(env.config().hubSpot.fields.paymentGatewayTransactionId, paymentGatewayEvent.getTransactionId());
    deal.getCustomProperties().put(env.config().hubSpot.fields.paymentGatewayCustomerId, paymentGatewayEvent.getCustomerId());
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
  public void refundDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    Optional<CrmDonation> donation = getDonation(paymentGatewayEvent);

    if (donation.isEmpty()) {
      log.warn("unable to find HS donation using transaction {}", paymentGatewayEvent.getTransactionId());
      return;
    }

    DealProperties deal = new DealProperties();
    setDonationRefundFields(deal, paymentGatewayEvent);

    hsClient.deal().update(donation.get().getId(), deal);
  }

  protected void setDonationRefundFields(DealProperties deal, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    deal.setDealstage(env.config().hubSpot.donationPipeline.refundedStage);

    deal.getCustomProperties().put(env.config().hubSpot.fields.paymentGatewayRefundId, paymentGatewayEvent.getRefundId());
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
    deal.getCustomProperties().put(env.config().hubSpot.fields.paymentGatewayDepositId, paymentGatewayEvent.getDepositId());
    deal.getCustomProperties().put(env.config().hubSpot.fields.paymentGatewayDepositDate, paymentGatewayEvent.getDepositDate());
    deal.getCustomProperties().put(env.config().hubSpot.fields.paymentGatewayDepositNetAmount, paymentGatewayEvent.getTransactionNetAmountInDollars());

    hsClient.deal().update(donation.get().getId(), deal);
  }

  @Override
  public String insertRecurringDonation(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: campaign

    DealProperties deal = new DealProperties();
    setRecurringDonationFields(deal, paymentGatewayEvent);

    Deal response = hsClient.deal().insert(deal);
    if (response != null) {
      Association companyAssociation = new Association(response.getId(), paymentGatewayEvent.getPrimaryCrmAccountId(),
          "deal_to_company"); // TODO: make sure this also creates company_to_deal
      hsClient.association().insert(companyAssociation);
      Association contactAssociation = new Association(response.getId(), paymentGatewayEvent.getPrimaryCrmContactId(),
          "deal_to_contact"); // TODO: make sure this also creates contact_to_deal
      hsClient.association().insert(contactAssociation);

      return response.getId();
    } else {
      return null;
    }
  }

  protected void setRecurringDonationFields(DealProperties deal, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    // TODO: campaign

    deal.setPipeline(env.config().hubSpot.recurringDonationPipeline.name);
    deal.setDealstage(env.config().hubSpot.recurringDonationPipeline.openStage);

    // TODO: Assumed to be monthly. If quarterly/yearly support needed, will need a custom field + divide the gift into the monthly rate.
    deal.setRecurringRevenueAmount(paymentGatewayEvent.getSubscriptionAmountInDollars());
    deal.setClosedate(paymentGatewayEvent.getTransactionDate());
    // purely a default, but we generally expect this to be overridden
    deal.setDealname(paymentGatewayEvent.getFullName() + " Recurring Donation");

    deal.getCustomProperties().put(env.config().hubSpot.fields.paymentGatewayName, paymentGatewayEvent.getGatewayName());
    deal.getCustomProperties().put(env.config().hubSpot.fields.paymentGatewaySubscriptionId, paymentGatewayEvent.getSubscriptionId());
    deal.getCustomProperties().put(env.config().hubSpot.fields.paymentGatewayCustomerId, paymentGatewayEvent.getCustomerId());
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
    deal.setDealstage(env.config().hubSpot.recurringDonationPipeline.closedStage);
    setRecurringDonationFieldsForClose(deal, paymentGatewayEvent);

    hsClient.deal().update(recurringDonation.get().id(), deal);
  }

  // Give orgs an opportunity to clear anything else out that's unique to them, prior to the update
  protected void setRecurringDonationFieldsForClose(DealProperties deal, PaymentGatewayEvent paymentGatewayEvent) throws Exception {
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
      log.info("explicit HubSpot list ID not provided; using the default {}", DEFAULT_HUBSPOT_SMS_LIST_ID);
      listId = DEFAULT_HUBSPOT_SMS_LIST_ID;
    }
    // note that HubSpot auto-prevents duplicate entries in lists
    // TODO: shift to V3
    HubSpotClientFactory.v1Client().contactList().addContactToList(Long.parseLong(listId), Long.parseLong(messagingWebhookEvent.getCrmContactId()));
    log.info("added HubSpot contact {} to list {}", messagingWebhookEvent.getCrmContactId(), listId);
  }

  @Override
  public void processImport(List<ImportEvent> importEvents) throws Exception {
    // TODO
  }
}
