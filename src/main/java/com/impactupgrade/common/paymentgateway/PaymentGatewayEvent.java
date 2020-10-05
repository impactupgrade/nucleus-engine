package com.impactupgrade.common.paymentgateway;

import com.google.common.base.Strings;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Card;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.Refund;
import com.stripe.model.Subscription;

import java.util.Calendar;
import java.util.Date;
import java.util.Optional;

public class PaymentGatewayEvent {

  protected String city;
  protected String country;
  protected String depositTransactionId;
  protected String email;
  protected String firstName;
  protected String fullName;
  protected String gatewayName;
  protected String lastName;
  protected String paymentMethod;
  protected String phone;
  protected String state;
  protected String street;
  protected Double subscriptionAmountInDollars;
  protected String subscriptionCurrency;
  protected String subscriptionId;
  protected String subscriptionInterval;
  protected Calendar subscriptionNextDate;
  protected Calendar subscriptionStartDate;
  protected Double transactionAmountInDollars;
  protected Calendar transactionDate;
  protected String transactionDescription;
  protected Double transactionExchangeRate;
  protected String transactionId;
  protected Double transactionOriginalAmountInDollars;
  protected String transactionOriginalCurrency;
  protected boolean transactionSuccess;
  protected String zip;

  public void initStripe(Charge stripeCharge, Customer stripeCustomer,
      Optional<Invoice> stripeInvoice, Optional<BalanceTransaction> stripeBalanceTransaction) {
    initStripeCommon();
    initStripeCustomer(stripeCustomer);

    // NOTE: See the note on the StripeService's customer.subscription.created event handling. We insert recurring donations
    // from subscription creation ONLY if it's in a trial period and starts in the future. Otherwise, let the
    // first donation do it in order to prevent timing issues.
    if (stripeInvoice.isPresent() && !Strings.isNullOrEmpty(stripeInvoice.get().getSubscription())) {
      initStripeSubscription(stripeInvoice.get().getSubscriptionObject());
    }

    if (stripeCharge.getCreated() != null) {
      transactionDate = Calendar.getInstance();
      transactionDate.setTimeInMillis(stripeCharge.getCreated() * 1000);
    } else {
      transactionDate = Calendar.getInstance();
    }

    depositTransactionId = stripeCharge.getBalanceTransaction();
    transactionDescription = stripeCharge.getDescription();
    transactionId = stripeCharge.getId();
    transactionSuccess = !"failed".equalsIgnoreCase(stripeCharge.getStatus());

    if ("usd".equalsIgnoreCase(stripeCharge.getCurrency())) {
      // Stripe is in cents
      transactionAmountInDollars = stripeCharge.getAmount() / 100.0;
    } else {
      transactionOriginalAmountInDollars = stripeCharge.getAmount() / 100.0;
      transactionOriginalCurrency = stripeCharge.getCurrency();
      // TODO: this implies the values will *not* be set for failed transactions!
      if (stripeBalanceTransaction.isPresent()) {
        transactionAmountInDollars = stripeBalanceTransaction.get().getAmount() / 100.0;
        transactionExchangeRate = stripeBalanceTransaction.get().getExchangeRate().doubleValue();
      }
    }
  }

  public void initStripe(Refund stripeRefund) {
    initStripeCommon();

    transactionId = stripeRefund.getId();
  }

  public void initStripe(Subscription stripeSubscription, Customer stripeCustomer) {
    initStripeCommon();
    initStripeCustomer(stripeCustomer);

    initStripeSubscription(stripeSubscription);
  }

  protected void initStripeCommon() {
    gatewayName = "Stripe";
    // TODO: expand to include ACH through Plaid?
    paymentMethod = "credit card";
  }

  protected void initStripeCustomer(Customer stripeCustomer) {
    email = stripeCustomer.getEmail();
    // TODO: Are these metadata fields LJI specific?
    fullName = stripeCustomer.getMetadata().get("customer_name");
    firstName = stripeCustomer.getMetadata().get("first_name");
    lastName = stripeCustomer.getMetadata().get("last_name");
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

  protected void initStripeSubscription(Subscription stripeSubscription) {
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
    subscriptionAmountInDollars = stripeSubscription.getPlan().getAmount() / 100.0;
    subscriptionCurrency = stripeSubscription.getPlan().getCurrency();
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

  private Calendar getTransactionDate(Date date) {
    if (date != null) {
      Calendar calendar = Calendar.getInstance();
      calendar.setTime(date);
      return calendar;
    } else {
      return Calendar.getInstance();
    }
  }

  // TRANSIENT

  public boolean isTransactionRecurring() {
    return !Strings.isNullOrEmpty(subscriptionId);
  }

  // ACCESSORS

  public String getCity() {
    return city;
  }

  public String getCountry() {
    return country;
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
