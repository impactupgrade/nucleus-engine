package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.MetadataRetriever;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.Environment.RequestEnvironment;
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
  protected final RequestEnvironment requestEnv;

  // determined by event
  protected String campaignId;
  protected String city;
  protected String country;
  protected String customerId;
  protected String depositTransactionId;
  protected String email;
  protected String firstName;
  protected String fullName;
  protected String gatewayName;
  protected String lastName;
  protected String paymentMethod;
  protected String phone;
  protected String refundId;
  protected String state;
  protected String street;
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
  protected boolean transactionSuccess;
  protected String zip;

  // context set within processing steps OR pulled from event metadata
  protected String primaryCrmAccountId;
  protected String primaryCrmContactId;
  protected String primaryCrmRecordTypeId;
  protected String primaryCrmRecurringDonationId;
  protected String depositId;
  protected Calendar depositDate;

  public PaymentGatewayWebhookEvent(Environment env, RequestEnvironment requestEnv) {
    this.env = env;
    this.requestEnv = requestEnv;
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
    if (requestEnv.currency().equalsIgnoreCase(stripeCharge.getCurrency().toUpperCase(Locale.ROOT))) {
      // currency is the same as the org receiving the funds, so no conversion necessary
      transactionAmountInDollars = stripeCharge.getAmount() / 100.0;
    } else {
      // currency is different than what the org is expecting, so assume it was converted
      // TODO: this implies the values will *not* be set for failed transactions!
      if (stripeBalanceTransaction.isPresent()) {
        transactionAmountInDollars = stripeBalanceTransaction.get().getAmount() / 100.0;
        transactionExchangeRate = stripeBalanceTransaction.get().getExchangeRate().doubleValue();
      }
    }

    transactionDescription = stripeCharge.getDescription();

    MetadataRetriever metadataRetriever = new MetadataRetriever(requestEnv).stripeCharge(stripeCharge).stripeCustomer(stripeCustomer);
    processMetadata(metadataRetriever);
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
    if (requestEnv.currency().equalsIgnoreCase(stripePaymentIntent.getCurrency().toUpperCase(Locale.ROOT))) {
      // currency is the same as the org receiving the funds, so no conversion necessary
      transactionAmountInDollars = stripePaymentIntent.getAmount() / 100.0;
    } else {
      // currency is different than what the org is expecting, so assume it was converted
      // TODO: this implies the values will *not* be set for failed transactions!
      if (stripeBalanceTransaction.isPresent()) {
        transactionAmountInDollars = stripeBalanceTransaction.get().getAmount() / 100.0;
        transactionExchangeRate = stripeBalanceTransaction.get().getExchangeRate().doubleValue();
      }
    }

    transactionDescription = stripePaymentIntent.getDescription();

    MetadataRetriever metadataRetriever = new MetadataRetriever(requestEnv).stripePaymentIntent(stripePaymentIntent).stripeCustomer(stripeCustomer);
    processMetadata(metadataRetriever);
  }

  public void initStripe(Refund stripeRefund) {
    initStripeCommon();

    refundId = stripeRefund.getId();
    transactionId = stripeRefund.getCharge();
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

    email = stripeCustomer.getEmail();
    phone = stripeCustomer.getPhone();

    if (stripeCustomer.getAddress() != null) {
      city = stripeCustomer.getAddress().getCity();
      country = stripeCustomer.getAddress().getCountry();
      state = stripeCustomer.getAddress().getState();
      street = stripeCustomer.getAddress().getLine1();
      if (!Strings.isNullOrEmpty(stripeCustomer.getAddress().getLine2())) {
        street += ", " + stripeCustomer.getAddress().getLine2();
      }
      zip = stripeCustomer.getAddress().getPostalCode();
    } else {
      // use the first payment source, but don't use the default source, since we can't guarantee it's set as a card
      // TODO: This will need rethought after Donor Portal is launched and Stripe is used for ACH!
      stripeCustomer.getSources().getData().stream()
          .filter(s -> s instanceof Card)
          .map(s -> (Card) s)
          .findFirst()
          .ifPresent(stripeCard -> {
            city = stripeCard.getAddressCity();
            country = stripeCard.getAddressCountry();
            state = stripeCard.getAddressState();
            street = stripeCard.getAddressLine1();
            if (!Strings.isNullOrEmpty(stripeCard.getAddressLine2())) {
              street += ", " + stripeCard.getAddressLine2();
            }
            zip = stripeCard.getAddressZip();
          });
    }
  }

  // What happens in this method seems ridiculous, but we're trying to resiliently deal with a variety of situations.
  // Some donation forms and vendors use true Customer names, others use metadata on Customer, other still only put
  // names in metadata on the Charge or Subscription. Madness. But let's be helpful...
  protected void initStripeCustomerName(Customer stripeCustomer, MetadataStore<?> stripeMetadataBackup) {
    // For the full name, start with Customer name. Generally this is populated, but a few vendors don't always do it.
    fullName = stripeCustomer.getName();
    // If that didn't work, look in the metadata. We've seen variations of "customer" or "full" name used.
    if (Strings.isNullOrEmpty(fullName)) {
      fullName = stripeCustomer.getMetadata().entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return (key.contains("customer") || key.contains("full")) && key.contains("name");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }
    // If that still didn't work, look in the backup metadata (typically a charge or subscription).
    if (Strings.isNullOrEmpty(fullName)) {
      fullName = stripeMetadataBackup.getMetadata().entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return (key.contains("customer") || key.contains("full")) && key.contains("name");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }

    // Now do first name, again using metadata.
    firstName = stripeCustomer.getMetadata().entrySet().stream().filter(e -> {
      String key = e.getKey().toLowerCase(Locale.ROOT);
      return key.contains("first") && key.contains("name");
    }).findFirst().map(Map.Entry::getValue).orElse(null);
    if (Strings.isNullOrEmpty(firstName)) {
      firstName = stripeMetadataBackup.getMetadata().entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("first") && key.contains("name");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }

    // And now the last name.
    lastName = stripeCustomer.getMetadata().entrySet().stream().filter(e -> {
      String key = e.getKey().toLowerCase(Locale.ROOT);
      return key.contains("last") && key.contains("name");
    }).findFirst().map(Map.Entry::getValue).orElse(null);
    if (Strings.isNullOrEmpty(lastName)) {
      lastName = stripeMetadataBackup.getMetadata().entrySet().stream().filter(e -> {
        String key = e.getKey().toLowerCase(Locale.ROOT);
        return key.contains("last") && key.contains("name");
      }).findFirst().map(Map.Entry::getValue).orElse(null);
    }

    // If we still don't have a first/last name, but do have full name, fall back to using a split.
    if (Strings.isNullOrEmpty(lastName) && !Strings.isNullOrEmpty(fullName)) {
      String[] split = Utils.fullNameToFirstLast(fullName);
      firstName = split[0];
      lastName = split[1];
    }

    // If we still don't have a full name, but do have a first and last, combine them.
    if (Strings.isNullOrEmpty(fullName) && !Strings.isNullOrEmpty(firstName) && !Strings.isNullOrEmpty(lastName)) {
      fullName = firstName + " " + lastName;
    }
  }

  // Keep stripeCustomer, even though we don't use it here -- needed in subclasses.
  protected void initStripeSubscription(Subscription stripeSubscription, Customer stripeCustomer) {
    if (stripeSubscription.getTrialEnd() != null) {
      subscriptionStartDate = Calendar.getInstance();
      subscriptionStartDate.setTimeInMillis(stripeSubscription.getTrialEnd() * 1000);
      subscriptionNextDate = Calendar.getInstance();
      subscriptionNextDate.setTimeInMillis(stripeSubscription.getTrialEnd() * 1000);
    } else {
      subscriptionStartDate = Calendar.getInstance();
      subscriptionNextDate = Calendar.getInstance();
    }

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

    MetadataRetriever metadataRetriever = new MetadataRetriever(requestEnv).stripeSubscription(stripeSubscription).stripeCustomer(stripeCustomer);
    processMetadata(metadataRetriever);

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

    city = paymentSpringTransaction.getCity();
    country = paymentSpringTransaction.getCountry();
    state = paymentSpringTransaction.getState();
    street = paymentSpringTransaction.getAddress1();
    if (!Strings.isNullOrEmpty(paymentSpringTransaction.getAddress2())) {
      street += ", " + paymentSpringTransaction.getAddress2();
    }
    zip = paymentSpringTransaction.getZip();

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

    email = paymentSpringTransaction.getEmail();
    firstName = paymentSpringTransaction.getFirstName();
    lastName = paymentSpringTransaction.getLastName();
    phone = paymentSpringTransaction.getPhone();

    if (paymentSpringCustomer.isPresent()) {
      customerId = paymentSpringCustomer.get().getId();

      if (Strings.isNullOrEmpty(email)) {
        email = paymentSpringCustomer.get().getEmail();
      }
      if (Strings.isNullOrEmpty(firstName)) {
        firstName = paymentSpringCustomer.get().getFirstName();
      }
      if (Strings.isNullOrEmpty(lastName)) {
        lastName = paymentSpringCustomer.get().getLastName();
      }
      if (Strings.isNullOrEmpty(phone)) {
        phone = paymentSpringCustomer.get().getPhone();
      }
    }

    // As an extra "Why not?", PS sometimes leaves off the name from everything but the Card Owner/Account Houlder.
    if (Strings.isNullOrEmpty(firstName) && Strings.isNullOrEmpty(lastName)) {
      String[] split = new String[0];
      if (!Strings.isNullOrEmpty(paymentSpringTransaction.getCardOwnerName())) {
        split = paymentSpringTransaction.getCardOwnerName().split(" ");
      } else if (!Strings.isNullOrEmpty(paymentSpringTransaction.getAccountHolderName())) {
        split = paymentSpringTransaction.getAccountHolderName().split(" ");
      }
      if (split.length == 2) {
        firstName = split[0];
        lastName = split[1];
      }
    }

    fullName = firstName + " " + lastName;

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

  private void processMetadata(MetadataRetriever metadataRetriever) {
    String accountId = metadataRetriever.getMetadataValue(env.config().metadataKeys.account);
    String campaignId = metadataRetriever.getMetadataValue(env.config().metadataKeys.campaign);
    String contactId = metadataRetriever.getMetadataValue(env.config().metadataKeys.contact);
    String recordTypeId = metadataRetriever.getMetadataValue(env.config().metadataKeys.recordType);

    // Only set the values if the new retrieval was not empty! This allows us to define fallbacks...
    if (!Strings.isNullOrEmpty(accountId)) {
      this.primaryCrmAccountId = accountId;
    }
    if (!Strings.isNullOrEmpty(campaignId)) {
      this.campaignId = campaignId;
    }
    if (!Strings.isNullOrEmpty(contactId)) {
      this.primaryCrmContactId = contactId;
    }
    if (!Strings.isNullOrEmpty(recordTypeId)) {
      this.primaryCrmRecordTypeId = recordTypeId;
    }
  }
  
  // CONTEXT SET WITHIN PROCESSING STEPS *OR* FROM EVENT METADATA

  public String getPrimaryCrmAccountId() {
    return primaryCrmAccountId;
  }

  public void setPrimaryCrmAccountId(String primaryCrmAccountId) {
    this.primaryCrmAccountId = primaryCrmAccountId;
  }

  public String getPrimaryCrmContactId() {
    return primaryCrmContactId;
  }

  public void setPrimaryCrmContactId(String primaryCrmContactId) {
    this.primaryCrmContactId = primaryCrmContactId;
  }

  public String getPrimaryCrmRecurringDonationId() {
    return primaryCrmRecurringDonationId;
  }

  public void setPrimaryCrmRecurringDonationId(String primaryCrmRecurringDonationId) {
    this.primaryCrmRecurringDonationId = primaryCrmRecurringDonationId;
  }

  public String getPrimaryCrmRecordTypeId() {
    return primaryCrmRecordTypeId;
  }

  public void setPrimaryCrmRecordTypeId(String primaryCrmRecordTypeId) {
    this.primaryCrmRecordTypeId = primaryCrmRecordTypeId;
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

  // ACCESSORS

  public RequestEnvironment getRequestEnv() {
    return requestEnv;
  }

  public String getCampaignId() {
    return campaignId;
  }

  public String getCity() {
    return city;
  }

  public String getCountry() {
    return country;
  }

  public String getCustomerId() {
    return customerId;
  }

  public String getDepositTransactionId() {
    return depositTransactionId;
  }

  public String getEmail() {
    return email;
  }

  public String getFirstName() {
    return firstName;
  }

  public String getFullName() {
    return fullName;
  }

  public String getGatewayName() {
    return gatewayName;
  }

  public String getLastName() {
    return lastName;
  }

  public String getPaymentMethod() {
    return paymentMethod;
  }

  public String getPhone() {
    return phone;
  }

  public String getRefundId() {
    return refundId;
  }

  public String getState() {
    return state;
  }

  public String getStreet() {
    return street;
  }

  public Double getSubscriptionAmountInDollars() {
    return subscriptionAmountInDollars;
  }

  public String getSubscriptionCurrency() {
    return subscriptionCurrency;
  }

  public String getSubscriptionDescription() {
    return subscriptionDescription;
  }

  public String getSubscriptionId() {
    return subscriptionId;
  }

  public String getSubscriptionInterval() {
    return subscriptionInterval;
  }

  public Calendar getSubscriptionNextDate() {
    return subscriptionNextDate;
  }

  public Calendar getSubscriptionStartDate() {
    return subscriptionStartDate;
  }

  public Double getTransactionAmountInDollars() {
    return transactionAmountInDollars;
  }

  public Double getTransactionNetAmountInDollars() {
    return transactionNetAmountInDollars;
  }

  public Calendar getTransactionDate() {
    return transactionDate;
  }

  public String getTransactionDescription() {
    return transactionDescription;
  }

  public Double getTransactionExchangeRate() {
    return transactionExchangeRate;
  }

  public String getTransactionId() {
    return transactionId;
  }

  public Double getTransactionOriginalAmountInDollars() {
    return transactionOriginalAmountInDollars;
  }

  public String getTransactionOriginalCurrency() {
    return transactionOriginalCurrency;
  }

  public boolean isTransactionSuccess() {
    return transactionSuccess;
  }

  public String getZip() {
    return zip;
  }

  // SETTERS

  // Mainly needed by DR to set a default country based on FN when missing.
  public void setCountry(String country) {
    this.country = country;
  }

  // TODO: Auto generated, but then modified. Note that this is used for failure notifactions sent to staff, etc.
  // We might be better off breaking this out into a separate, dedicated method.
  @Override
  public String toString() {
    return "PaymentGatewayEvent{" +

        ", firstName='" + firstName + '\'' +
        ", lastName='" + lastName + '\'' +
        ", fullName='" + fullName + '\'' +
        ", email='" + email + '\'' +
        ", phone='" + phone + '\'' +

        ", street='" + street + '\'' +
        ", city='" + city + '\'' +
        ", state='" + state + '\'' +
        ", zip='" + zip + '\'' +
        ", country='" + country + '\'' +

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

        ", subscriptionAmountInDollars=" + subscriptionAmountInDollars +
        ", subscriptionCurrency='" + subscriptionCurrency + '\'' +
        ", subscriptionDescription='" + subscriptionDescription + '\'' +
        ", subscriptionInterval='" + subscriptionInterval + '\'' +
        ", subscriptionStartDate=" + subscriptionStartDate +
        ", subscriptionNextDate=" + subscriptionNextDate +

        ", primaryCrmAccountId='" + primaryCrmAccountId + '\'' +
        ", primaryCrmContactId='" + primaryCrmContactId + '\'' +
        ", primaryCrmRecordTypeId='" + primaryCrmRecordTypeId + '\'' +
        ", primaryCrmRecurringDonationId='" + primaryCrmRecurringDonationId +
        '}';
  }
}
