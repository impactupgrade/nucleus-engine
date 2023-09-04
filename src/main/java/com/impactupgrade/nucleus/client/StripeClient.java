/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.impactupgrade.nucleus.environment.Environment;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.BalanceTransactionCollection;
import com.stripe.model.Card;
import com.stripe.model.Charge;
import com.stripe.model.ChargeCollection;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.EventCollection;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentSource;
import com.stripe.model.Payout;
import com.stripe.model.Plan;
import com.stripe.model.Product;
import com.stripe.model.Refund;
import com.stripe.model.RefundCollection;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.net.RequestOptions;
import com.stripe.param.ChargeCreateParams;
import com.stripe.param.ChargeListParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerListParams;
import com.stripe.param.CustomerRetrieveParams;
import com.stripe.param.CustomerSearchParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentListParams;
import com.stripe.param.PaymentIntentUpdateParams;
import com.stripe.param.PaymentSourceCollectionCreateParams;
import com.stripe.param.PayoutListParams;
import com.stripe.param.PlanCreateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.SubscriptionCancelParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.common.EmptyParam;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class StripeClient {

  protected final Environment env;
  protected final RequestOptions requestOptions;

  public StripeClient(Environment env) {
    this.env = env;
    requestOptions = RequestOptions.builder().setApiKey(env.getConfig().stripe.secretKey).build();
  }

  public StripeClient(RequestOptions requestOptions, Environment env) {
    this.env = env;
    this.requestOptions = requestOptions;
  }

  public Charge getCharge(String id) throws StripeException {
    return new Retriever<Charge>() {
      @Override
      protected Charge retrieve() throws StripeException {
        return Charge.retrieve(id, requestOptions);
      }
    }.result();
  }

  public List<Charge> getChargesFromCustomer(String customerId) throws StripeException {
    ChargeListParams params = ChargeListParams.builder().setCustomer(customerId).build();
    return Charge.list(params, requestOptions).getData();
  }

  public Invoice getInvoice(String id) throws StripeException {
    Map<String, Object> params = new HashMap<>();
    List<String> expand = new ArrayList<>();
    expand.add("subscription");
    params.put("expand", expand);

    return new Retriever<Invoice>() {
      @Override
      protected Invoice retrieve() throws StripeException {
        return Invoice.retrieve(id, params, requestOptions);
      }
    }.result();
  }

  public BalanceTransaction getBalanceTransaction(String id) throws StripeException {
    return new Retriever<BalanceTransaction>() {
      @Override
      protected BalanceTransaction retrieve() throws StripeException {
        return BalanceTransaction.retrieve(id, requestOptions);
      }
    }.result();
  }

  public Customer getCustomer(String id) throws StripeException {
    CustomerRetrieveParams customerParams = CustomerRetrieveParams.builder()
        .addExpand("sources")
        .build();
    return new Retriever<Customer>() {
      @Override
      protected Customer retrieve() throws StripeException {
        return Customer.retrieve(id, customerParams, requestOptions);
      }
    }.result();
  }

  public List<Customer> getCustomersByEmail(String email) throws StripeException {
    CustomerListParams customerParams = CustomerListParams.builder()
        .setEmail(email)
        .addExpand("data.sources")
        .build();
    return Customer.list(customerParams, requestOptions).getData();
  }

  public Optional<Customer> getCustomerByEmail(String email) throws StripeException {
    // If there are multiple, use the oldest.
    return getCustomersByEmail(email).stream().min(Comparator.comparing(Customer::getCreated));
  }

  public List<Customer> getCustomersByName(String name) throws StripeException {
    CustomerSearchParams customerParams = CustomerSearchParams.builder()
        .setQuery("name~\"" + name + "\"")
        .addExpand("data.sources")
        .build();
    return Customer.search(customerParams, requestOptions).getData();
  }

  public PaymentIntent getPaymentIntent(String id) throws StripeException {
    return new Retriever<PaymentIntent>() {
      @Override
      protected PaymentIntent retrieve() throws StripeException {
        return PaymentIntent.retrieve(id, requestOptions);
      }
    }.result();
  }

  public List<PaymentIntent> getPaymentIntentsFromCustomer(String customerId) throws StripeException {
    PaymentIntentListParams params = PaymentIntentListParams.builder().setCustomer(customerId).build();
    return PaymentIntent.list(params, requestOptions).getData();
  }

  public Refund getRefund(String id) throws StripeException {
    return new Retriever<Refund>() {
      @Override
      protected Refund retrieve() throws StripeException {
        return Refund.retrieve(id, requestOptions);
      }
    }.result();
  }

  public Subscription getSubscription(String id) throws StripeException {
    return new Retriever<Subscription>() {
      @Override
      protected Subscription retrieve() throws StripeException {
        return Subscription.retrieve(id, requestOptions);
      }
    }.result();
  }

  public List<Subscription> getActiveSubscriptionsFromCustomer(String customerId) throws StripeException {
    SubscriptionListParams params = SubscriptionListParams.builder().setCustomer(customerId).build();
    return Subscription.list(params, requestOptions).getData();
  }

  public void cancelSubscription(String id) throws StripeException {
    env.logJobInfo("cancelling subscription {}...", id);
    // TODO: set prorate/invoice_now params? Is this even needed?
    SubscriptionCancelParams params = SubscriptionCancelParams.builder().build();
    Subscription.retrieve(id, requestOptions).cancel(params, requestOptions);
    env.logJobInfo("cancelled subscription {}", id);
  }

  public SubscriptionItem getSubscriptionItem(String id) throws StripeException {
    return new Retriever<SubscriptionItem>() {
      @Override
      protected SubscriptionItem retrieve() throws StripeException {
        return SubscriptionItem.retrieve(id, requestOptions);
      }
    }.result();
  }

  public Iterable<Charge> getAllChargesFromCustomer(String customerId) throws StripeException {
    ChargeCollection chargeCollection = Charge.list(ChargeListParams.builder().setCustomer(customerId).build(), requestOptions);
    return chargeCollection.autoPagingIterable();
  }

  public Iterable<Charge> getAllCharges(Date startDate, Date endDate) throws StripeException {
    Map<String, Object> params = new HashMap<>();
    params.put("limit", 100);
    Map<String, Object> createdParams = new HashMap<>();
    createdParams.put("gte", startDate.getTime() / 1000);
    createdParams.put("lte", endDate.getTime() / 1000);
    params.put("created", createdParams);

    List<String> expandList = new ArrayList<>();
    expandList.add("data.customer");
    expandList.add("data.payment_intent");
    expandList.add("data.balance_transaction");
    params.put("expand", expandList);

    ChargeCollection chargeCollection = Charge.list(params, requestOptions);
    return chargeCollection.autoPagingIterable();
  }

  public Iterable<Refund> getAllRefunds(Date startDate, Date endDate) throws StripeException {
    Map<String, Object> params = new HashMap<>();
    params.put("limit", 100);
    Map<String, Object> createdParams = new HashMap<>();
    createdParams.put("gte", startDate.getTime() / 1000);
    createdParams.put("lte", endDate.getTime() / 1000);
    params.put("created", createdParams);

    List<String> expandList = new ArrayList<>();
    expandList.add("data.charge");
    expandList.add("data.payment_intent");
    expandList.add("data.balance_transaction");
    params.put("expand", expandList);

    RefundCollection refundCollection = Refund.list(params, requestOptions);
    return refundCollection.autoPagingIterable();
  }

  public Iterable<Event> getAllEvents(String eventType, Date startDate, Date endDate) throws StripeException {
    Map<String, Object> params = new HashMap<>();
    params.put("limit", 100);
    Map<String, Object> createdParams = new HashMap<>();
    createdParams.put("gte", startDate.getTime() / 1000);
    createdParams.put("lte", endDate.getTime() / 1000);
    params.put("created", createdParams);
    params.put("type", eventType);

    EventCollection eventCollection = Event.list(params, requestOptions);
    return eventCollection.autoPagingIterable();
  }

  public List<Payout> getPayouts(Date startDate, Date endDate, int payoutLimit) throws StripeException {
    PayoutListParams.ArrivalDate arrivalDate = PayoutListParams.ArrivalDate.builder()
        .setGte(startDate.getTime() / 1000)
        .setLte(endDate.getTime() / 1000)
        .build();
    PayoutListParams params = PayoutListParams.builder()
        .setLimit((long) payoutLimit)
        .setArrivalDate(arrivalDate)
        .build();

    return Payout.list(params, requestOptions).getData();
  }

  public List<Payout> getPayouts(String endingBefore, int payoutLimit) throws StripeException {
    Map<String, Object> payoutParams = new HashMap<>();
    // "ending_before" is a little misleading -- results are LIFO, so this actually means "give me all payouts
    // that have happened *after* (chronologically) the given payoutId
    payoutParams.put("ending_before", endingBefore);
    payoutParams.put("limit", payoutLimit);

    return Payout.list(payoutParams, requestOptions).getData();
  }

  public Payout getPayout(String id) throws StripeException {
    return new Retriever<Payout>() {
      @Override
      protected Payout retrieve() throws StripeException {
        return Payout.retrieve(id, requestOptions);
      }
    }.result();
  }

  public List<BalanceTransaction> getBalanceTransactions(String payoutId) throws StripeException {
    Payout payout = Payout.retrieve(payoutId, requestOptions);
    return getBalanceTransactions(payout);
  }

  public List<BalanceTransaction> getBalanceTransactions(Payout payout) throws StripeException {
    List<BalanceTransaction> balanceTransactions = getBalanceTransactions(payout, null);
    if (balanceTransactions.isEmpty()) {
      env.logJobInfo("no new payouts to process");
    }

    return balanceTransactions;
  }

  /**
   * The API has a max limit of 100 transactions per page, and we *do* have payouts with 100+ charges, so
   * we need to iterate over the paging.
   * @return
   */
  private List<BalanceTransaction> getBalanceTransactions(Payout payout, BalanceTransaction startingAfter)
      throws StripeException {

    Map<String, Object> transactionParams = new HashMap<>();
    transactionParams.put("payout", payout.getId());
    transactionParams.put("limit", 100);
    if (startingAfter != null) {
      transactionParams.put("starting_after", startingAfter.getId());
    }
    List<String> transactionExpand = new ArrayList<>();
    // must include this expansion, otherwise the response will only contain the chargeId and not the Charge itself
    transactionExpand.add("data.source");
    // also include the customer -- need it as a fallback for campaigns
    transactionExpand.add("data.source.customer");
    // and the charge
    transactionExpand.add("data.source.charge");
    // and the payment intent
    transactionExpand.add("data.source.payment_intent");
    transactionParams.put("expand", transactionExpand);
    BalanceTransactionCollection balanceTransactionsPage = BalanceTransaction.list(transactionParams, requestOptions);
    env.logJobInfo("found {} transactions in payout page", balanceTransactionsPage.getData().size());

    List<BalanceTransaction> balanceTransactions = new ArrayList<>(balanceTransactionsPage.getData());
    // if there were 100 transactions, iterate to add the next page
    if (balanceTransactionsPage.getData().size() >= 100) {
      balanceTransactions.addAll(getBalanceTransactions(payout, Iterables.getLast(balanceTransactionsPage.getData())));
    }

    return balanceTransactions;
  }

  public CustomerCreateParams.Builder defaultCustomerBuilder(String name, String email, String sourceToken) {
    return CustomerCreateParams.builder()
        .setName(name)
        // Important to use the name as the description! Allows the Subscriptions list to display customers
        // by-name, otherwise it's limited to email. But still allow this to be overwritten.
        .setDescription(name)
        .setEmail(email)
        .setSource(sourceToken)
        .addExpand("sources");
  }

  public Customer createCustomer(CustomerCreateParams.Builder customerBuilder) throws StripeException {
    return Customer.create(customerBuilder.build(), requestOptions);
  }

  public PaymentSource addCustomerSource(Customer customer, String sourceToken) throws StripeException {
    PaymentSourceCollectionCreateParams params = PaymentSourceCollectionCreateParams.builder()
        .setSource(sourceToken)
        .build();
    PaymentSource newSource = customer.getSources().create(params, requestOptions);

    // de-duplicate
    Iterable<PaymentSource> existingSources = customer.getSources().autoPagingIterable();
    for (PaymentSource existingSource : existingSources) {
      // TODO: Assumes new donations are card only -- will break for Plaid!
      if (existingSource instanceof Card existingCard) {
        Card newCard = (Card) newSource;
        boolean sameCard = existingCard.getFingerprint().equals(newCard.getFingerprint())
            && existingCard.getExpMonth().equals(newCard.getExpMonth())
            && existingCard.getExpYear().equals(newCard.getExpYear());
        boolean cvcOverride = "pass".equals(newCard.getCvcCheck()) && !"pass".equals(existingCard.getCvcCheck());
        boolean zipOverride = "pass".equals(newCard.getAddressZipCheck()) && !"pass".equals(existingCard.getAddressZipCheck());
        if (sameCard) {
          if (cvcOverride || zipOverride) {
            // keep existing card and just insert the new one
            env.logJobInfo("card duplicated an existing source; keeping the old card and simply inserting the new one");
          } else {
            // keep existing one
            env.logJobInfo("card duplicated an existing source; removing it and reusing the existing one");
            newCard.delete(requestOptions);
            return existingCard;
          }
        }
      }
    }

    return newSource;
  }

  public void setCustomerDefaultSource(Customer customer, PaymentSource source) throws StripeException {
    customer.update(CustomerUpdateParams.builder().setDefaultSource(source.getId()).build(), requestOptions);
  }

  public void updateSubscriptionAmount(String subscriptionId, double dollarAmount) throws StripeException {
    env.logJobInfo("updating subscription amount to {} for subscription {}", dollarAmount, subscriptionId);

    Subscription subscription = Subscription.retrieve(subscriptionId, requestOptions);
    Plan existingPlan = subscription.getItems().getData().get(0).getPlan();

    Plan plan = createPlan(dollarAmount, existingPlan.getCurrency(), existingPlan.getInterval());

    // update the subscription with the new plan
    SubscriptionUpdateParams subscriptionUpdateParams = SubscriptionUpdateParams.builder()
        .setCancelAtPeriodEnd(false)
        .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE)
        .addItem(
            // overwrite the existing item with the new one by using the existing id
            SubscriptionUpdateParams.Item.builder()
                .setId(subscription.getItems().getData().get(0).getId())
                .setPlan(plan.getId())
                .build())
        .build();

    subscription.update(subscriptionUpdateParams, requestOptions);

    env.logJobInfo("updated subscription amount to {} for subscription {}", dollarAmount, subscriptionId);
  }

  public void updateSubscriptionDate(String subscriptionId, Calendar nextPaymentDate) throws StripeException {
    env.logJobInfo("updating subscription {} date...", subscriptionId);
    SubscriptionUpdateParams params =
        SubscriptionUpdateParams.builder()
            .setTrialEnd(nextPaymentDate.getTimeInMillis() / 1000)
            .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE)
            .build();
    Subscription subscription = Subscription.retrieve(subscriptionId, requestOptions);
    subscription.update(params, requestOptions);
    env.logJobInfo("updated subscription {} date to {}...", subscriptionId, nextPaymentDate.getTime());
  }

  public void pauseSubscription(String subscriptionId, Calendar pauseUntilDate) throws StripeException {
    env.logJobInfo("pausing subscription {}...", subscriptionId);

    Subscription subscription = Subscription.retrieve(subscriptionId, requestOptions);

    SubscriptionUpdateParams.PauseCollection.Builder pauseBuilder = SubscriptionUpdateParams.PauseCollection.builder();
    pauseBuilder.setBehavior(SubscriptionUpdateParams.PauseCollection.Behavior.MARK_UNCOLLECTIBLE);
    if (pauseUntilDate != null){
      pauseBuilder.setResumesAt(pauseUntilDate.getTimeInMillis() / 1000);
    }

    SubscriptionUpdateParams params = SubscriptionUpdateParams.builder().setPauseCollection(pauseBuilder.build()).build();
    subscription.update(params, requestOptions);

    if (pauseUntilDate != null) {
      env.logJobInfo("paused subscription {} until {}", subscription.getId(), pauseUntilDate.getTime());
    } else {
      env.logJobInfo("paused subscription {} indefinitely", subscription.getId());
    }
  }

  public void resumeSubscription(String subscriptionId, Calendar resumeOnDate) throws StripeException {
    Subscription subscription = Subscription.retrieve(subscriptionId, requestOptions);

    if ("canceled".equalsIgnoreCase(subscription.getStatus())) {
      // TODO: can't reopen a canceled subscription, so need to open a new one
      //  but should that create a new RD in the CRM (probably...), not update the existing subscription ID
    } else {
      if (resumeOnDate != null) {
        env.logJobInfo("resuming subscription {} on {}...", subscription.getId(), resumeOnDate.getTime());

        SubscriptionUpdateParams.PauseCollection.Builder pauseBuilder = SubscriptionUpdateParams.PauseCollection.builder();
        pauseBuilder.setBehavior(SubscriptionUpdateParams.PauseCollection.Behavior.MARK_UNCOLLECTIBLE);
        pauseBuilder.setResumesAt(resumeOnDate.getTimeInMillis() / 1000);

        SubscriptionUpdateParams params = SubscriptionUpdateParams.builder().setPauseCollection(pauseBuilder.build()).build();
        subscription.update(params, requestOptions);
        subscription.update(params, requestOptions);
      } else {
        env.logJobInfo("resuming subscription {} immediately...", subscription.getId());

        SubscriptionUpdateParams params = SubscriptionUpdateParams.builder().setPauseCollection(EmptyParam.EMPTY).build();
        subscription.update(params, requestOptions);
      }
    }
  }

  public void updateSubscriptionPaymentMethod(String subscriptionId, String paymentMethodToken) throws StripeException {
    Subscription subscription = getSubscription(subscriptionId);
    String customerId = subscription.getCustomer();
    env.logJobInfo("updating customer {} payment method on subscription {}...", customerId, subscriptionId);

    // add source to customer
    Customer customer = getCustomer(customerId);
    PaymentSource newSource = addCustomerSource(customer, paymentMethodToken);

    // set source as defaultSource for subscription
    updateSubscriptionPaymentMethod(subscription, newSource);

    env.logJobInfo("updated customer {} payment method on subscription {}", customerId, subscriptionId);
  }

  public void updateSubscriptionPaymentMethod(Subscription subscription, PaymentSource newSource) throws StripeException {
    SubscriptionUpdateParams subscriptionParams = SubscriptionUpdateParams.builder()
        .setDefaultSource(newSource.getId())
        .build();
    subscription.update(subscriptionParams, requestOptions);
  }

  public Customer updateCustomer(Customer customer, Map<String, String> customerMetadata) throws StripeException {
    CustomerUpdateParams customerParams = CustomerUpdateParams.builder()
        .setMetadata(customerMetadata)
        .addExpand("sources")
        .build();
    return customer.update(customerParams, requestOptions);
  }

  public ChargeCreateParams.Builder defaultChargeBuilder(Customer customer, PaymentSource source, long amountInCents,
      String currency) {
    return ChargeCreateParams.builder()
        .setCustomer(customer.getId())
        .setSource(source.getId())
        .setAmount(amountInCents)
        .setCurrency(currency);
  }

  public PaymentIntentCreateParams.Builder defaultPaymentIntentBuilder(long amountInCents, String currency) {
    return PaymentIntentCreateParams.builder()
            .setAmount(amountInCents)
            .setCurrency(currency)
            .setAutomaticPaymentMethods(
              PaymentIntentCreateParams.AutomaticPaymentMethods.builder().setEnabled(true).build()
            );
  }

  public Charge createCharge(ChargeCreateParams.Builder chargeBuilder) throws StripeException {
    return Charge.create(chargeBuilder.build(), requestOptions);
  }

  public PaymentIntent createPaymentIntent(PaymentIntentCreateParams.Builder paymentIntentBuilder) throws StripeException {
    return PaymentIntent.create(paymentIntentBuilder.build(), requestOptions);
  }

  public PaymentIntent updatePaymentIntent(PaymentIntent paymentIntent, PaymentIntentUpdateParams.Builder paymentIntentUpdateBuilder) throws StripeException {
    return paymentIntent.update(paymentIntentUpdateBuilder.build(), requestOptions);
  }

  public ProductCreateParams.Builder defaultProductBuilder(Customer customer, long amountInCents, String currency) {
    String name = Strings.isNullOrEmpty(customer.getName()) ? customer.getEmail() : customer.getName();
    return ProductCreateParams.builder()
        .setName(name + ": $" + new DecimalFormat("#.##").format(amountInCents / 100.0) + " " + currency.toUpperCase(Locale.ROOT) + " (monthly)");
  }

  public PlanCreateParams.Builder defaultPlanBuilder(long amountInCents, String currency) {
    return PlanCreateParams.builder()
        .setInterval(PlanCreateParams.Interval.MONTH)
        .setAmount(amountInCents)
        .setCurrency(currency);
  }

  public SubscriptionCreateParams.Builder defaultSubscriptionBuilder(Customer customer, PaymentSource source) {
    return defaultSubscriptionBuilder(customer, source, null, null);
  }

  public SubscriptionCreateParams.Builder defaultSubscriptionBuilder(Customer customer, PaymentSource source, Integer autoCancelMonths) {
    return defaultSubscriptionBuilder(customer, source, null, autoCancelMonths);
  }

  public SubscriptionCreateParams.Builder defaultSubscriptionBuilder(Customer customer, PaymentSource source,
     SubscriptionCreateParams.PaymentBehavior behavior, Integer autoCancelMonths) {
    SubscriptionCreateParams.Builder subscriptionCreateParamsBuilder = SubscriptionCreateParams.builder();
    subscriptionCreateParamsBuilder.setCustomer(customer.getId());

    if (source != null) {
      subscriptionCreateParamsBuilder.setDefaultSource(source.getId());
    } else {
      subscriptionCreateParamsBuilder.setPaymentSettings(SubscriptionCreateParams.PaymentSettings.builder()
          // TODO: Is there a way to enable *all*, like we do with defaultPaymentIntentBuilder?
          .addPaymentMethodType(SubscriptionCreateParams.PaymentSettings.PaymentMethodType.CARD)
          .addPaymentMethodType(SubscriptionCreateParams.PaymentSettings.PaymentMethodType.US_BANK_ACCOUNT)
          .build());
    }

    if (behavior != null) {
      subscriptionCreateParamsBuilder.setPaymentBehavior(behavior);
    } else {
      // fail immediately if zip/cvc fails, just like a onetime charge -- default is to allow
      // incomplete invoices, which doesn't give immediate donor feedback
      subscriptionCreateParamsBuilder.setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.ERROR_IF_INCOMPLETE);
    }
    if (autoCancelMonths != null) {
      Calendar future = Calendar.getInstance();
      future.add(Calendar.MONTH, autoCancelMonths);
      Long cancelAt = future.getTimeInMillis() / 1000;

      subscriptionCreateParamsBuilder.setCancelAt(cancelAt);
    }
    return subscriptionCreateParamsBuilder;
  }

  public Subscription createSubscription(ProductCreateParams.Builder productBuilder,
      PlanCreateParams.Builder planBuilder, SubscriptionCreateParams.Builder subscriptionBuilder) throws StripeException {
    Product product = Product.create(productBuilder.build(), requestOptions);

    PlanCreateParams planParams = planBuilder.setProduct(product.getId()).build();
    Plan plan = Plan.create(planParams, requestOptions);

    SubscriptionCreateParams.Item item = SubscriptionCreateParams.Item.builder().setPlan(plan.getId()).build();
    SubscriptionCreateParams subscriptionParams = subscriptionBuilder
        .addItem(item)
        .build();
    return Subscription.create(subscriptionParams, requestOptions);
  }

  // TODO: merge this with the other plan creation, but it needs tested since it will affect at least LJI DP.
  public Plan createPlan(double dollarAmount, String currencyCode, String frequency) throws StripeException {
    frequency = frequency.toLowerCase(Locale.ROOT);
    if ("week".equalsIgnoreCase(frequency)) {
      frequency = "weekly";
    } else if ("month".equalsIgnoreCase(frequency)) {
      frequency = "monthly";
    } else if ("year".equalsIgnoreCase(frequency)) {
      frequency = "yearly";
    }

    int wholeDollarAmount = (int) dollarAmount;

    String planId = "plan_" + wholeDollarAmount + "_" + currencyCode + "_" + frequency;

    try {
      Plan plan = Plan.retrieve(planId, requestOptions);
      if (plan != null) {
        env.logJobInfo("plan {} already exists", planId);
        return plan;
      }
    } catch (InvalidRequestException e) {
      // fall-through -- SDK currently throws this if the plan does *not* exist
    }

    env.logJobInfo("plan {} does not exist; creating it...", planId);

    PlanCreateParams.Interval interval = PlanCreateParams.Interval.MONTH;
    if ("yearly".equalsIgnoreCase(frequency)) {
      interval = PlanCreateParams.Interval.YEAR;
    } else if ("weekly".equalsIgnoreCase(frequency)) {
      interval = PlanCreateParams.Interval.WEEK;
    }

    PlanCreateParams planCreateParams = PlanCreateParams
        .builder()
        .setId(planId)
        .setProduct(
            PlanCreateParams.Product.builder()
                .setName("plan " + wholeDollarAmount + " " + currencyCode + " " + frequency)
                .build()
        )
        .setAmount((long) (dollarAmount * 100))
        .setCurrency(currencyCode)
        .setInterval(interval)
        .build();
    Plan plan = Plan.create(planCreateParams, requestOptions);

    env.logJobInfo("created plan {}", planId);

    return plan;
  }

  private abstract class Retriever<T> {
    protected abstract T retrieve() throws StripeException;

    public T result() throws StripeException {
      return result(0);
    }

    private T result(int count) throws StripeException {
      try {
        return retrieve();
      } catch (RateLimitException e) {
        env.logJobInfo("Stripe API attempt {} failed due to rate limit or lock; retrying in 3s", count, e);
        try {
          Thread.sleep(3000);
        } catch (InterruptedException e1) {
          env.logJobError("sleep failed", e1);
        }

        if (count == 4) {
          env.logJobError("unable to call Stripe API by attempt {}", count);
          // rethrow exception, since the whole flow simply needs to halt at this point
          throw e;
        }

        return result(count + 1);
      } catch (Exception e) {
        env.logJobError("Stripe API failed", e);
        throw e;
      }
    }
  }

  public RequestOptions getRequestOptions() {
    return requestOptions;
  }
}
