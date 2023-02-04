/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
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

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PaymentGatewayEvent implements Serializable {

  protected final EnvironmentConfig envConfig;

  // For convenience's sake, making use of CRM models, here, to make downstream processing cleaner.
  // Important to not make these public -- note how the setters are multistep!
  protected CrmAccount crmAccount = new CrmAccount();
  protected CrmContact crmContact = new CrmContact();
  protected CrmDonation crmDonation = new CrmDonation();
  protected CrmRecurringDonation crmRecurringDonation = new CrmRecurringDonation();

  protected String application;

  // If enrichment results in multiple events (ex: a split of a deductible and non-deductible transaction),
  // nest the secondary events under the primary so downstream processing is aware of the connection.
  protected List<PaymentGatewayEvent> secondaryEvents = new ArrayList<>();

  public PaymentGatewayEvent(Environment env) {
    envConfig = env.getConfig();

    crmDonation.account = crmAccount;
    crmDonation.contact = crmContact;
    crmRecurringDonation.account = crmAccount;
    crmRecurringDonation.contact = crmContact;
  }

  // IMPORTANT! We remove all non-numeric chars on all metadata fields -- it appears a few campaign IDs were pasted
  // into forms and contain NBSP characters :(

  public void initStripe(Charge stripeCharge, Optional<Customer> stripeCustomer,
      Optional<Invoice> stripeInvoice, Optional<BalanceTransaction> stripeBalanceTransaction) {
    crmDonation.metadata.putAll(stripeCharge.getMetadata());
    // some fundraising platforms put most of the donor metadata on the charge/subscription, so include them here
    crmAccount.metadata.putAll(stripeCharge.getMetadata());
    crmContact.metadata.putAll(stripeCharge.getMetadata());

    crmDonation.gatewayName = "Stripe";
    application = stripeCharge.getApplication();
    String stripePaymentMethod = stripeCharge.getPaymentMethodDetails().getType();
    if (stripePaymentMethod.toLowerCase(Locale.ROOT).contains("ach")) {
      crmDonation.paymentMethod = "ACH";
    } else {
      crmDonation.paymentMethod = "Credit Card";
    }

    if (stripeInvoice.isPresent()) {
      if (stripeInvoice.get().getLines() != null) {
        crmDonation.products = stripeInvoice.get().getLines().getData().stream().map(
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
      crmDonation.closeDate = ZonedDateTime.ofInstant(Instant.ofEpochSecond(stripeCharge.getCreated()), ZoneId.of("UTC"));
    } else {
      crmDonation.closeDate = ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("UTC"));
    }

    crmDonation.description = stripeCharge.getDescription();
    crmDonation.transactionId = stripeCharge.getId();
    crmDonation.status = "failed".equalsIgnoreCase(stripeCharge.getStatus()) ? CrmDonation.Status.FAILED : CrmDonation.Status.SUCCESSFUL;
    crmDonation.url = "https://dashboard.stripe.com/charges/" + stripeCharge.getId();

    crmDonation.originalAmountInDollars = stripeCharge.getAmount() / 100.0;
    crmDonation.originalCurrency = stripeCharge.getCurrency().toUpperCase(Locale.ROOT);

    stripeBalanceTransaction.ifPresent(bt -> {
      crmDonation.depositTransactionId = bt.getId();
      crmDonation.netAmountInDollars = bt.getNet() / 100.0;
      crmDonation.feeInDollars = bt.getFee() / 100.0;
    });

    if (envConfig.currency.equalsIgnoreCase(stripeCharge.getCurrency().toUpperCase(Locale.ROOT))) {
      // currency is the same as the org receiving the funds, so no conversion necessary
      crmDonation.amount = stripeCharge.getAmount() / 100.0;
    } else {
      crmDonation.currencyConverted = true;
      // currency is different than what the org is expecting, so assume it was converted
      // TODO: this implies the values will *not* be set for failed transactions!
      if (stripeBalanceTransaction.isPresent()) {
        crmDonation.amount = stripeBalanceTransaction.get().getAmount() / 100.0;
        crmDonation.exchangeRate = stripeBalanceTransaction.get().getExchangeRate().doubleValue();
      }
    }

    // Always do this last! We need all the metadata context to fill out the customer details.
    initStripeCustomer(stripeCustomer, Optional.ofNullable(stripeCharge.getBillingDetails()));
  }

  public void initStripe(PaymentIntent stripePaymentIntent, Optional<Customer> stripeCustomer,
      Optional<Invoice> stripeInvoice, Optional<BalanceTransaction> stripeBalanceTransaction) {
    crmDonation.metadata.putAll(stripePaymentIntent.getMetadata());
    // some fundraising platforms put most of the donor metadata on the charge/subscription, so include them here
    crmAccount.metadata.putAll(stripePaymentIntent.getMetadata());
    crmContact.metadata.putAll(stripePaymentIntent.getMetadata());

    crmDonation.gatewayName = "Stripe";
    application = stripePaymentIntent.getApplication();
    String stripePaymentMethod = stripePaymentIntent.getCharges().getData().stream().findFirst().map(c -> c.getPaymentMethodDetails().getType()).orElse("");
    if (stripePaymentMethod.toLowerCase(Locale.ROOT).contains("ach")) {
      crmDonation.paymentMethod = "ACH";
    } else {
      crmDonation.paymentMethod = "Credit Card";
    }

    if (stripeInvoice.isPresent()) {
      if (stripeInvoice.get().getLines() != null) {
        crmDonation.products = stripeInvoice.get().getLines().getData().stream().map(
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
      crmDonation.closeDate = ZonedDateTime.ofInstant(Instant.ofEpochSecond(stripePaymentIntent.getCreated()), ZoneId.of("UTC"));
    } else {
      crmDonation.closeDate = ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("UTC"));
    }

    crmDonation.description = stripePaymentIntent.getDescription();
    crmDonation.transactionId = stripePaymentIntent.getId();
    crmDonation.secondaryId = stripePaymentIntent.getCharges().getData().stream().findFirst().map(Charge::getId).orElse(null);
    // note this is different than a charge, which uses !"failed" -- intents have multiple phases of "didn't work",
    // so explicitly search for succeeded
    crmDonation.status = "succeeded".equalsIgnoreCase(stripePaymentIntent.getStatus()) ? CrmDonation.Status.SUCCESSFUL : CrmDonation.Status.FAILED;
    crmDonation.url = "https://dashboard.stripe.com/payments/" + stripePaymentIntent.getId();

    crmDonation.originalAmountInDollars = stripePaymentIntent.getAmount() / 100.0;
    crmDonation.originalCurrency = stripePaymentIntent.getCurrency().toUpperCase(Locale.ROOT);

    stripeBalanceTransaction.ifPresent(bt -> {
      crmDonation.depositTransactionId = bt.getId();
      crmDonation.netAmountInDollars = bt.getNet() / 100.0;
      crmDonation.feeInDollars = bt.getFee() / 100.0;
    });

    if (envConfig.currency.equalsIgnoreCase(stripePaymentIntent.getCurrency().toUpperCase(Locale.ROOT))) {
      // currency is the same as the org receiving the funds, so no conversion necessary
      crmDonation.amount = stripePaymentIntent.getAmount() / 100.0;
    } else {
      crmDonation.currencyConverted = true;
      // currency is different than what the org is expecting, so assume it was converted
      // TODO: this implies the values will *not* be set for failed transactions!
      if (stripeBalanceTransaction.isPresent()) {
        crmDonation.amount = stripeBalanceTransaction.get().getAmount() / 100.0;
        BigDecimal exchangeRate = stripeBalanceTransaction.get().getExchangeRate();
        if (exchangeRate != null) {
          crmDonation.exchangeRate = exchangeRate.doubleValue();
        }
      }
    }

    // Always do this last! We need all the metadata context to fill out the customer details.
    initStripeCustomer(stripeCustomer, Optional.empty());
  }

  public void initStripe(Refund stripeRefund) {
    crmDonation.gatewayName = "Stripe";

    crmDonation.refundId = stripeRefund.getId();
    if (!Strings.isNullOrEmpty(stripeRefund.getPaymentIntent())) {
      crmDonation.transactionId = stripeRefund.getPaymentIntent();
      crmDonation.secondaryId = stripeRefund.getCharge();
    } else {
      crmDonation.transactionId = stripeRefund.getCharge();
    }

    if (stripeRefund.getCreated() != null) {
      crmDonation.refundDate = ZonedDateTime.ofInstant(Instant.ofEpochSecond(stripeRefund.getCreated()), ZoneId.of("UTC"));
    } else {
      crmDonation.refundDate = ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("UTC"));
    }
  }

  public void initStripe(Subscription stripeSubscription, Customer stripeCustomer) {
    crmDonation.gatewayName = "Stripe";

    initStripeSubscription(stripeSubscription, stripeCustomer);

    // Always do this last! We need all the metadata context to fill out the customer details.
    initStripeCustomer(Optional.of(stripeCustomer), Optional.empty());
  }

  protected void initStripeCustomer(Optional<Customer> __stripeCustomer, Optional<PaymentMethod.BillingDetails> billingDetails) {
    if (__stripeCustomer.isPresent()) {
      Customer stripeCustomer = __stripeCustomer.get();

      crmAccount.metadata.putAll(stripeCustomer.getMetadata());
      crmContact.metadata.putAll(stripeCustomer.getMetadata());

      crmDonation.customerId = stripeCustomer.getId();
      crmRecurringDonation.customerId = stripeCustomer.getId();

      crmContact.email = stripeCustomer.getEmail();
      crmContact.mobilePhone = stripeCustomer.getPhone();
    }

    // backfill with metadata if needed
    if (Strings.isNullOrEmpty(crmContact.email)) {
      crmContact.email = crmContact.metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return (key.contains("email"));
      }).findFirst().map(e -> (String) e.getValue()).orElse(null);
    }
    if (Strings.isNullOrEmpty(crmContact.mobilePhone)) {
      // TODO: Do we need to break this down into the different phone numbers?
      crmContact.mobilePhone = crmContact.metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return (key.contains("phone"));
      }).findFirst().map(e -> (String) e.getValue()).orElse(null);
    }

    initStripeCustomerName(__stripeCustomer, billingDetails);
    initStripeAddress(__stripeCustomer, billingDetails);
  }

  // What happens in this method seems ridiculous, but we're trying to resiliently deal with a variety of situations.
  // Some donation forms and vendors use true Customer names, others use metadata on Customer, other still only put
  // names in metadata on the Charge or Subscription. Madness. But let's be helpful...
  protected void initStripeCustomerName(Optional<Customer> stripeCustomer, Optional<PaymentMethod.BillingDetails> billingDetails) {
    // For the full name, start with Customer name. Generally this is populated, but a few vendors don't always do it.
    crmAccount.name = stripeCustomer.map(Customer::getName).orElse(null);
    // If that didn't work, look in the metadata. We've seen variations of "customer" or "full" name used.
    if (Strings.isNullOrEmpty(crmAccount.name)) {
      crmAccount.name = crmAccount.metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return (key.contains("customer") || key.contains("full")) && key.contains("name");
      }).findFirst().map(e -> (String) e.getValue()).orElse(null);
    }
    // If that still didn't work, look in the backup metadata (typically a charge or subscription).
    if (Strings.isNullOrEmpty(crmAccount.name)) {
      crmAccount.name = crmAccount.metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return (key.contains("customer") || key.contains("full")) && key.contains("name");
      }).findFirst().map(e -> (String) e.getValue()).orElse(null);
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
    crmContact.firstName = crmContact.metadata.entrySet().stream().filter(e -> {
      String key = e.getKey().toLowerCase(Locale.ROOT);
      return key.matches("(?i)first.*name");
    }).findFirst().map(e -> (String) e.getValue()).orElse(null);
    if (Strings.isNullOrEmpty(crmContact.firstName)) {
      crmContact.firstName = crmContact.metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.matches("(?i)first.*name");
      }).findFirst().map(e -> (String) e.getValue()).orElse(null);
    }

    // And now the last name.
    crmContact.lastName = crmContact.metadata.entrySet().stream().filter(e -> {
      String key = e.getKey().toLowerCase(Locale.ROOT);
      return key.matches("(?i)last.*name");
    }).findFirst().map(e -> (String) e.getValue()).orElse(null);
    if (Strings.isNullOrEmpty(crmContact.lastName)) {
      crmContact.lastName = crmContact.metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.matches("(?i)last.*name");
      }).findFirst().map(e -> (String) e.getValue()).orElse(null);
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
      // TODO: The stream and filter are getting repetitive (see initStripeCustomerName as well). DRY it up
      crmAddress.street = crmAccount.metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("street") || key.contains("address");
      }).findFirst().map(e -> (String) e.getValue()).orElse(null);
      crmAddress.city = crmAccount.metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("city");
      }).findFirst().map(e -> (String) e.getValue()).orElse(null);
      crmAddress.state = crmAccount.metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("state");
      }).findFirst().map(e -> (String) e.getValue()).orElse(null);
      crmAddress.postalCode = crmAccount.metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("postal") || key.contains("zip");
      }).findFirst().map(e -> (String) e.getValue()).orElse(null);
      crmAddress.country = crmAccount.metadata.entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("country");
      }).findFirst().map(e -> (String) e.getValue()).orElse(null);
    }

    crmAccount.billingAddress = crmAddress;
    crmContact.mailingAddress = crmAddress;
  }

  // Keep stripeCustomer, even though we don't use it here -- needed in subclasses.
  protected void initStripeSubscription(Subscription stripeSubscription, Customer stripeCustomer) {
    crmRecurringDonation.metadata.putAll(stripeSubscription.getMetadata());
    // some fundraising platforms put most of the donor metadata on the charge/subscription, so include them here
    crmAccount.metadata.putAll(stripeSubscription.getMetadata());
    crmContact.metadata.putAll(stripeSubscription.getMetadata());

    crmRecurringDonation.gatewayName = "Stripe";

    if (stripeSubscription.getTrialEnd() != null) {
      crmRecurringDonation.subscriptionStartDate = ZonedDateTime.ofInstant(Instant.ofEpochSecond(stripeSubscription.getTrialEnd()), ZoneId.of("UTC"));
    } else {
      crmRecurringDonation.subscriptionStartDate = ZonedDateTime.ofInstant(Instant.ofEpochSecond(stripeSubscription.getStartDate()), ZoneId.of("UTC"));
    }
    crmRecurringDonation.subscriptionNextDate = crmRecurringDonation.subscriptionStartDate;

    crmRecurringDonation.subscriptionId = stripeSubscription.getId();
    if (stripeSubscription.getPendingInvoiceItemInterval() != null) {
      crmRecurringDonation.frequency = CrmRecurringDonation.Frequency.fromName(stripeSubscription.getPendingInvoiceItemInterval().getInterval());
    }
    // by default, assume monthly
    if (crmRecurringDonation.frequency == null) crmRecurringDonation.frequency = CrmRecurringDonation.Frequency.MONTHLY;

    // Stripe is in cents
    // TODO: currency conversion support? This is eventually updated as charges are received, but for brand new ones
    // with a trial, this could throw off future forecasting!
    SubscriptionItem item = stripeSubscription.getItems().getData().get(0);
    crmRecurringDonation.amount = item.getPrice().getUnitAmountDecimal().doubleValue() * item.getQuantity() / 100.0;
    crmRecurringDonation.subscriptionCurrency = item.getPrice().getCurrency().toUpperCase(Locale.ROOT);

    // TODO: We could shift this to MetadataRetriever, but odds are we're the only ones setting it...
    crmRecurringDonation.description = stripeSubscription.getMetadata().get("description");
  }

  public Map<String, String> getAllMetadata() {
    // In order!
    return Stream.of(crmDonation.metadata, crmRecurringDonation.metadata, crmContact.metadata, crmAccount.metadata)
        .flatMap(map -> map.entrySet().stream())
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            Map.Entry::getValue,
            // If there's a duplicate key, always keep the first! We define the order of precedence, above.
            (v1, v2) -> v1
        ));
  }

  public String getMetadataValue(Collection<String> keys) {
    Collection<String> filteredKeys = keys.stream().filter(k -> !Strings.isNullOrEmpty(k)).toList();

    // In order!
    return Stream.of(crmDonation.metadata, crmRecurringDonation.metadata, crmContact.metadata, crmAccount.metadata)
        .flatMap(map -> map.entrySet().stream())
        .filter(e -> filteredKeys.contains(e.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  // TRANSIENT

  public CrmAccount getCrmAccount() {
    // If we don't yet have an ID, but event metadata has one defined, use that as a default
    if (Strings.isNullOrEmpty(crmAccount.id)) {
      crmAccount.id = getMetadataValue(envConfig.metadataKeys.account);
    }
    return crmAccount;
  }

  public void setCrmAccountId(String crmAccountId) {
    crmAccount.id = crmAccountId;
    crmContact.account.id = crmAccountId;
    crmDonation.account.id = crmAccountId;
    crmRecurringDonation.account.id = crmAccountId;
  }

  public void setCrmAccount(CrmAccount crmAccount) {
    this.crmAccount = crmAccount;
    crmContact.account = crmAccount;
    crmDonation.account = crmAccount;
    crmRecurringDonation.account = crmAccount;
  }

  public CrmContact getCrmContact() {
    // If we don't yet have an ID, but event metadata has one defined, use that as a default
    if (Strings.isNullOrEmpty(crmContact.id)) {
      crmContact.id = getMetadataValue(envConfig.metadataKeys.contact);
    }
    return crmContact;
  }

  public void setCrmContactId(String crmContactId) {
    crmContact.id = crmContactId;
    crmDonation.contact.id = crmContactId;
    crmRecurringDonation.contact.id = crmContactId;
  }

  public void setCrmContact(CrmContact crmContact) {
    this.crmContact = crmContact;
    crmDonation.contact = crmContact;
    crmRecurringDonation.contact = crmContact;
  }

  public CrmDonation getCrmDonation() {
    return crmDonation;
  }

  public void setCrmDonationId(String crmDonationId) {
    crmDonation.id = crmDonationId;
  }

  public void setCrmDonation(CrmDonation crmDonation) {
    this.crmDonation = crmDonation;
  }

  public CrmRecurringDonation getCrmRecurringDonation() {
    return crmRecurringDonation;
  }

  public void setCrmRecurringDonationId(String crmRecurringDonationId) {
    crmRecurringDonation.id = crmRecurringDonationId;
    crmDonation.recurringDonation.id = crmRecurringDonationId;
  }

  public void setCrmRecurringDonationId(CrmRecurringDonation crmRecurringDonation) {
    this.crmRecurringDonation = crmRecurringDonation;
    crmDonation.recurringDonation = crmRecurringDonation;
  }

  public boolean isTransactionRecurring() {
    return !Strings.isNullOrEmpty(crmRecurringDonation.subscriptionId);
  }

  // GETTERS/SETTERS
  // Note that we allow setters here, as orgs sometimes need to override the values based on custom logic.

  public String getApplication() {
    return application;
  }

  public void setApplication(String application) {
    this.application = application;
  }

  public List<PaymentGatewayEvent> getSecondaryEvents() {
    return secondaryEvents;
  }

  public void setSecondaryEvents(List<PaymentGatewayEvent> secondaryEvents) {
    this.secondaryEvents = secondaryEvents;
  }

  // TODO: Auto generated, but then modified. Note that this is used for failure notifications sent to staff, etc.
  // We might be better off breaking this out into a separate, dedicated method.
  // TODO: redo
//  @Override
//  public String toString() {
//    return "PaymentGatewayEvent{" +
//
//        "fullName='" + crmAccount.name + '\'' +
//        ", firstName='" + crmContact.firstName + '\'' +
//        ", lastName='" + crmContact.lastName + '\'' +
//        ", email='" + crmContact.email + '\'' +
//        ", mobilePhone='" + crmContact.mobilePhone + '\'' +
//
//        ", street='" + crmContact.address.street + '\'' +
//        ", city='" + crmContact.address.city + '\'' +
//        ", state='" + crmContact.address.state + '\'' +
//        ", zip='" + crmContact.address.postalCode + '\'' +
//        ", country='" + crmContact.address.country + '\'' +
//
//        ", gatewayName='" + gatewayName + '\'' +
//        ", paymentMethod='" + paymentMethod + '\'' +
//
//        ", customerId='" + customerId + '\'' +
//        ", transactionId='" + transactionId + '\'' +
//        ", transactionSecondaryId='" + transactionSecondaryId + '\'' +
//        ", subscriptionId='" + subscriptionId + '\'' +
//
//        ", products='" + String.join(",", products) + '\'' +
//
//        ", transactionDate=" + transactionDate +
//        ", transactionSuccess=" + transactionSuccess +
//        ", transactionDescription='" + transactionDescription + '\'' +
//        ", transactionAmountInDollars=" + transactionAmountInDollars +
//        ", transactionNetAmountInDollars=" + transactionNetAmountInDollars +
//        ", transactionExchangeRate=" + transactionExchangeRate +
//        ", transactionFeeInDollars=" + transactionFeeInDollars +
//        ", transactionOriginalAmountInDollars=" + transactionOriginalAmountInDollars +
//        ", transactionOriginalCurrency='" + transactionOriginalCurrency + '\'' +
//        ", transactionCurrencyConverted='" + transactionCurrencyConverted + '\'' +
//        ", transactionUrl=" + transactionUrl +
//
//        ", subscriptionAmountInDollars=" + subscriptionAmountInDollars +
//        ", subscriptionCurrency='" + subscriptionCurrency + '\'' +
//        ", subscriptionDescription='" + subscriptionDescription + '\'' +
//        ", subscriptionInterval='" + subscriptionInterval + '\'' +
//        ", subscriptionStartDate=" + subscriptionStartDate +
//        ", subscriptionNextDate=" + subscriptionNextDate +
//
//        ", primaryCrmAccountId='" + crmAccount.id + '\'' +
//        ", primaryCrmContactId='" + crmContact.id + '\'' +
//        ", primaryCrmRecurringDonationId='" + crmRecurringDonationId +
//        '}';
//  }
}
