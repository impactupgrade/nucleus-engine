package com.impactupgrade.common.paymentgateway.model;

import com.google.common.base.Strings;
import com.impactupgrade.common.environment.CampaignRetriever;
import com.impactupgrade.common.environment.Environment;
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
import java.util.Date;
import java.util.Locale;
import java.util.Optional;

public class PaymentGatewayEvent {
  
  protected final Environment env;
  protected final CampaignRetriever campaignRetriever;

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

  // context set within processing steps
  protected String primaryCrmAccountId;
  protected String primaryCrmContactId;
  protected String primaryCrmRecurringDonationId;
  protected String depositId;
  protected Calendar depositDate;

  public PaymentGatewayEvent(Environment env) {
    this.env = env;
    campaignRetriever = new CampaignRetriever(env);
  }

  // IMPORTANT! We're remove all non-numeric chars on all metadata fields -- it appears a few campaign IDs were pasted
  // into forms and contain NBSP characters :(

  public void initStripe(Charge stripeCharge, Customer stripeCustomer,
      Optional<Invoice> stripeInvoice, Optional<BalanceTransaction> stripeBalanceTransaction) {
    initStripeCommon();
    initStripeCustomer(stripeCustomer);

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
    if (env.defaultCurrency().equalsIgnoreCase(stripeCharge.getCurrency())) {
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

    campaignId = campaignRetriever.stripeCharge(stripeCharge).stripeCustomer(stripeCustomer).getCampaign();
  }

  public void initStripe(PaymentIntent stripePaymentIntent, Customer stripeCustomer,
      Optional<Invoice> stripeInvoice, Optional<BalanceTransaction> stripeBalanceTransaction) {
    initStripeCommon();
    initStripeCustomer(stripeCustomer);

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
    transactionSuccess = !"failed".equalsIgnoreCase(stripePaymentIntent.getStatus());

    transactionOriginalAmountInDollars = stripePaymentIntent.getAmount() / 100.0;
    stripeBalanceTransaction.ifPresent(t -> transactionNetAmountInDollars = t.getNet() / 100.0);
    transactionOriginalCurrency = stripePaymentIntent.getCurrency().toUpperCase(Locale.ROOT);
    if (env.defaultCurrency().equalsIgnoreCase(stripePaymentIntent.getCurrency())) {
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

    campaignId = campaignRetriever.stripePaymentIntent(stripePaymentIntent).stripeCustomer(stripeCustomer).getCampaign();
  }

  public void initStripe(Refund stripeRefund) {
    initStripeCommon();

    refundId = stripeRefund.getId();
    transactionId = stripeRefund.getCharge();
  }

  public void initStripe(Subscription stripeSubscription, Customer stripeCustomer) {
    initStripeCommon();
    initStripeCustomer(stripeCustomer);

    initStripeSubscription(stripeSubscription, stripeCustomer);
  }

  protected void initStripeCommon() {
    gatewayName = "Stripe";
    // TODO: expand to include ACH through Plaid?
    paymentMethod = "credit card";
  }

  protected void initStripeCustomer(Customer stripeCustomer) {
    customerId = stripeCustomer.getId();

    // TODO: These metadata fields might be LJI specific, but try them anyway. Move to Environment?
    fullName = stripeCustomer.getMetadata().get("customer_name");
    firstName = stripeCustomer.getMetadata().get("first_name");
    lastName = stripeCustomer.getMetadata().get("last_name");
    if (Strings.isNullOrEmpty(fullName)) {
      fullName = stripeCustomer.getName();
    }
    if (Strings.isNullOrEmpty(lastName) && !Strings.isNullOrEmpty(fullName)) {
      String[] split = fullName.split(" ");
      firstName = split[0];
      lastName = split[1];
    }

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

    campaignId = campaignRetriever.stripeSubscription(stripeSubscription).stripeCustomer(stripeCustomer).getCampaign();
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
      campaignId = paymentSpringCustomer.get().getMetadata().get("sf_campaign_id").replaceAll("[^A-Za-z0-9]", "");
      // some appear to be using "campaign", so try that too (SMH)
      if (Strings.isNullOrEmpty(campaignId)) {
        campaignId = paymentSpringCustomer.get().getMetadata().get("campaign").replaceAll("[^A-Za-z0-9]", "");
      }
    }
    if (Strings.isNullOrEmpty(campaignId)) {
      campaignId = paymentSpringTransaction.getMetadata().get("sf_campaign_id").replaceAll("[^A-Za-z0-9]", "");
      // some appear to be using "campaign", so try that too (SMH)
      if (Strings.isNullOrEmpty(campaignId)) {
        campaignId = paymentSpringTransaction.getMetadata().get("campaign").replaceAll("[^A-Za-z0-9]", "");
      }
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
  
  // CONTEXT SET WITHIN PROCESSING STEPS

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
}
