package com.impactupgrade.common.paymentgateway.stripe;

import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
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
import com.stripe.net.RequestOptions;
import com.stripe.param.ChargeCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerRetrieveParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.PaymentSourceCollectionCreateParams;
import com.stripe.param.PayoutListParams;
import com.stripe.param.PlanCreateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StripeClient {

  private static final Logger log = LogManager.getLogger(StripeClient.class.getName());

  protected static final String SDF = "MM/dd/yy hh:mm";

  protected final RequestOptions requestOptions;

  public StripeClient(String apiKey) {
    requestOptions = RequestOptions.builder().setApiKey(apiKey).build();
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

  public PaymentIntent getPaymentIntent(String id) throws StripeException {
    return PaymentIntent.retrieve(id, requestOptions);
  }

  public void cancelSubscription(String id) throws StripeException {
    log.info("cancelling subscription {}...", id);
    Subscription.retrieve(id, requestOptions).cancel();
    log.info("cancelled subscription {}", id);
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

  public Customer createCustomer(String name, String email, String sourceToken) throws StripeException {
    CustomerCreateParams customerParams = CustomerCreateParams.builder()
        .setName(name)
        // Important to use the name as the description! Allows the Subscriptions list to display customers
        // by-name, otherwise it's limited to email.
        .setDescription(name)
        .setEmail(email)
        .setSource(sourceToken)
        .addExpand("sources")
        .build();
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

  // TODO: SFDC specific
  public Customer updateCustomer(Customer customer, String sfAccountId, String sfContactId) throws StripeException {
    Map<String, String> customerMetadata = new HashMap<>();
    // TODO: DR specific
    customerMetadata.put("sf_account", sfAccountId);
    customerMetadata.put("sf_contact", sfContactId);

    CustomerUpdateParams customerParams = CustomerUpdateParams.builder()
        .setMetadata(customerMetadata)
        .addExpand("sources")
        .build();
    return customer.update(customerParams, requestOptions);
  }

  // TODO: SFDC specific
  public Charge createCharge(Customer customer, PaymentSource source, long amountInCents, String currency,
      String description, String sfCampaignId, String sfOppRecordType) throws StripeException {
    Map<String, String> chargeMetadata = new HashMap<>();
    // TODO: DR specific
    chargeMetadata.put("sf_campaign", sfCampaignId);
    chargeMetadata.put("sf_opp_record_type", sfOppRecordType);

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

  // TODO: SFDC specific
  public Subscription createSubscription(Customer customer, PaymentSource source, long amountInCents, String currency,
      String description, String sfCampaignId) throws StripeException {
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

    Map<String, String> subscriptionMetadata = new HashMap<>();
    subscriptionMetadata.put("description", description);
    // TODO: DR specific
    subscriptionMetadata.put("sf_campaign", sfCampaignId);

    SubscriptionCreateParams.Item item = SubscriptionCreateParams.Item.builder().setPlan(plan.getId()).build();
    SubscriptionCreateParams subscriptionParams = SubscriptionCreateParams.builder()
        .setCustomer(customer.getId())
        .setDefaultSource(source.getId())
        .addItem(item)
        .setMetadata(subscriptionMetadata)
        .build();
    return Subscription.create(subscriptionParams, requestOptions);
  }
}
