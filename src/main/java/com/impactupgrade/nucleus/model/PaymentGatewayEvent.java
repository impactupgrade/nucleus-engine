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
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.util.CaseInsensitiveMap;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PaymentGatewayEvent {

  protected final Environment env;

  // For convenience's sake, making use of CRM models, here, to make downstream processing cleaner.
  protected CrmAccount crmAccount = new CrmAccount();
  protected CrmContact crmContact = new CrmContact();

  protected String customerId;
  protected String depositTransactionId;
  protected String gatewayName;
  protected String paymentMethod;
  // TODO: Ex: If the payment involved a Stripe invoice, capture the product ID for each line item. We eventually may
  //  need to refactor this to provide additional info, but let's see how it goes.
  protected List<String> products = new ArrayList<>();
  protected String refundId;
  protected ZonedDateTime refundDate;
  protected Double subscriptionAmountInDollars;
  protected String subscriptionCurrency;
  protected String subscriptionDescription;
  protected String subscriptionId;
  protected String subscriptionInterval;
  protected ZonedDateTime subscriptionNextDate;
  protected ZonedDateTime subscriptionStartDate;
  protected Double transactionAmountInDollars;
  protected Double transactionNetAmountInDollars;
  protected ZonedDateTime transactionDate;
  protected String transactionDescription;
  protected Double transactionExchangeRate;
  protected Double transactionFeeInDollars;
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
  protected ZonedDateTime depositDate;

  // Maps holding metadata content. We need to split these up in order to define an ordered hierarchy of values.
  // VITAL: Allow these to be case insensitive!
  private final Map<String, String> contextMetadata = new CaseInsensitiveMap<>();
  private final Map<String, String> transactionMetadata = new CaseInsensitiveMap<>();
  private final Map<String, String> subscriptionMetadata = new CaseInsensitiveMap<>();
  private final Map<String, String> customerMetadata = new CaseInsensitiveMap<>();

  public PaymentGatewayEvent(Environment env) {
    this.env = env;
  }

  // IMPORTANT! We're remove all non-numeric chars on all metadata fields -- it appears a few campaign IDs were pasted
  // into forms and contain NBSP characters :(

  public void initStripe(Charge stripeCharge, Optional<Customer> stripeCustomer,
      Optional<Invoice> stripeInvoice, Optional<BalanceTransaction> stripeBalanceTransaction) {
    gatewayName = "Stripe";
    String stripePaymentMethod = stripeCharge.getPaymentMethodDetails().getType();
    if (stripePaymentMethod.toLowerCase(Locale.ROOT).contains("ach")) {
      paymentMethod = "ACH";
    } else {
      paymentMethod = "Credit Card";
    }

    if (stripeInvoice.isPresent()) {
      if (stripeInvoice.get().getLines() != null) {
        products = stripeInvoice.get().getLines().getData().stream().map(
            line -> line.getPrice() == null || line.getPrice().getProduct() == null ? null : line.getPrice().getProduct()
        ).filter(Objects::nonNull).collect(Collectors.toList());
      }

      // NOTE: See the note on the StripeService's customer.subscription.created event handling. We insert recurring donations
      // from subscription creation ONLY if it's in a trial period and starts in the future. Otherwise, let the
      // first donation do it in order to prevent timing issues.
      if (!Strings.isNullOrEmpty(stripeInvoice.get().getSubscription())) {
        initStripeSubscription(stripeInvoice.get().getSubscriptionObject(), stripeCustomer.get());
      }
    }

    if (stripeCharge.getCreated() != null) {
      transactionDate = ZonedDateTime.ofInstant(Instant.ofEpochSecond(stripeCharge.getCreated()), ZoneId.of("UTC"));
    } else {
      transactionDate = ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("UTC"));
    }

    transactionDescription = stripeCharge.getDescription();
    transactionId = stripeCharge.getId();
    transactionSuccess = !"failed".equalsIgnoreCase(stripeCharge.getStatus());
    transactionUrl = "https://dashboard.stripe.com/charges/" + stripeCharge.getId();

    transactionOriginalAmountInDollars = stripeCharge.getAmount() / 100.0;
    transactionOriginalCurrency = stripeCharge.getCurrency().toUpperCase(Locale.ROOT);

    stripeBalanceTransaction.ifPresent(bt -> {
      depositTransactionId = bt.getId();
      transactionNetAmountInDollars = bt.getNet() / 100.0;
      transactionFeeInDollars = bt.getFee() / 100.0;
    });

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
    initStripeCustomer(stripeCustomer, Optional.ofNullable(stripeCharge.getBillingDetails()));
  }

  public void initStripe(PaymentIntent stripePaymentIntent, Optional<Customer> stripeCustomer,
      Optional<Invoice> stripeInvoice, Optional<BalanceTransaction> stripeBalanceTransaction) {
    gatewayName = "Stripe";
    String stripePaymentMethod = stripePaymentIntent.getCharges().getData().stream().findFirst().map(c -> c.getPaymentMethodDetails().getType()).orElse("");
    if (stripePaymentMethod.toLowerCase(Locale.ROOT).contains("ach")) {
      paymentMethod = "ACH";
    } else {
      paymentMethod = "Credit Card";
    }

    if (stripeInvoice.isPresent()) {
      if (stripeInvoice.get().getLines() != null) {
        products = stripeInvoice.get().getLines().getData().stream().map(
            line -> line.getPrice() == null || line.getPrice().getProduct() == null ? null : line.getPrice().getProduct()
        ).filter(Objects::nonNull).collect(Collectors.toList());
      }

      // NOTE: See the note on the StripeService's customer.subscription.created event handling. We insert recurring donations
      // from subscription creation ONLY if it's in a trial period and starts in the future. Otherwise, let the
      // first donation do it in order to prevent timing issues.
      if (!Strings.isNullOrEmpty(stripeInvoice.get().getSubscription())) {
        initStripeSubscription(stripeInvoice.get().getSubscriptionObject(), stripeCustomer.get());
      }
    }

    if (stripePaymentIntent.getCreated() != null) {
      transactionDate = ZonedDateTime.ofInstant(Instant.ofEpochSecond(stripePaymentIntent.getCreated()), ZoneId.of("UTC"));
    } else {
      transactionDate = ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("UTC"));
    }

    transactionDescription = stripePaymentIntent.getDescription();
    transactionId = stripePaymentIntent.getId();
    transactionSecondaryId = stripePaymentIntent.getCharges().getData().stream().findFirst().map(Charge::getId).orElse(null);
    // note this is different than a charge, which uses !"failed" -- intents have multiple phases of "didn't work",
    // so explicitly search for succeeded
    transactionSuccess = "succeeded".equalsIgnoreCase(stripePaymentIntent.getStatus());
    transactionUrl = "https://dashboard.stripe.com/payments/" + stripePaymentIntent.getId();

    transactionOriginalAmountInDollars = stripePaymentIntent.getAmount() / 100.0;
    transactionOriginalCurrency = stripePaymentIntent.getCurrency().toUpperCase(Locale.ROOT);

    stripeBalanceTransaction.ifPresent(bt -> {
      depositTransactionId = bt.getId();
      transactionNetAmountInDollars = bt.getNet() / 100.0;
      transactionFeeInDollars = bt.getFee() / 100.0;
    });

    if (env.getConfig().currency.equalsIgnoreCase(stripePaymentIntent.getCurrency().toUpperCase(Locale.ROOT))) {
      // currency is the same as the org receiving the funds, so no conversion necessary
      transactionAmountInDollars = stripePaymentIntent.getAmount() / 100.0;
    } else {
      transactionCurrencyConverted = true;
      // currency is different than what the org is expecting, so assume it was converted
      // TODO: this implies the values will *not* be set for failed transactions!
      if (stripeBalanceTransaction.isPresent()) {
        transactionAmountInDollars = stripeBalanceTransaction.get().getAmount() / 100.0;
        BigDecimal exchangeRate = stripeBalanceTransaction.get().getExchangeRate();
        if (exchangeRate != null) {
          transactionExchangeRate = exchangeRate.doubleValue();
        }
      }
    }

    transactionDescription = stripePaymentIntent.getDescription();

    addMetadata(stripePaymentIntent.getMetadata(), transactionMetadata);

    // Always do this last! We need all the metadata context to fill out the customer details.
    addMetadata(stripeCustomer.map(Customer::getMetadata).orElse(null), customerMetadata);
    initStripeCustomer(stripeCustomer, Optional.empty());
  }

  public void initStripe(Refund stripeRefund) {
    gatewayName = "Stripe";

    refundId = stripeRefund.getId();
    if (!Strings.isNullOrEmpty(stripeRefund.getPaymentIntent())) {
      transactionId = stripeRefund.getPaymentIntent();
      transactionSecondaryId = stripeRefund.getCharge();
    } else {
      transactionId = stripeRefund.getCharge();
    }

    if (stripeRefund.getCreated() != null) {
      refundDate = ZonedDateTime.ofInstant(Instant.ofEpochSecond(stripeRefund.getCreated()), ZoneId.of("UTC"));
    } else {
      refundDate = ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("UTC"));
    }
  }

  public void initStripe(Subscription stripeSubscription, Customer stripeCustomer) {
    gatewayName = "Stripe";

    initStripeSubscription(stripeSubscription, stripeCustomer);

    // Always do this last! We need all the metadata context to fill out the customer details.
    initStripeCustomer(Optional.of(stripeCustomer), Optional.empty());
  }

  protected void initStripeCustomer(Optional<Customer> __stripeCustomer, Optional<PaymentMethod.BillingDetails> billingDetails) {
    Map<String, String> metadata = getAllMetadata();

    if (__stripeCustomer.isPresent()) {
      Customer stripeCustomer = __stripeCustomer.get();

      customerId = stripeCustomer.getId();

      crmContact.email = stripeCustomer.getEmail();
      crmContact.mobilePhone = stripeCustomer.getPhone();
    }

    // backfill with metadata if needed
    if (Strings.isNullOrEmpty(crmContact.email)) {
      crmContact.email = metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return (key.contains("email"));
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }
    if (Strings.isNullOrEmpty(crmContact.mobilePhone)) {
      // TODO: Do we need to break this down into the different phone numbers?
      crmContact.mobilePhone = metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return (key.contains("phone"));
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }

    initStripeCustomerName(__stripeCustomer, billingDetails);
    initStripeAddress(__stripeCustomer, billingDetails);
  }

  // What happens in this method seems ridiculous, but we're trying to resiliently deal with a variety of situations.
  // Some donation forms and vendors use true Customer names, others use metadata on Customer, other still only put
  // names in metadata on the Charge or Subscription. Madness. But let's be helpful...
  protected void initStripeCustomerName(Optional<Customer> stripeCustomer, Optional<PaymentMethod.BillingDetails> billingDetails) {
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
    // Still nothing? Try the billing details.
    if (Strings.isNullOrEmpty(crmAccount.name) && billingDetails.isPresent()
        && !Strings.isNullOrEmpty(billingDetails.get().getName())
        // Some vendors, like Custom Donations, may use email as the billing details name if no true name
        // was available. Sanity check and skip if so...
        && !billingDetails.get().getName().contains("@")) {
      crmAccount.name = billingDetails.get().getName();
    }
    // And finally, because some platforms are silly and use Description, try that...
    if (Strings.isNullOrEmpty(crmAccount.name)) {
      crmAccount.name = stripeCustomer.map(Customer::getDescription).orElse(null);
    }

    // Now do first name, again using metadata. Don't do "contains 'first' and contains 'name'", since that would also
    // pick up, as an example, Raisely's use of fundraiser_first_name. Instead, use regex that's a little more explicit.
    crmContact.firstName = metadata.entrySet().stream().filter(e -> {
      String key = e.getKey().toLowerCase(Locale.ROOT);
      return key.matches("(?i)first.*name");
    }).findFirst().map(Map.Entry::getValue).orElse(null);
    if (Strings.isNullOrEmpty(crmContact.firstName)) {
      crmContact.firstName = metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.matches("(?i)first.*name");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }

    // And now the last name.
    crmContact.lastName = metadata.entrySet().stream().filter(e -> {
      String key = e.getKey().toLowerCase(Locale.ROOT);
      return key.matches("(?i)last.*name");
    }).findFirst().map(Map.Entry::getValue).orElse(null);
    if (Strings.isNullOrEmpty(crmContact.lastName)) {
      crmContact.lastName = metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.matches("(?i)last.*name");
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

    // Finally, if we have no name at all, use a default. Many/most CRMs will fail without something.
    if (Strings.isNullOrEmpty(crmAccount.name) && Strings.isNullOrEmpty(crmContact.lastName)) {
      crmAccount.name = "Anonymous Account";
      crmContact.firstName = "Anonymous";
      crmContact.lastName = "Contact";
    }
  }

  protected void initStripeAddress(Optional<Customer> __stripeCustomer, Optional<PaymentMethod.BillingDetails> billingDetails) {
    CrmAddress crmAddress = new CrmAddress();

    if (__stripeCustomer.isPresent()) {
      Customer stripeCustomer = __stripeCustomer.get();
      if (stripeCustomer.getAddress() != null) {
        crmAddress.street = stripeCustomer.getAddress().getLine1();
        if (!Strings.isNullOrEmpty(stripeCustomer.getAddress().getLine2())) {
          crmAddress.street += ", " + stripeCustomer.getAddress().getLine2();
        }
        crmAddress.city = stripeCustomer.getAddress().getCity();
        crmAddress.state = stripeCustomer.getAddress().getState();
        crmAddress.postalCode = stripeCustomer.getAddress().getPostalCode();
        crmAddress.country = stripeCustomer.getAddress().getCountry();
      } else if (stripeCustomer.getSources() != null) {
        // use the first payment source, but don't use the default source, since we can't guarantee it's set as a card
        // TODO: This will need rethought after Donor Portal is launched and Stripe is used for ACH!
        stripeCustomer.getSources().getData().stream()
            .filter(s -> s instanceof Card)
            .map(s -> (Card) s)
            .findFirst()
            .ifPresent(stripeCard -> {
              crmAddress.street = stripeCard.getAddressLine1();
              if (!Strings.isNullOrEmpty(stripeCard.getAddressLine2())) {
                crmAddress.street += ", " + stripeCard.getAddressLine2();
              }
              crmAddress.city = stripeCard.getAddressCity();
              crmAddress.state = stripeCard.getAddressState();
              crmAddress.postalCode = stripeCard.getAddressZip();
              crmAddress.country = stripeCard.getAddressCountry();
            });
      }
    }

    // Also try the source right on the Charge, if it wasn't on the Customer.
    if (Strings.isNullOrEmpty(crmAddress.street) && billingDetails.isPresent() && billingDetails.get().getAddress() != null) {
      crmAddress.street = billingDetails.get().getAddress().getLine1();
      if (!Strings.isNullOrEmpty(billingDetails.get().getAddress().getLine2())) {
        crmAddress.street += ", " + billingDetails.get().getAddress().getLine2();
      }
      crmAddress.city = billingDetails.get().getAddress().getCity();
      crmAddress.state = billingDetails.get().getAddress().getState();
      crmAddress.postalCode = billingDetails.get().getAddress().getPostalCode();
      crmAddress.country = billingDetails.get().getAddress().getCountry();
    }

    // If the customer and sources didn't have the full address, try metadata from both.
    if (Strings.isNullOrEmpty(crmAddress.street)) {
      Map<String, String> metadata = getAllMetadata();

      // TODO: The stream and filter are getting repetitive (see initStripeCustomerName as well). DRY it up
      crmAddress.street = metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("street") || key.contains("address");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
      crmAddress.city = metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("city");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
      crmAddress.state = metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("state");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
      crmAddress.postalCode = metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("postal") || key.contains("zip");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
      crmAddress.country = metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("country");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }

    crmAccount.address = crmAddress;
    crmContact.address = crmAddress;
  }

  // Keep stripeCustomer, even though we don't use it here -- needed in subclasses.
  protected void initStripeSubscription(Subscription stripeSubscription, Customer stripeCustomer) {
    if (stripeSubscription.getTrialEnd() != null) {
      subscriptionStartDate = ZonedDateTime.ofInstant(Instant.ofEpochSecond(stripeSubscription.getTrialEnd()), ZoneId.of("UTC"));
    } else {
      subscriptionStartDate = ZonedDateTime.ofInstant(Instant.ofEpochSecond(stripeSubscription.getStartDate()), ZoneId.of("UTC"));
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

  public Map<String, String> getAllMetadata() {
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

  public List<String> getProducts() {
    return products;
  }

  public void setProducts(List<String> products) {
    this.products = products;
  }

  public String getRefundId() {
    return refundId;
  }

  public void setRefundId(String refundId) {
    this.refundId = refundId;
  }

  public ZonedDateTime getRefundDate() {
    return refundDate;
  }

  public void setRefundDate(ZonedDateTime refundDate) { this.refundDate = refundDate; }

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

  public ZonedDateTime getSubscriptionNextDate() {
    return subscriptionNextDate;
  }

  public void setSubscriptionNextDate(ZonedDateTime subscriptionNextDate) {
    this.subscriptionNextDate = subscriptionNextDate;
  }

  public ZonedDateTime getSubscriptionStartDate() {
    return subscriptionStartDate;
  }

  public void setSubscriptionStartDate(ZonedDateTime subscriptionStartDate) {
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

  public ZonedDateTime getTransactionDate() {
    return transactionDate;
  }

  public void setTransactionDate(ZonedDateTime transactionDate) {
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

  public Double getTransactionFeeInDollars() {
    return transactionFeeInDollars;
  }

  public void setTransactionFeeInDollars(Double transactionFeeInDollars) {
    this.transactionFeeInDollars = transactionFeeInDollars;
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

  public ZonedDateTime getDepositDate() {
    return depositDate;
  }

  public void setDepositDate(ZonedDateTime depositDate) {
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

        ", products='" + String.join(",", products) + '\'' +

        ", transactionDate=" + transactionDate +
        ", transactionSuccess=" + transactionSuccess +
        ", transactionDescription='" + transactionDescription + '\'' +
        ", transactionAmountInDollars=" + transactionAmountInDollars +
        ", transactionNetAmountInDollars=" + transactionNetAmountInDollars +
        ", transactionExchangeRate=" + transactionExchangeRate +
        ", transactionFeeInDollars=" + transactionFeeInDollars +
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
