package com.impactupgrade.common.paymentgateway.stripe;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.param.CustomerRetrieveParams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StripeClient {

  private static final Logger log = LogManager.getLogger(StripeClient.class.getName());

  static {
    Stripe.apiKey = System.getenv("STRIPE_KEY");
  }

  public static Invoice getInvoice(String id) throws StripeException {
    Map<String, Object> params = new HashMap<>();
    List<String> expand = new ArrayList<>();
    expand.add("subscription");
    params.put("expand", expand);

    return Invoice.retrieve(id, params, null);
  }

  public static BalanceTransaction getBalanceTransaction(String id) throws StripeException {
    return BalanceTransaction.retrieve(id);
  }

  public static Customer getCustomer(String id) throws StripeException {
    CustomerRetrieveParams customerParams = CustomerRetrieveParams.builder()
        .addExpand("sources")
        .build();
    return Customer.retrieve(id, customerParams, null);
  }

  public static void cancelSubscription(String subscriptionId) throws StripeException {
    log.info("canceling subscription {}...", subscriptionId);
    Subscription.retrieve(subscriptionId).cancel();
    log.info("canceled subscription {}", subscriptionId);
  }
}
