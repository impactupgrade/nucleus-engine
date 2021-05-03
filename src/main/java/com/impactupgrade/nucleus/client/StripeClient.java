package com.impactupgrade.nucleus.client;

import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.stripe.exception.InvalidRequestException;
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
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.net.RequestOptions;
import com.stripe.param.ChargeCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerListParams;
import com.stripe.param.CustomerRetrieveParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.PaymentSourceCollectionCreateParams;
import com.stripe.param.PayoutListParams;
import com.stripe.param.PlanCreateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

// TODO: The method arguments are getting out of control. Update these to use a record class to pass in
//  whatever is needed.
public class StripeClient {

  private static final Logger log = LogManager.getLogger(StripeClient.class.getName());

  protected static final String SDF = "MM/dd/yy hh:mm";

  protected final RequestOptions requestOptions;

  public StripeClient(String apiKey) {
    requestOptions = RequestOptions.builder().setApiKey(apiKey).build();
  }

  public StripeClient(RequestOptions requestOptions) {
    this.requestOptions = requestOptions;
  }

  public Charge getCharge(String id) throws StripeException {
    return Charge.retrieve(id, requestOptions);
  }

  public Invoice getInvoice(String id) throws StripeException {
    Map<String, Object> params = new HashMap<>();
    List<String> expand = new ArrayList<>();
    expand.add("subscription");
    params.put("expand", expand);

    return Invoice.retrieve(id, params, requestOptions);
  }

  public BalanceTransaction getBalanceTransaction(String id) throws StripeException {
    return BalanceTransaction.retrieve(id, requestOptions);
  }

  public Customer getCustomer(String id) throws StripeException {
    CustomerRetrieveParams customerParams = CustomerRetrieveParams.builder()
        .addExpand("sources")
        .build();
    return Customer.retrieve(id, customerParams, requestOptions);
  }

  public Optional<Customer> getCustomerByEmail(String email) throws StripeException {
    CustomerListParams customerParams = CustomerListParams.builder()
        .setEmail(email)
        .addExpand("data.sources")
        .setLimit(1L)
        .build();
    return Customer.list(customerParams, requestOptions).getData().stream().findFirst();
  }

  public PaymentIntent getPaymentIntent(String id) throws StripeException {
    return PaymentIntent.retrieve(id, requestOptions);
  }

  public Subscription getSubscription(String id) throws StripeException {
    return Subscription.retrieve(id, requestOptions);
  }

  public void cancelSubscription(String id) throws StripeException {
    log.info("cancelling subscription {}...", id);
    Subscription.retrieve(id, requestOptions).cancel();
    log.info("cancelled subscription {}", id);
  }

  public SubscriptionItem getSubscriptionItem(String id) throws StripeException {
    return SubscriptionItem.retrieve(id, requestOptions);
  }

  public Iterable<Charge> getAllCharges(Date startDate, Date endDate) throws StripeException {
    Map<String, Object> params = new HashMap<>();
    params.put("limit", 100);
    Map<String, Object> createdParams = new HashMap<>();
    createdParams.put("gte", startDate);
    createdParams.put("lte", endDate);
    params.put("created", createdParams);

    List<String> expandList = new ArrayList<>();
    expandList.add("data.payment_intent");
    params.put("expand", expandList);

    ChargeCollection chargeCollection = Charge.list(params, requestOptions);
    return chargeCollection.autoPagingIterable();
  }

  public Iterable<Event> getAllEvents(String eventType, Date startDate, Date endDate) throws StripeException {
    Map<String, Object> params = new HashMap<>();
    params.put("limit", 100);
    Map<String, Object> createdParams = new HashMap<>();
    createdParams.put("gte", startDate);
    createdParams.put("lte", endDate);
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

  public List<BalanceTransaction> getBalanceTransactions(String payoutId) throws StripeException {
    Payout payout = Payout.retrieve(payoutId, requestOptions);
    return getBalanceTransactions(payout);
  }

  public List<BalanceTransaction> getBalanceTransactions(Payout payout) throws StripeException {
    List<BalanceTransaction> balanceTransactions = getBalanceTransactions(payout, null);
    if (balanceTransactions.isEmpty()) {
      log.info("no new payouts to process");
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
    // and the payment intent
    transactionExpand.add("data.source.payment_intent");
    transactionParams.put("expand", transactionExpand);
    BalanceTransactionCollection balanceTransactionsPage = BalanceTransaction.list(transactionParams, requestOptions);
    log.info("found {} transactions in payout page", balanceTransactionsPage.getData().size());

    List<BalanceTransaction> balanceTransactions = new ArrayList<>(balanceTransactionsPage.getData());
    // if there were 100 transactions, iterate to add the next page
    if (balanceTransactionsPage.getData().size() >= 100) {
      balanceTransactions.addAll(getBalanceTransactions(payout, Iterables.getLast(balanceTransactionsPage.getData())));
    }

    return balanceTransactions;
  }

  public Customer createCustomer(String name, String email, String sourceToken, Map<String, String> customerMetadata) throws StripeException {
    CustomerCreateParams customerParams = CustomerCreateParams.builder()
        .setName(name)
        // Important to use the name as the description! Allows the Subscriptions list to display customers
        // by-name, otherwise it's limited to email.
        .setDescription(name)
        .setEmail(email)
        .setSource(sourceToken)
        .setMetadata(customerMetadata)
        .addExpand("sources")
        .build();
    return createCustomer(customerParams);
  }
  public Customer createCustomer(CustomerCreateParams customerParams) throws StripeException {
    return Customer.create(customerParams, requestOptions);
  }

  public PaymentSource updateCustomerSource(Customer customer, String sourceToken) throws StripeException {
    PaymentSourceCollectionCreateParams params = PaymentSourceCollectionCreateParams.builder()
        .setSource(sourceToken)
        .build();
    PaymentSource newSource = customer.getSources().create(params, requestOptions);

    // de-duplicate
    Iterable<PaymentSource> existingSources = customer.getSources().autoPagingIterable();
    for (PaymentSource existingSource : existingSources) {
      // TODO: Assumes we're card only -- will break for Plaid!
      Card existingCard = (Card) existingSource;
      Card newCard = (Card) newSource;
      if (existingCard.getFingerprint().equals(newCard.getFingerprint())
          && existingCard.getExpMonth().equals(newCard.getExpMonth()) && existingCard.getExpYear().equals(newCard.getExpYear())) {
        log.info("card duplicated an existing source; removing it and reusing the existing one");
        newCard.delete(requestOptions);
        return existingCard;
      }
    }

    return newSource;
  }

  public void updateSubscriptionAmount(String subscriptionId, double dollarAmount) throws StripeException {
    log.info("updating subscription amount to {} for subscription {}", dollarAmount, subscriptionId);

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

    log.info("updated subscription amount to {} for subscription {}", dollarAmount, subscriptionId);
  }

  public void updateSubscriptionDate(String subscriptionId, Calendar nextPaymentDate) throws StripeException {
    log.info("updating subscription {} date...", subscriptionId);
    SubscriptionUpdateParams params =
        SubscriptionUpdateParams.builder()
            .setTrialEnd(nextPaymentDate.getTimeInMillis() / 1000)
            .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE)
            .build();
    Subscription subscription = Subscription.retrieve(subscriptionId, requestOptions);
    subscription.update(params, requestOptions);
    log.info("updated subscription {} date to {}...", subscriptionId, nextPaymentDate.getTime());
  }

  public void pauseSubscription(String subscriptionId, Calendar pauseUntilDate) throws StripeException {
    log.info("pausing subscription {}...", subscriptionId);

    Subscription subscription = Subscription.retrieve(subscriptionId, requestOptions);

    SubscriptionUpdateParams.PauseCollection.Builder pauseBuilder = SubscriptionUpdateParams.PauseCollection.builder();
    pauseBuilder.setBehavior(SubscriptionUpdateParams.PauseCollection.Behavior.MARK_UNCOLLECTIBLE);
    if (pauseUntilDate != null){
      pauseBuilder.setResumesAt(pauseUntilDate.getTimeInMillis() / 1000);
    }

    SubscriptionUpdateParams params = SubscriptionUpdateParams.builder().setPauseCollection(pauseBuilder.build()).build();
    subscription.update(params, requestOptions);

    if (pauseUntilDate != null) {
      log.info("paused subscription {} until {}", subscription.getId(), pauseUntilDate.getTime());
    } else {
      log.info("paused subscription {} indefinitely", subscription.getId());
    }

  }

  public Customer updateCustomer(Customer customer, Map<String, String> customerMetadata) throws StripeException {
    CustomerUpdateParams customerParams = CustomerUpdateParams.builder()
        .setMetadata(customerMetadata)
        .addExpand("sources")
        .build();
    return customer.update(customerParams, requestOptions);
  }

  public Charge createCharge(Customer customer, PaymentSource source, long amountInCents, String currency,
      String description, Map<String, String> chargeMetadata) throws StripeException {
    // Stripe hates empty strings
    description = Strings.isNullOrEmpty(description) ? null : description;

    ChargeCreateParams chargeParams = ChargeCreateParams.builder()
        .setCustomer(customer.getId())
        .setSource(source.getId())
        .setAmount(amountInCents)
        .setCurrency(currency)
        .setDescription(description)
        .setMetadata(chargeMetadata)
        .build();
    return Charge.create(chargeParams, requestOptions);
  }

  public Subscription createSubscription(Customer customer, PaymentSource source, long amountInCents, String currency,
      String description, Integer autoCancelMonths, Map<String, String> subscriptionMetadata) throws StripeException {
    // Stripe hates empty strings
    description = Strings.isNullOrEmpty(description) ? null : description;

    ProductCreateParams productParams = ProductCreateParams.builder()
        .setName(customer.getName() + ": $" + new DecimalFormat("#.##").format(amountInCents / 100.0) + " " + currency.toUpperCase(Locale.ROOT) + " (monthly)")
        .build();
    Product product = Product.create(productParams, requestOptions);
    PlanCreateParams planParams = PlanCreateParams.builder()
        .setProduct(product.getId())
        .setInterval(PlanCreateParams.Interval.MONTH)
        .setAmount(amountInCents)
        .setCurrency(currency)
        .build();
    Plan plan = Plan.create(planParams, requestOptions);

    // TODO: This 1) assumes the Map is mutable and 2) overwrites one explicitly provided by the caller
    description = Strings.isNullOrEmpty(description) ? null : description;
    subscriptionMetadata.put("description", description);

    Long cancelAt = null;
    if (autoCancelMonths != null) {
      Calendar future = Calendar.getInstance();
      future.add(Calendar.MONTH, autoCancelMonths);
      cancelAt = future.getTimeInMillis() / 1000;
    }

    SubscriptionCreateParams.Item item = SubscriptionCreateParams.Item.builder().setPlan(plan.getId()).build();
    SubscriptionCreateParams subscriptionParams = SubscriptionCreateParams.builder()
        .setCustomer(customer.getId())
        .setDefaultSource(source.getId())
        .addItem(item)
        .setCancelAt(cancelAt)
        .setMetadata(subscriptionMetadata)
        .build();
    return Subscription.create(subscriptionParams, requestOptions);
  }

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
        log.info("plan {} already exists", planId);
        return plan;
      }
    } catch (InvalidRequestException e) {
      // fall-through -- SDK currently throws this if the plan does *not* exist
    }

    log.info("plan {} does not exist; creating it...", planId);

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

    log.info("created plan {}", planId);

    return plan;
  }

}
