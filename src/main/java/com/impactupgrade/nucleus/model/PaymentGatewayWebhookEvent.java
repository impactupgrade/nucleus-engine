/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.MetadataRetriever;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.Utils;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Card;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.MetadataStore;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class PaymentGatewayWebhookEvent {
  
  protected final Environment env;
  protected final MetadataRetriever metadataRetriever;

  // determined by event
  protected CrmAccount crmAccount = new CrmAccount();
  protected CrmContact crmContact = new CrmContact();
  protected String campaignId;
  protected String customerId;
  protected String depositTransactionId;
  protected String gatewayName;
  protected String paymentMethod;
  protected String refundId;
  protected Calendar refundDate;
  protected Double subscriptionAmountInDollars;
  protected String subscriptionCurrency;
  protected String subscriptionDescription;
  protected String subscriptionId;
  protected String subscriptionInterval;
  protected Calendar subscriptionNextDate;
  protected Calendar subscriptionStartDate;
  protected Double transactionAmountInDollars;
  protected Double transactionNetAmountInDollars;
  protected Calendar transactionDate;
  protected String transactionDescription;
  protected Double transactionExchangeRate;
  protected String transactionId;
  protected Double transactionOriginalAmountInDollars;
  protected String transactionOriginalCurrency;
  protected boolean transactionCurrencyConverted;
  protected boolean transactionSuccess;

  // context set within processing steps OR pulled from event metadata
  protected String crmDonationRecordTypeId;
  protected String crmRecurringDonationId;
  protected String depositId;
  protected Calendar depositDate;

  public PaymentGatewayWebhookEvent(Environment env) {
    this.env = env;
    metadataRetriever = new MetadataRetriever(env);
  }

  // IMPORTANT! We're remove all non-numeric chars on all metadata fields -- it appears a few campaign IDs were pasted
  // into forms and contain NBSP characters :(

  public void initStripe(Charge stripeCharge, Customer stripeCustomer,
      Optional<Invoice> stripeInvoice, Optional<BalanceTransaction> stripeBalanceTransaction) {
    initStripeCommon();
    initStripeCustomer(stripeCustomer, stripeCharge);

    // NOTE: See the note on the StripeService's customer.subscription.created event handling. We insert recurring donations
    // from subscription creation ONLY if it's in a trial period and starts in the future. Otherwise, let the
    // first donation do it in order to prevent timing issues.
    if (stripeInvoice.isPresent() && !Strings.isNullOrEmpty(stripeInvoice.get().getSubscription())) {
      initStripeSubscription(stripeInvoice.get().getSubscriptionObject(), stripeCustomer);
    }

    if (stripeCharge.getCreated() != null) {
      transactionDate = Calendar.getInstance();
      transactionDate.setTimeInMillis(stripeCharge.getCreated() * 1000);
    } else {
      transactionDate = Calendar.getInstance();
    }

    stripeBalanceTransaction.ifPresent(balanceTransaction -> depositTransactionId = balanceTransaction.getId());
    transactionDescription = stripeCharge.getDescription();
    transactionId = stripeCharge.getId();
    transactionSuccess = !"failed".equalsIgnoreCase(stripeCharge.getStatus());

    transactionOriginalAmountInDollars = stripeCharge.getAmount() / 100.0;
    stripeBalanceTransaction.ifPresent(t -> transactionNetAmountInDollars = t.getNet() / 100.0);
    transactionOriginalCurrency = stripeCharge.getCurrency().toUpperCase(Locale.ROOT);
    if (env.getConfig().currency.equalsIgnoreCase(stripeCharge.getCurrency().toUpperCase(Locale.ROOT))) {
      // currency is the same as the org receiving the funds, so no conversion necessary
      transactionAmountInDollars = stripeCharge.getAmount() / 100.0;
    } else {
      transactionCurrencyConverted = true;
      // currency is different than what the org is expecting, so assume it was converted
      // TODO: this implies the values will *not* be set for failed transactions!
      if (stripeBalanceTransaction.isPresent()) {
        transactionAmountInDollars = stripeBalanceTransaction.get().getAmount() / 100.0;
        transactionExchangeRate = stripeBalanceTransaction.get().getExchangeRate().doubleValue();
      }
    }

    transactionDescription = stripeCharge.getDescription();

    metadataRetriever.stripeCharge(stripeCharge).stripeCustomer(stripeCustomer);
  }

  public void initStripe(PaymentIntent stripePaymentIntent, Customer stripeCustomer,
      Optional<Invoice> stripeInvoice, Optional<BalanceTransaction> stripeBalanceTransaction) {
    initStripeCommon();
    initStripeCustomer(stripeCustomer, stripePaymentIntent);

    // NOTE: See the note on the StripeService's customer.subscription.created event handling. We insert recurring donations
    // from subscription creation ONLY if it's in a trial period and starts in the future. Otherwise, let the
    // first donation do it in order to prevent timing issues.
    if (stripeInvoice.isPresent() && !Strings.isNullOrEmpty(stripeInvoice.get().getSubscription())) {
      initStripeSubscription(stripeInvoice.get().getSubscriptionObject(), stripeCustomer);
    }

    if (stripePaymentIntent.getCreated() != null) {
      transactionDate = Calendar.getInstance();
      transactionDate.setTimeInMillis(stripePaymentIntent.getCreated() * 1000);
    } else {
      transactionDate = Calendar.getInstance();
    }

    stripeBalanceTransaction.ifPresent(balanceTransaction -> depositTransactionId = balanceTransaction.getId());
    transactionDescription = stripePaymentIntent.getDescription();
    transactionId = stripePaymentIntent.getId();
    // note this is different than a charge, which uses !"failed" -- intents have multiple phases of "didn't work",
    // so explicitly search for succeeded
    transactionSuccess = "succeeded".equalsIgnoreCase(stripePaymentIntent.getStatus());

    transactionOriginalAmountInDollars = stripePaymentIntent.getAmount() / 100.0;
    stripeBalanceTransaction.ifPresent(t -> transactionNetAmountInDollars = t.getNet() / 100.0);
    transactionOriginalCurrency = stripePaymentIntent.getCurrency().toUpperCase(Locale.ROOT);
    if (env.getConfig().currency.equalsIgnoreCase(stripePaymentIntent.getCurrency().toUpperCase(Locale.ROOT))) {
      // currency is the same as the org receiving the funds, so no conversion necessary
      transactionAmountInDollars = stripePaymentIntent.getAmount() / 100.0;
    } else {
      transactionCurrencyConverted = true;
      // currency is different than what the org is expecting, so assume it was converted
      // TODO: this implies the values will *not* be set for failed transactions!
      if (stripeBalanceTransaction.isPresent()) {
        transactionAmountInDollars = stripeBalanceTransaction.get().getAmount() / 100.0;
        transactionExchangeRate = stripeBalanceTransaction.get().getExchangeRate().doubleValue();
      }
    }

    transactionDescription = stripePaymentIntent.getDescription();

    metadataRetriever.stripePaymentIntent(stripePaymentIntent).stripeCustomer(stripeCustomer);
  }

  public void initStripe(Refund stripeRefund) {
    initStripeCommon();

    refundId = stripeRefund.getId();
    transactionId = stripeRefund.getCharge();

    if (stripeRefund.getCreated() != null) {
      refundDate = Calendar.getInstance();
      refundDate.setTimeInMillis(stripeRefund.getCreated() * 1000);
    } else {
      refundDate = Calendar.getInstance();
    }
  }

  public void initStripe(Subscription stripeSubscription, Customer stripeCustomer) {
    initStripeCommon();
    initStripeCustomer(stripeCustomer, stripeSubscription);

    initStripeSubscription(stripeSubscription, stripeCustomer);
  }

  protected void initStripeCommon() {
    gatewayName = "Stripe";
    // TODO: expand to include ACH through Plaid?
    paymentMethod = "credit card";
  }

  protected void initStripeCustomer(Customer stripeCustomer, MetadataStore<?> stripeMetadataBackup) {
    customerId = stripeCustomer.getId();

    initStripeCustomerName(stripeCustomer, stripeMetadataBackup);

    crmContact.email = stripeCustomer.getEmail();
    crmContact.phone = stripeCustomer.getPhone();

    CrmAddress crmAddress = new CrmAddress();
    if (stripeCustomer.getAddress() != null) {
      crmAddress.city = stripeCustomer.getAddress().getCity();
      crmAddress.country = stripeCustomer.getAddress().getCountry();
      crmAddress.state = stripeCustomer.getAddress().getState();
      crmAddress.street = stripeCustomer.getAddress().getLine1();
      if (!Strings.isNullOrEmpty(stripeCustomer.getAddress().getLine2())) {
        crmAddress.street += ", " + stripeCustomer.getAddress().getLine2();
      }
      crmAddress.postalCode = stripeCustomer.getAddress().getPostalCode();
    } else {
      // use the first payment source, but don't use the default source, since we can't guarantee it's set as a card
      // TODO: This will need rethought after Donor Portal is launched and Stripe is used for ACH!
      stripeCustomer.getSources().getData().stream()
          .filter(s -> s instanceof Card)
          .map(s -> (Card) s)
          .findFirst()
          .ifPresent(stripeCard -> {
            crmAddress.city = stripeCard.getAddressCity();
            crmAddress.country = stripeCard.getAddressCountry();
            crmAddress.state = stripeCard.getAddressState();
            crmAddress.street = stripeCard.getAddressLine1();
            if (!Strings.isNullOrEmpty(stripeCard.getAddressLine2())) {
              crmAddress.street += ", " + stripeCard.getAddressLine2();
            }
            crmAddress.postalCode = stripeCard.getAddressZip();
          });
    }

    crmAccount.address = crmAddress;
    crmContact.address = crmAddress;
  }

  // What happens in this method seems ridiculous, but we're trying to resiliently deal with a variety of situations.
  // Some donation forms and vendors use true Customer names, others use metadata on Customer, other still only put
  // names in metadata on the Charge or Subscription. Madness. But let's be helpful...
  protected void initStripeCustomerName(Customer stripeCustomer, MetadataStore<?> stripeMetadataBackup) {
    // For the full name, start with Customer name. Generally this is populated, but a few vendors don't always do it.
    crmAccount.name = stripeCustomer.getName();
    // If that didn't work, look in the metadata. We've seen variations of "customer" or "full" name used.
    if (Strings.isNullOrEmpty(crmAccount.name)) {
      crmAccount.name = stripeCustomer.getMetadata().entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return (key.contains("customer") || key.contains("full")) && key.contains("name");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }
    // If that still didn't work, look in the backup metadata (typically a charge or subscription).
    if (Strings.isNullOrEmpty(crmAccount.name)) {
      crmAccount.name = stripeMetadataBackup.getMetadata().entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return (key.contains("customer") || key.contains("full")) && key.contains("name");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }

    // Now do first name, again using metadata.
    crmContact.firstName = stripeCustomer.getMetadata().entrySet().stream().filter(e -> {
      String key = e.getKey().toLowerCase(Locale.ROOT);
      return key.contains("first") && key.contains("name");
    }).findFirst().map(Map.Entry::getValue).orElse(null);
    if (Strings.isNullOrEmpty(crmContact.firstName)) {
      crmContact.firstName = stripeMetadataBackup.getMetadata().entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("first") && key.contains("name");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }

    // And now the last name.
    crmContact.lastName = stripeCustomer.getMetadata().entrySet().stream().filter(e -> {
      String key = e.getKey().toLowerCase(Locale.ROOT);
      return key.contains("last") && key.contains("name");
    }).findFirst().map(Map.Entry::getValue).orElse(null);
    if (Strings.isNullOrEmpty(crmContact.lastName)) {
      crmContact.lastName = stripeMetadataBackup.getMetadata().entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("last") && key.contains("name");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }

    // If we still don't have a first/last name, but do have full name, fall back to using a split.
    if (Strings.isNullOrEmpty(crmContact.lastName) && !Strings.isNullOrEmpty(crmAccount.name)) {
      String[] split = Utils.fullNameToFirstLast(crmAccount.name);
      crmContact.firstName = split[0];
      crmContact.lastName = split[1];
    }

    // If we still don't have a full name, but do have a first and last, combine them.
    if (Strings.isNullOrEmpty(crmAccount.name) && !Strings.isNullOrEmpty(crmContact.firstName) && !Strings.isNullOrEmpty(crmContact.lastName)) {
      crmAccount.name = crmContact.firstName + " " + crmContact.lastName;
    }
  }

  // Keep stripeCustomer, even though we don't use it here -- needed in subclasses.
  protected void initStripeSubscription(Subscription stripeSubscription, Customer stripeCustomer) {
    if (stripeSubscription.getTrialEnd() != null) {
      subscriptionStartDate = Calendar.getInstance();
      subscriptionStartDate.setTimeInMillis(stripeSubscription.getTrialEnd() * 1000);
    } else {
      subscriptionStartDate = Calendar.getInstance();
      subscriptionStartDate.setTimeInMillis(stripeSubscription.getStartDate() * 1000);
    }
    subscriptionNextDate = subscriptionStartDate;

    subscriptionId = stripeSubscription.getId();
    if (stripeSubscription.getPendingInvoiceItemInterval() != null) {
      subscriptionInterval = stripeSubscription.getPendingInvoiceItemInterval().getInterval();
    }
    // by default, assume monthly
    if (Strings.isNullOrEmpty(subscriptionInterval)) subscriptionInterval = "month";

    // Stripe is in cents
    // TODO: currency conversion support? This is eventually updated as charges are received, but for brand new ones
    // with a trial, this could throw off future forecasting!
    SubscriptionItem item = stripeSubscription.getItems().getData().get(0);
    subscriptionAmountInDollars = item.getPrice().getUnitAmountDecimal().doubleValue() * item.getQuantity() / 100.0;
    subscriptionCurrency = item.getPrice().getCurrency().toUpperCase(Locale.ROOT);

    metadataRetriever.stripeSubscription(stripeSubscription).stripeCustomer(stripeCustomer);

    // TODO: We could shift this to MetadataRetriever, but odds are we're the only ones setting it...
    subscriptionDescription = stripeSubscription.getMetadata().get("description");
  }

  public void initPaymentSpring(com.impactupgrade.integration.paymentspring.model.Transaction paymentSpringTransaction,
      Optional<com.impactupgrade.integration.paymentspring.model.Customer> paymentSpringCustomer,
      Optional<com.impactupgrade.integration.paymentspring.model.Subscription> paymentSpringSubscription) {
    initPaymentSpringCommon();

    // NOTE: See the note on the PaymentSpringService's subscription->created event handling. Let the
    // first donation create the recurring donation in order to prevent timing issues.
    paymentSpringSubscription.ifPresent(s -> initPaymentSpringSubscription(s, paymentSpringTransaction));

    initPaymentSpringCustomer(paymentSpringTransaction, paymentSpringCustomer);

    CrmAddress crmAddress = new CrmAddress();
    crmAddress.city = paymentSpringTransaction.getCity();
    crmAddress.country = paymentSpringTransaction.getCountry();
    crmAddress.state = paymentSpringTransaction.getState();
    crmAddress.street = paymentSpringTransaction.getAddress1();
    if (!Strings.isNullOrEmpty(paymentSpringTransaction.getAddress2())) {
      crmAddress.street += ", " + paymentSpringTransaction.getAddress2();
    }
    crmAddress.postalCode = paymentSpringTransaction.getZip();

    crmAccount.address = crmAddress;
    crmContact.address = crmAddress;

    transactionDate = getTransactionDate(paymentSpringTransaction.getCreatedAt());

    transactionDescription = paymentSpringTransaction.getDescription();
    transactionId = paymentSpringTransaction.getId();

    // PaymentSpring is in cents
    if (paymentSpringTransaction.getAmountFailed() != null && paymentSpringTransaction.getAmountFailed() > 0) {
      transactionAmountInDollars = paymentSpringTransaction.getAmountFailed() / 100.0;
      transactionSuccess = false;
    } else {
      transactionAmountInDollars = paymentSpringTransaction.getAmountSettled() / 100.0;
      transactionSuccess = true;
    }
  }

  public void initPaymentSpring(com.impactupgrade.integration.paymentspring.model.Subscription paymentSpringSubscription) {
    initPaymentSpringCommon();
    // currently used for closing only, so no customer data needed

    initPaymentSpringSubscription(paymentSpringSubscription);
  }

  protected void initPaymentSpringCommon() {
    gatewayName = "PaymentSpring";
    // TODO: expand to include CC?
    paymentMethod = "ACH";
  }

  protected void initPaymentSpringCustomer(com.impactupgrade.integration.paymentspring.model.Transaction paymentSpringTransaction,
      Optional<com.impactupgrade.integration.paymentspring.model.Customer> paymentSpringCustomer) {

    // PaymentSpring pisses me off. There's no consistency in *anything*. SOMETIMES all the data is present in the
    // transaction event, other times it's not and you need the Customer, and we can't figure out the reason.
    // To be safe, always check both. And don't assume it's on the Customer either. Madness.

    crmContact.email = paymentSpringTransaction.getEmail();
    crmContact.firstName = paymentSpringTransaction.getFirstName();
    crmContact.lastName = paymentSpringTransaction.getLastName();
    crmContact.phone = paymentSpringTransaction.getPhone();

    if (paymentSpringCustomer.isPresent()) {
      customerId = paymentSpringCustomer.get().getId();

      if (Strings.isNullOrEmpty(crmContact.email)) {
        crmContact.email = paymentSpringCustomer.get().getEmail();
      }
      if (Strings.isNullOrEmpty(crmContact.firstName)) {
        crmContact.firstName = paymentSpringCustomer.get().getFirstName();
      }
      if (Strings.isNullOrEmpty(crmContact.lastName)) {
        crmContact.lastName = paymentSpringCustomer.get().getLastName();
      }
      if (Strings.isNullOrEmpty(crmContact.phone)) {
        crmContact.phone = paymentSpringCustomer.get().getPhone();
      }
    }

    // As an extra "Why not?", PS sometimes leaves off the name from everything but the Card Owner/Account Houlder.
    if (Strings.isNullOrEmpty(crmContact.firstName) && Strings.isNullOrEmpty(crmContact.lastName)) {
      String[] split = new String[0];
      if (!Strings.isNullOrEmpty(paymentSpringTransaction.getCardOwnerName())) {
        split = paymentSpringTransaction.getCardOwnerName().split(" ");
      } else if (!Strings.isNullOrEmpty(paymentSpringTransaction.getAccountHolderName())) {
        split = paymentSpringTransaction.getAccountHolderName().split(" ");
      }
      if (split.length == 2) {
        crmContact.firstName = split[0];
        crmContact.lastName = split[1];
      }
    }

    // Furthering the madness, staff can't manually change the campaign on a subscription, only the customer.
    // So check the customer *first* and let it override the subscription.
    if (paymentSpringCustomer.isPresent()) {
      campaignId = paymentSpringCustomer.get().getMetadata().get("sf_campaign_id");
      // some appear to be using "campaign", so try that too (SMH)
      if (Strings.isNullOrEmpty(campaignId)) {
        campaignId = paymentSpringCustomer.get().getMetadata().get("campaign");
      }
    }
    if (Strings.isNullOrEmpty(campaignId)) {
      campaignId = paymentSpringTransaction.getMetadata().get("sf_campaign_id");
      // some appear to be using "campaign", so try that too (SMH)
      if (Strings.isNullOrEmpty(campaignId)) {
        campaignId = paymentSpringTransaction.getMetadata().get("campaign");
      }
    }
    if (campaignId != null) {
      campaignId = campaignId.replaceAll("[^A-Za-z0-9]", "");
    }
  }

  protected void initPaymentSpringSubscription(
      com.impactupgrade.integration.paymentspring.model.Subscription paymentSpringSubscription) {
    subscriptionId = paymentSpringSubscription.getId();
    subscriptionInterval = paymentSpringSubscription.getFrequency();

    // PaymentSpring is in cents
    subscriptionAmountInDollars = paymentSpringSubscription.getAmount() / 100.0;
    subscriptionCurrency = "usd";
  }

  protected void initPaymentSpringSubscription(
      com.impactupgrade.integration.paymentspring.model.Subscription paymentSpringSubscription,
      com.impactupgrade.integration.paymentspring.model.Transaction paymentSpringTransaction) {
    initPaymentSpringSubscription(paymentSpringSubscription);

    subscriptionStartDate = getTransactionDate(paymentSpringTransaction.getCreatedAt());
    subscriptionNextDate = getTransactionDate(paymentSpringTransaction.getCreatedAt());
  }

  // DO NOT LET THESE BE AUTO-GENERATED, ALLOWING METADATARETRIEVER TO PROVIDE DEFAULTS

  public String getCrmAccountId() {
    if (Strings.isNullOrEmpty(crmAccount.id)) {
      return metadataRetriever.getMetadataValue(env.getConfig().metadataKeys.account);
    }
    return crmAccount.id;
  }

  public void setCrmAccountId(String crmAccountId) {
    crmAccount.id = crmAccountId;
    crmContact.accountId = crmAccountId;
  }

  public String getCrmContactId() {
    if (Strings.isNullOrEmpty(crmContact.id)) {
      return metadataRetriever.getMetadataValue(env.getConfig().metadataKeys.contact);
    }
    return crmContact.id;
  }

  public void setCrmContactId(String crmContactId) {
    crmContact.id = crmContactId;
  }

  public String getCrmRecurringDonationId() {
    // TODO: should we support looking up metadata on the Subscription?
    return crmRecurringDonationId;
  }

  public void setCrmRecurringDonationId(String crmRecurringDonationId) {
    this.crmRecurringDonationId = crmRecurringDonationId;
  }

  public String getDonationCrmRecordTypeId() {
    if (Strings.isNullOrEmpty(crmDonationRecordTypeId)) {
      return metadataRetriever.getMetadataValue(env.getConfig().metadataKeys.recordType);
    }
    return crmDonationRecordTypeId;
  }

  public void setDonationCrmRecordTypeId(String crmDonationRecordTypeId) {
    this.crmDonationRecordTypeId = crmDonationRecordTypeId;
  }

  public String getCampaignId() {
    if (Strings.isNullOrEmpty(campaignId)) {
      return metadataRetriever.getMetadataValue(env.getConfig().metadataKeys.campaign);
    }
    return campaignId;
  }

  public MetadataRetriever getMetadataRetriever() {
    return metadataRetriever;
  }

  // TRANSIENT

  public boolean isTransactionRecurring() {
    return !Strings.isNullOrEmpty(subscriptionId);
  }

  private Calendar getTransactionDate(Date date) {
    if (date != null) {
      Calendar calendar = Calendar.getInstance();
      calendar.setTime(date);
      return calendar;
    } else {
      return Calendar.getInstance();
    }
  }

  // GETTERS/SETTERS
  // Note that we allow setters here, as orgs sometimes need to override the values based on custom logic.

  public Environment getEnv() {
    return env;
  }

  public CrmAccount getCrmAccount() {
    return crmAccount;
  }

  public void setCrmAccount(CrmAccount crmAccount) {
    this.crmAccount = crmAccount;
  }

  public CrmContact getCrmContact() {
    return crmContact;
  }

  public void setCrmContact(CrmContact crmContact) {
    this.crmContact = crmContact;
  }

  public String getCustomerId() {
    return customerId;
  }

  public void setCustomerId(String customerId) {
    this.customerId = customerId;
  }

  public String getDepositTransactionId() {
    return depositTransactionId;
  }

  public void setDepositTransactionId(String depositTransactionId) {
    this.depositTransactionId = depositTransactionId;
  }

  public String getGatewayName() {
    return gatewayName;
  }

  public void setGatewayName(String gatewayName) {
    this.gatewayName = gatewayName;
  }

  public String getPaymentMethod() {
    return paymentMethod;
  }

  public void setPaymentMethod(String paymentMethod) {
    this.paymentMethod = paymentMethod;
  }

  public String getRefundId() {
    return refundId;
  }

  public void setRefundId(String refundId) {
    this.refundId = refundId;
  }

  public Calendar getRefundDate() {
    return refundDate;
  }

  public void setRefundDate(Calendar refundDate) { this.refundDate = refundDate; }

  public Double getSubscriptionAmountInDollars() {
    return subscriptionAmountInDollars;
  }

  public void setSubscriptionAmountInDollars(Double subscriptionAmountInDollars) {
    this.subscriptionAmountInDollars = subscriptionAmountInDollars;
  }

  public String getSubscriptionCurrency() {
    return subscriptionCurrency;
  }

  public void setSubscriptionCurrency(String subscriptionCurrency) {
    this.subscriptionCurrency = subscriptionCurrency;
  }

  public String getSubscriptionDescription() {
    return subscriptionDescription;
  }

  public void setSubscriptionDescription(String subscriptionDescription) {
    this.subscriptionDescription = subscriptionDescription;
  }

  public String getSubscriptionId() {
    return subscriptionId;
  }

  public void setSubscriptionId(String subscriptionId) {
    this.subscriptionId = subscriptionId;
  }

  public String getSubscriptionInterval() {
    return subscriptionInterval;
  }

  public void setSubscriptionInterval(String subscriptionInterval) {
    this.subscriptionInterval = subscriptionInterval;
  }

  public Calendar getSubscriptionNextDate() {
    return subscriptionNextDate;
  }

  public void setSubscriptionNextDate(Calendar subscriptionNextDate) {
    this.subscriptionNextDate = subscriptionNextDate;
  }

  public Calendar getSubscriptionStartDate() {
    return subscriptionStartDate;
  }

  public void setSubscriptionStartDate(Calendar subscriptionStartDate) {
    this.subscriptionStartDate = subscriptionStartDate;
  }

  public Double getTransactionAmountInDollars() {
    return transactionAmountInDollars;
  }

  public void setTransactionAmountInDollars(Double transactionAmountInDollars) {
    this.transactionAmountInDollars = transactionAmountInDollars;
  }

  public Double getTransactionNetAmountInDollars() {
    return transactionNetAmountInDollars;
  }

  public void setTransactionNetAmountInDollars(Double transactionNetAmountInDollars) {
    this.transactionNetAmountInDollars = transactionNetAmountInDollars;
  }

  public Calendar getTransactionDate() {
    return transactionDate;
  }

  public void setTransactionDate(Calendar transactionDate) {
    this.transactionDate = transactionDate;
  }

  public String getTransactionDescription() {
    return transactionDescription;
  }

  public void setTransactionDescription(String transactionDescription) {
    this.transactionDescription = transactionDescription;
  }

  public Double getTransactionExchangeRate() {
    return transactionExchangeRate;
  }

  public void setTransactionExchangeRate(Double transactionExchangeRate) {
    this.transactionExchangeRate = transactionExchangeRate;
  }

  public String getTransactionId() {
    return transactionId;
  }

  public void setTransactionId(String transactionId) {
    this.transactionId = transactionId;
  }

  public Double getTransactionOriginalAmountInDollars() {
    return transactionOriginalAmountInDollars;
  }

  public void setTransactionOriginalAmountInDollars(Double transactionOriginalAmountInDollars) {
    this.transactionOriginalAmountInDollars = transactionOriginalAmountInDollars;
  }

  public String getTransactionOriginalCurrency() {
    return transactionOriginalCurrency;
  }

  public void setTransactionOriginalCurrency(String transactionOriginalCurrency) {
    this.transactionOriginalCurrency = transactionOriginalCurrency;
  }

  public boolean isTransactionCurrencyConverted() {
    return transactionCurrencyConverted;
  }

  public void setTransactionCurrencyConverted(boolean transactionCurrencyConverted) {
    this.transactionCurrencyConverted = transactionCurrencyConverted;
  }

  public boolean isTransactionSuccess() {
    return transactionSuccess;
  }

  public void setTransactionSuccess(boolean transactionSuccess) {
    this.transactionSuccess = transactionSuccess;
  }

  public String getDepositId() {
    return depositId;
  }

  public void setDepositId(String depositId) {
    this.depositId = depositId;
  }

  public Calendar getDepositDate() {
    return depositDate;
  }

  public void setDepositDate(Calendar depositDate) {
    this.depositDate = depositDate;
  }

  // TODO: Auto generated, but then modified. Note that this is used for failure notifications sent to staff, etc.
  // We might be better off breaking this out into a separate, dedicated method.
  @Override
  public String toString() {
    return "PaymentGatewayEvent{" +

        "fullName='" + crmAccount.name + '\'' +
        ", firstName='" + crmContact.firstName + '\'' +
        ", lastName='" + crmContact.lastName + '\'' +
        ", email='" + crmContact.email + '\'' +
        ", phone='" + crmContact.phone + '\'' +

        ", street='" + crmContact.address.street + '\'' +
        ", city='" + crmContact.address.city + '\'' +
        ", state='" + crmContact.address.state + '\'' +
        ", zip='" + crmContact.address.postalCode + '\'' +
        ", country='" + crmContact.address.country + '\'' +

        ", gatewayName='" + gatewayName + '\'' +
        ", paymentMethod='" + paymentMethod + '\'' +

        ", customerId='" + customerId + '\'' +
        ", transactionId='" + transactionId + '\'' +
        ", subscriptionId='" + subscriptionId + '\'' +
        ", campaignId='" + campaignId + '\'' +

        ", transactionDate=" + transactionDate +
        ", transactionSuccess=" + transactionSuccess +
        ", transactionDescription='" + transactionDescription + '\'' +
        ", transactionAmountInDollars=" + transactionAmountInDollars +
        ", transactionNetAmountInDollars=" + transactionNetAmountInDollars +
        ", transactionExchangeRate=" + transactionExchangeRate +
        ", transactionOriginalAmountInDollars=" + transactionOriginalAmountInDollars +
        ", transactionOriginalCurrency='" + transactionOriginalCurrency + '\'' +
        ", transactionCurrencyConverted='" + transactionCurrencyConverted + '\'' +

        ", subscriptionAmountInDollars=" + subscriptionAmountInDollars +
        ", subscriptionCurrency='" + subscriptionCurrency + '\'' +
        ", subscriptionDescription='" + subscriptionDescription + '\'' +
        ", subscriptionInterval='" + subscriptionInterval + '\'' +
        ", subscriptionStartDate=" + subscriptionStartDate +
        ", subscriptionNextDate=" + subscriptionNextDate +

        ", primaryCrmAccountId='" + crmAccount.id + '\'' +
        ", primaryCrmContactId='" + crmContact.id + '\'' +
        ", primaryCrmRecordTypeId='" + crmDonationRecordTypeId + '\'' +
        ", primaryCrmRecurringDonationId='" + crmRecurringDonationId +
        '}';
  }
}
