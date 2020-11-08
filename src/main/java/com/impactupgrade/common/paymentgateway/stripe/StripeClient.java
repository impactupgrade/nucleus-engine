package com.impactupgrade.common.paymentgateway.stripe;

import com.sforce.ws.ConnectionException;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerRetrieveParams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StripeClient {

  private static final Logger log = LogManager.getLogger(StripeClient.class.getName());

  private static final String STRIPE_DEFAULT_KEY = System.getenv("STRIPE_KEY");

  public static Invoice getInvoice(String id, RequestOptions requestOptions) throws StripeException {
    Map<String, Object> params = new HashMap<>();
    List<String> expand = new ArrayList<>();
    expand.add("subscription");
    params.put("expand", expand);

    return Invoice.retrieve(id, params, requestOptions);
  }

  public static BalanceTransaction getBalanceTransaction(String id, RequestOptions requestOptions) throws StripeException {
    return BalanceTransaction.retrieve(id, requestOptions);
  }

  public static Customer getCustomer(String id, RequestOptions requestOptions) throws StripeException {
    CustomerRetrieveParams customerParams = CustomerRetrieveParams.builder()
        .addExpand("sources")
        .build();
    return Customer.retrieve(id, customerParams, requestOptions);
  }

  public static void cancelSubscription(String id, RequestOptions requestOptions) throws StripeException {
    log.info("cancelling subscription {}...", id);
    Subscription.retrieve(id, requestOptions).cancel();
    log.info("cancelled subscription {}", id);
  }

  protected static RequestOptions defaultRequestOptions() {
    return RequestOptions.builder().setApiKey(STRIPE_DEFAULT_KEY).build();
  }
}
