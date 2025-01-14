/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
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
import com.stripe.model.Dispute;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import org.apache.commons.collections4.map.CaseInsensitiveMap;

import java.io.Serializable;
import java.math.BigDecimal;
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
  protected PaymentGatewayEventType eventType;

  public PaymentGatewayEvent(Environment env) {
    envConfig = env.getConfig();

    crmContact.account = crmAccount;
    crmDonation.account = crmAccount;
    crmDonation.contact = crmContact;
    crmDonation.recurringDonation = crmRecurringDonation;
    crmRecurringDonation.account = crmAccount;
    crmRecurringDonation.contact = crmContact;
  }

  // IMPORTANT! We remove all non-numeric chars on all metadata fields -- it appears a few campaign IDs were pasted
  // into forms and contain NBSP characters :(

  public void initStripe(Charge stripeCharge, Optional<Customer> stripeCustomer,
      Optional<Invoice> stripeInvoice, Optional<BalanceTransaction> stripeBalanceTransaction) {
    crmDonation.metadata.putAll(stripeCharge.getMetadata());
    // for fundraising platforms that do not use Subscriptions (like FRU), the recurring donation metadata is on the charge
    crmRecurringDonation.metadata.putAll(stripeCharge.getMetadata());
    // some fundraising platforms put most of the donor metadata on the charge/subscription, so include them here
    crmAccount.metadata.putAll(stripeCharge.getMetadata());
    crmContact.metadata.putAll(stripeCharge.getMetadata());

    crmDonation.gatewayName = "Stripe";
    crmDonation.application = stripeCharge.getApplication();
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

    if (stripeBalanceTransaction.isPresent() && stripeBalanceTransaction.get().getCreated() != null) {
      crmDonation.closeDate = Utils.toZonedDateTime(stripeBalanceTransaction.get().getCreated(), "UTC");
    } else if (stripeCharge.getCreated() != null) {
      crmDonation.closeDate = Utils.toZonedDateTime(stripeCharge.getCreated(), "UTC");
    } else {
      crmDonation.closeDate = Utils.now("UTC");
    }

    crmDonation.description = stripeCharge.getDescription();
    crmDonation.transactionId = stripeCharge.getId();

    if ("failed".equalsIgnoreCase(stripeCharge.getStatus())) {
      crmDonation.status = CrmDonation.Status.FAILED;
      crmDonation.failureReason = stripeCharge.getFailureMessage();
    } else {
      crmDonation.status = CrmDonation.Status.SUCCESSFUL;
    }
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
    initStripeCustomer(stripeCustomer, Optional.ofNullable(stripeCharge.getBillingDetails()), stripeCharge.getReceiptEmail());
  }

  public void initStripe(PaymentIntent stripePaymentIntent, Optional<Customer> stripeCustomer,
      Optional<Invoice> stripeInvoice, Optional<BalanceTransaction> stripeBalanceTransaction) {
    crmDonation.metadata.putAll(stripePaymentIntent.getMetadata());
    // for fundraising platforms that do not use Subscriptions (like FRU), the recurring donation metadata is on the charge
    crmRecurringDonation.metadata.putAll(stripePaymentIntent.getMetadata());
    // some fundraising platforms put most of the donor metadata on the charge/subscription, so include them here
    crmAccount.metadata.putAll(stripePaymentIntent.getMetadata());
    crmContact.metadata.putAll(stripePaymentIntent.getMetadata());

    crmDonation.gatewayName = "Stripe";
    crmDonation.application = stripePaymentIntent.getApplication();
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

    if (stripeBalanceTransaction.isPresent() && stripeBalanceTransaction.get().getCreated() != null) {
      crmDonation.closeDate = Utils.toZonedDateTime(stripeBalanceTransaction.get().getCreated(), "UTC");
    } else if (stripePaymentIntent.getCreated() != null) {
      crmDonation.closeDate = Utils.toZonedDateTime(stripePaymentIntent.getCreated(), "UTC");
    } else {
      crmDonation.closeDate = Utils.now("UTC");
    }

    crmDonation.description = stripePaymentIntent.getDescription();
    crmDonation.transactionId = stripePaymentIntent.getId();
    crmDonation.secondaryId = stripePaymentIntent.getCharges().getData().stream().findFirst().map(Charge::getId).orElse(null);
    // note this is different than a charge, which uses !"failed" -- intents have multiple phases of "didn't work",
    // so explicitly search for succeeded
    if ("succeeded".equalsIgnoreCase(stripePaymentIntent.getStatus())) {
      crmDonation.status = CrmDonation.Status.SUCCESSFUL;
    } else {
      crmDonation.status = CrmDonation.Status.FAILED;
      if (stripePaymentIntent.getLastPaymentError() != null) {
        crmDonation.failureReason = stripePaymentIntent.getLastPaymentError().getMessage();
      }
    }
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
    Optional<PaymentMethod.BillingDetails> billingDetails = stripePaymentIntent.getCharges().getData().stream()
        .findFirst().map(Charge::getBillingDetails);
    initStripeCustomer(stripeCustomer, billingDetails, stripePaymentIntent.getReceiptEmail());
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
      crmDonation.refundDate = Utils.toZonedDateTime(stripeRefund.getCreated(), "UTC");
    } else {
      crmDonation.refundDate = Utils.now("UTC");
    }
  }

  public void initStripe(Dispute stripeRefund) {
    crmDonation.gatewayName = "Stripe";

    crmDonation.refundId = stripeRefund.getId();
    if (!Strings.isNullOrEmpty(stripeRefund.getPaymentIntent())) {
      crmDonation.transactionId = stripeRefund.getPaymentIntent();
      crmDonation.secondaryId = stripeRefund.getCharge();
    } else {
      crmDonation.transactionId = stripeRefund.getCharge();
    }

    if (stripeRefund.getCreated() != null) {
      crmDonation.refundDate = Utils.toZonedDateTime(stripeRefund.getCreated(), "UTC");
    } else {
      crmDonation.refundDate = Utils.now("UTC");
    }
  }

  public void initStripe(Subscription stripeSubscription, Customer stripeCustomer) {
    crmDonation.gatewayName = "Stripe";

    initStripeSubscription(stripeSubscription, stripeCustomer);

    // Always do this last! We need all the metadata context to fill out the customer details.
    initStripeCustomer(Optional.of(stripeCustomer), Optional.empty(), null);
  }

  protected void initStripeCustomer(Optional<Customer> __stripeCustomer,
      Optional<PaymentMethod.BillingDetails> __billingDetails, String receiptEmail) {
    if (__stripeCustomer.isPresent()) {
      Customer stripeCustomer = __stripeCustomer.get();

      crmAccount.metadata.putAll(stripeCustomer.getMetadata());
      crmContact.metadata.putAll(stripeCustomer.getMetadata());

      crmDonation.customerId = stripeCustomer.getId();
      crmRecurringDonation.customerId = stripeCustomer.getId();

      crmContact.email = stripeCustomer.getEmail();
      crmContact.mobilePhone = stripeCustomer.getPhone();
    } else if (__billingDetails.isPresent() && !Strings.isNullOrEmpty(__billingDetails.get().getEmail())) {
      PaymentMethod.BillingDetails billingDetails = __billingDetails.get();
      crmContact.email = billingDetails.getEmail();
      crmContact.mobilePhone = billingDetails.getPhone();
    } else {
      crmContact.email = receiptEmail;
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

    initStripeCustomerName(__stripeCustomer, __billingDetails);
    initStripeAddress(__stripeCustomer, __billingDetails);
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
    // pick up, as an example, other tools' use of something like  fundraiser_first_name. Instead, use regex that's a
    // little more explicit.
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
    crmDonation.metadata.putAll(stripeSubscription.getMetadata());

    crmRecurringDonation.gatewayName = "Stripe";

    if (stripeSubscription.getTrialEnd() != null) {
      crmRecurringDonation.subscriptionStartDate = Utils.toZonedDateTime(stripeSubscription.getTrialEnd(), "UTC");
    } else {
      crmRecurringDonation.subscriptionStartDate = Utils.toZonedDateTime(stripeSubscription.getStartDate(), "UTC");
    }
    crmRecurringDonation.subscriptionNextDate = crmRecurringDonation.subscriptionStartDate;

    crmRecurringDonation.subscriptionId = stripeSubscription.getId();
    if (stripeSubscription.getItems().getData().get(0).getPlan().getInterval() != null) {
      crmRecurringDonation.frequency = CrmRecurringDonation.Frequency.fromName(stripeSubscription.getItems().getData().get(0).getPlan().getInterval());
    }
    // by default, assume monthly
    //TODO: potentially redundant/unused? crmRecurringDonation is initialized with frequency being monthly
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
    Map<String, String> metadata = new CaseInsensitiveMap<>();
    // order matters -- let the donations overwrite the customer defaults
    metadata.putAll(crmAccount.metadata);
    metadata.putAll(crmContact.metadata);
    metadata.putAll(crmRecurringDonation.metadata);
    metadata.putAll(crmDonation.metadata);
    return metadata;
  }

  public String getMetadataValue(String key) {
    return getMetadataValue(List.of(key));
  }

  public String getMetadataValue(Collection<String> keys) {
    Collection<String> filteredKeys = keys.stream().filter(k -> !Strings.isNullOrEmpty(k))
        .map(k -> k.toLowerCase(Locale.ROOT)).toList();

    // In order!
    return Stream.of(crmDonation.metadata, crmRecurringDonation.metadata, crmContact.metadata, crmAccount.metadata)
        .flatMap(map -> map.entrySet().stream())
        .filter(e -> filteredKeys.contains(e.getKey().toLowerCase(Locale.ROOT)))
        .map(Map.Entry::getValue)
        .findFirst()
        .map(String::trim)
        .orElse(null);
  }

  // TRANSIENT

  public CrmAccount getCrmAccount() {
    // If we don't yet have an ID, but event metadata has one defined, use that as a default
    if (Strings.isNullOrEmpty(crmAccount.id)) {
      setCrmAccountId(getMetadataValue(envConfig.metadataKeys.account));
    }
    return crmAccount;
  }

  public void setCrmAccountId(String crmAccountId) {
    crmAccount.id = crmAccountId;
    crmContact.account.id = crmAccountId;
    crmDonation.account.id = crmAccountId;
    crmRecurringDonation.account.id = crmAccountId;
  }

  public CrmContact getCrmContact() {
    // If we don't yet have an ID, but event metadata has one defined, use that as a default
    if (Strings.isNullOrEmpty(crmContact.id)) {
      setCrmContactId(getMetadataValue(envConfig.metadataKeys.contact));
    }
    return crmContact;
  }

  public void setCrmContactId(String crmContactId) {
    crmContact.id = crmContactId;
    crmDonation.contact.id = crmContactId;
    crmRecurringDonation.contact.id = crmContactId;
  }

  public CrmDonation getCrmDonation() {
    return crmDonation;
  }

  public void setCrmDonationId(String crmDonationId) {
    crmDonation.id = crmDonationId;
  }

  public CrmRecurringDonation getCrmRecurringDonation() {
    return crmRecurringDonation;
  }

  public void setCrmRecurringDonationId(String crmRecurringDonationId) {
    crmRecurringDonation.id = crmRecurringDonationId;
    crmDonation.recurringDonation.id = crmRecurringDonationId;
  }

  public PaymentGatewayEventType getPaymentGatewayEventType() {
    return eventType;
  }

  public void setPaymentGatewayEventType(PaymentGatewayEventType type){
    eventType = type;
  }

  @Override
  public String toString() {
    return "PaymentGatewayEvent{" +

        "fullName='" + crmAccount.name + '\'' +
        ", firstName='" + crmContact.firstName + '\'' +
        ", lastName='" + crmContact.lastName + '\'' +
        ", email='" + crmContact.email + '\'' +
        ", mobilePhone='" + crmContact.mobilePhone + '\'' +

        ", street='" + crmAccount.billingAddress.street + '\'' +
        ", city='" + crmAccount.billingAddress.city + '\'' +
        ", state='" + crmAccount.billingAddress.state + '\'' +
        ", zip='" + crmAccount.billingAddress.postalCode + '\'' +
        ", country='" + crmAccount.billingAddress.country + '\'' +

        ", gatewayName='" + crmDonation.gatewayName + '\'' +
        ", paymentMethod='" + crmDonation.paymentMethod + '\'' +

        ", customerId='" + crmDonation.customerId + '\'' +
        ", transactionId='" + crmDonation.transactionId + '\'' +
        ", transactionSecondaryId='" + crmDonation.secondaryId + '\'' +
        ", subscriptionId='" + crmRecurringDonation.subscriptionId + '\'' +

        ", products='" + String.join(",", crmDonation.products) + '\'' +

        ", transactionDate=" + crmDonation.closeDate +
        ", transactionSuccess=" + crmDonation.status +
        ", transactionDescription='" + crmDonation.description + '\'' +
        ", transactionAmountInDollars=" + crmDonation.amount +
        ", transactionNetAmountInDollars=" + crmDonation.netAmountInDollars +
        ", transactionExchangeRate=" + crmDonation.exchangeRate +
        ", transactionFeeInDollars=" + crmDonation.feeInDollars +
        ", transactionOriginalAmountInDollars=" + crmDonation.originalAmountInDollars +
        ", transactionOriginalCurrency='" + crmDonation.originalCurrency + '\'' +
        ", transactionCurrencyConverted='" + crmDonation.currencyConverted + '\'' +
        ", transactionUrl=" + crmDonation.url +

        ", subscriptionAmountInDollars=" + crmRecurringDonation.amount +
        ", subscriptionCurrency='" + crmRecurringDonation.subscriptionCurrency + '\'' +
        ", subscriptionDescription='" + crmRecurringDonation.description + '\'' +
        ", subscriptionInterval='" + crmRecurringDonation.frequency + '\'' +
        ", subscriptionStartDate=" + crmRecurringDonation.subscriptionStartDate +
        ", subscriptionNextDate=" + crmRecurringDonation.subscriptionNextDate +

        ", crmAccountId='" + crmAccount.id + '\'' +
        ", crmContactId='" + crmContact.id + '\'' +
        ", crmRecurringDonationId='" + crmRecurringDonation.id +
        '}';
  }
}
