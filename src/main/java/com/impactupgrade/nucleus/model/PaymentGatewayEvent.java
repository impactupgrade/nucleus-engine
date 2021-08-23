/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.util.Utils;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Card;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;

import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PaymentGatewayEvent {

  protected final Environment env;

  // determined by event
  protected CrmAccount crmAccount = new CrmAccount();
  protected CrmContact crmContact = new CrmContact();
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
  protected String transactionId; // ex: Stripe PaymentIntent ID, or the Charge ID if this was a simple Charge API use
  protected String transactionSecondaryId; // ex: Stripe Charge ID if this was the Payment Intent API
  protected Double transactionOriginalAmountInDollars;
  protected String transactionOriginalCurrency;
  protected boolean transactionCurrencyConverted;
  protected boolean transactionSuccess;
  protected String transactionUrl;

  // context set within processing steps OR pulled from event metadata
  protected String crmRecurringDonationId;
  protected String depositId;
  protected Calendar depositDate;

  // Maps holding metadata content. We need to split these up in order to define an ordered hierarchy of values.
  private Map<String, String> contextMetadata = new HashMap<>();
  private Map<String, String> transactionMetadata = new HashMap<>();
  private Map<String, String> subscriptionMetadata = new HashMap<>();
  private Map<String, String> customerMetadata = new HashMap<>();

  public PaymentGatewayEvent(Environment env) {
    this.env = env;
  }

  // IMPORTANT! We're remove all non-numeric chars on all metadata fields -- it appears a few campaign IDs were pasted
  // into forms and contain NBSP characters :(

  public void initStripe(Charge stripeCharge, Optional<Customer> stripeCustomer,
      Optional<Invoice> stripeInvoice, Optional<BalanceTransaction> stripeBalanceTransaction) {
    initStripeCommon();

    // NOTE: See the note on the StripeService's customer.subscription.created event handling. We insert recurring donations
    // from subscription creation ONLY if it's in a trial period and starts in the future. Otherwise, let the
    // first donation do it in order to prevent timing issues.
    if (stripeInvoice.isPresent() && !Strings.isNullOrEmpty(stripeInvoice.get().getSubscription())) {
      initStripeSubscription(stripeInvoice.get().getSubscriptionObject(), stripeCustomer.get());
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
    transactionUrl = "https://dashboard.stripe.com/charges/" + stripeCharge.getId();

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

    addMetadata(stripeCharge.getMetadata(), transactionMetadata);

    // Always do this last! We need all the metadata context to fill out the customer details.
    addMetadata(stripeCustomer.map(Customer::getMetadata).orElse(null), customerMetadata);
    initStripeCustomer(stripeCustomer);
  }

  public void initStripe(PaymentIntent stripePaymentIntent, Optional<Customer> stripeCustomer,
      Optional<Invoice> stripeInvoice, Optional<BalanceTransaction> stripeBalanceTransaction) {
    initStripeCommon();

    // NOTE: See the note on the StripeService's customer.subscription.created event handling. We insert recurring donations
    // from subscription creation ONLY if it's in a trial period and starts in the future. Otherwise, let the
    // first donation do it in order to prevent timing issues.
    if (stripeInvoice.isPresent() && !Strings.isNullOrEmpty(stripeInvoice.get().getSubscription())) {
      initStripeSubscription(stripeInvoice.get().getSubscriptionObject(), stripeCustomer.get());
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
    transactionSecondaryId = stripePaymentIntent.getCharges().getData().stream().findFirst().map(Charge::getId).orElse(null);
    // note this is different than a charge, which uses !"failed" -- intents have multiple phases of "didn't work",
    // so explicitly search for succeeded
    transactionSuccess = "succeeded".equalsIgnoreCase(stripePaymentIntent.getStatus());
    transactionUrl = "https://dashboard.stripe.com/payments/" + stripePaymentIntent.getId();

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

    addMetadata(stripePaymentIntent.getMetadata(), transactionMetadata);

    // Always do this last! We need all the metadata context to fill out the customer details.
    addMetadata(stripeCustomer.map(Customer::getMetadata).orElse(null), customerMetadata);
    initStripeCustomer(stripeCustomer);
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

    initStripeSubscription(stripeSubscription, stripeCustomer);

    // Always do this last! We need all the metadata context to fill out the customer details.
    initStripeCustomer(Optional.of(stripeCustomer));
  }

  protected void initStripeCommon() {
    gatewayName = "Stripe";
    // TODO: expand to include ACH through Plaid?
    paymentMethod = "credit card";
  }

  protected void initStripeCustomer(Optional<Customer> __stripeCustomer) {
    if (__stripeCustomer.isPresent()) {
      Customer stripeCustomer = __stripeCustomer.get();

      customerId = stripeCustomer.getId();

      crmContact.email = stripeCustomer.getEmail();

      crmContact.mobilePhone = stripeCustomer.getPhone();

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

    initStripeCustomerName(__stripeCustomer);

    if (Strings.isNullOrEmpty(crmContact.email)) {
      crmContact.email = getAllMetadata().entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return (key.contains("email"));
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }
  }

  // What happens in this method seems ridiculous, but we're trying to resiliently deal with a variety of situations.
  // Some donation forms and vendors use true Customer names, others use metadata on Customer, other still only put
  // names in metadata on the Charge or Subscription. Madness. But let's be helpful...
  protected void initStripeCustomerName(Optional<Customer> stripeCustomer) {
    Map<String, String> metadata = getAllMetadata();

    // For the full name, start with Customer name. Generally this is populated, but a few vendors don't always do it.
    crmAccount.name = stripeCustomer.map(Customer::getName).orElse(null);
    // If that didn't work, look in the metadata. We've seen variations of "customer" or "full" name used.
    if (Strings.isNullOrEmpty(crmAccount.name)) {
      crmAccount.name = metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return (key.contains("customer") || key.contains("full")) && key.contains("name");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }
    // If that still didn't work, look in the backup metadata (typically a charge or subscription).
    if (Strings.isNullOrEmpty(crmAccount.name)) {
      crmAccount.name = metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return (key.contains("customer") || key.contains("full")) && key.contains("name");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }

    // Now do first name, again using metadata.
    crmContact.firstName = metadata.entrySet().stream().filter(e -> {
      String key = e.getKey().toLowerCase(Locale.ROOT);
      return key.contains("first") && key.contains("name");
    }).findFirst().map(Map.Entry::getValue).orElse(null);
    if (Strings.isNullOrEmpty(crmContact.firstName)) {
      crmContact.firstName = metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("first") && key.contains("name");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }

    // And now the last name.
    crmContact.lastName = metadata.entrySet().stream().filter(e -> {
      String key = e.getKey().toLowerCase(Locale.ROOT);
      return key.contains("last") && key.contains("name");
    }).findFirst().map(Map.Entry::getValue).orElse(null);
    if (Strings.isNullOrEmpty(crmContact.lastName)) {
      crmContact.lastName = metadata.entrySet().stream().filter(e -> {
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

    addMetadata(stripeSubscription.getMetadata(), subscriptionMetadata);
    addMetadata(stripeCustomer.getMetadata(), customerMetadata);

    // TODO: We could shift this to MetadataRetriever, but odds are we're the only ones setting it...
    subscriptionDescription = stripeSubscription.getMetadata().get("description");
  }

  private void addMetadata(Map<String, String> newEntries, Map<String, String> currentEntries) {
    if (newEntries != null) {
      currentEntries.putAll(newEntries);
    }
  }

  public void addMetadata(String key, String value) {
    contextMetadata.put(key, value);
  }

  public String getMetadataValue(String metadataKey) {
    return getMetadataValue(Set.of(metadataKey));
  }

  public String getMetadataValue(Collection<String> metadataKeys) {
    String metadataValue = null;

    for (String metadataKey : metadataKeys) {
      // Always start with the raw context and let it trump everything else.
      if (contextMetadata.containsKey(metadataKey) && !Strings.isNullOrEmpty(contextMetadata.get(metadataKey))) {
        metadataValue = contextMetadata.get(metadataKey);
      } else if (transactionMetadata.containsKey(metadataKey) && !Strings.isNullOrEmpty(transactionMetadata.get(metadataKey))) {
        metadataValue = transactionMetadata.get(metadataKey);
      } else if (subscriptionMetadata.containsKey(metadataKey) && !Strings.isNullOrEmpty(subscriptionMetadata.get(metadataKey))) {
        metadataValue = subscriptionMetadata.get(metadataKey);
      } else if (customerMetadata.containsKey(metadataKey) && !Strings.isNullOrEmpty(customerMetadata.get(metadataKey))) {
        metadataValue = customerMetadata.get(metadataKey);
      }
    }

    if (metadataValue != null) {
      // IMPORTANT: The keys and values are sometimes copy/pasted by a human and we've had issues with whitespace.
      // Strip it! But note that sometimes it's something like a non-breaking space char (pasted from a doc?),
      // so convert that to a standard space first.
      metadataValue = metadataValue.replaceAll("[\\h+]", " ");
      metadataValue = metadataValue.trim();
    }

    return metadataValue;
  }

  private Map<String, String> getAllMetadata() {
    // In order!
    return Stream.of(contextMetadata, transactionMetadata, subscriptionMetadata, customerMetadata)
        .flatMap(map -> map.entrySet().stream())
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            Map.Entry::getValue,
            // If there's a duplicate key, always keep the first! We define the order of precedence, above.
            (v1, v2) -> v1
        ));
  }

  // DO NOT LET THESE BE AUTO-GENERATED, ALLOWING METADATARETRIEVER TO PROVIDE DEFAULTS

  public CrmAccount getCrmAccount() {
    // If we don't yet have an ID, but event metadata has one defined, use that as a default
    if (Strings.isNullOrEmpty(crmAccount.id)) {
      crmAccount.id = getMetadataValue(env.getConfig().metadataKeys.account);
    }
    return crmAccount;
  }

  public void setCrmAccount(CrmAccount crmAccount) {
    this.crmAccount = crmAccount;
  }

  public void setCrmAccountId(String crmAccountId) {
    crmAccount.id = crmAccountId;
    crmContact.accountId = crmAccountId;
  }

  public CrmContact getCrmContact() {
    // If we don't yet have an ID, but event metadata has one defined, use that as a default
    if (Strings.isNullOrEmpty(crmContact.id)) {
      crmContact.id = getMetadataValue(env.getConfig().metadataKeys.contact);
    }
    return crmContact;
  }

  public void setCrmContact(CrmContact crmContact) {
    this.crmContact = crmContact;
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

  public String getTransactionSecondaryId() {
    return transactionSecondaryId;
  }

  public void setTransactionSecondaryId(String transactionSecondaryId) {
    this.transactionSecondaryId = transactionSecondaryId;
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

  public String getTransactionUrl() {
    return transactionUrl;
  }

  public void setTransactionUrl(String transactionUrl) {
    this.transactionUrl = transactionUrl;
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
        ", mobilePhone='" + crmContact.mobilePhone + '\'' +

        ", street='" + crmContact.address.street + '\'' +
        ", city='" + crmContact.address.city + '\'' +
        ", state='" + crmContact.address.state + '\'' +
        ", zip='" + crmContact.address.postalCode + '\'' +
        ", country='" + crmContact.address.country + '\'' +

        ", gatewayName='" + gatewayName + '\'' +
        ", paymentMethod='" + paymentMethod + '\'' +

        ", customerId='" + customerId + '\'' +
        ", transactionId='" + transactionId + '\'' +
        ", transactionSecondaryId='" + transactionSecondaryId + '\'' +
        ", subscriptionId='" + subscriptionId + '\'' +

        ", transactionDate=" + transactionDate +
        ", transactionSuccess=" + transactionSuccess +
        ", transactionDescription='" + transactionDescription + '\'' +
        ", transactionAmountInDollars=" + transactionAmountInDollars +
        ", transactionNetAmountInDollars=" + transactionNetAmountInDollars +
        ", transactionExchangeRate=" + transactionExchangeRate +
        ", transactionOriginalAmountInDollars=" + transactionOriginalAmountInDollars +
        ", transactionOriginalCurrency='" + transactionOriginalCurrency + '\'' +
        ", transactionCurrencyConverted='" + transactionCurrencyConverted + '\'' +
        ", transactionUrl=" + transactionUrl +

        ", subscriptionAmountInDollars=" + subscriptionAmountInDollars +
        ", subscriptionCurrency='" + subscriptionCurrency + '\'' +
        ", subscriptionDescription='" + subscriptionDescription + '\'' +
        ", subscriptionInterval='" + subscriptionInterval + '\'' +
        ", subscriptionStartDate=" + subscriptionStartDate +
        ", subscriptionNextDate=" + subscriptionNextDate +

        ", primaryCrmAccountId='" + crmAccount.id + '\'' +
        ", primaryCrmContactId='" + crmContact.id + '\'' +
        ", primaryCrmRecurringDonationId='" + crmRecurringDonationId +
        '}';
  }
}
