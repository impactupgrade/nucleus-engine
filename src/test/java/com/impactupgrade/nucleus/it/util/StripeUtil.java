package com.impactupgrade.nucleus.it.util;

import com.google.gson.JsonObject;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.EventData;
import com.stripe.model.Subscription;
import com.stripe.param.ChargeCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PlanCreateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import org.apache.commons.lang3.RandomStringUtils;

public class StripeUtil {

  public static Customer createCustomer(Environment env) throws StripeException {
    String randomFirstName = RandomStringUtils.randomAlphabetic(8);
    String randomLastName = RandomStringUtils.randomAlphabetic(8);
    String randomEmail = RandomStringUtils.randomAlphabetic(8).toLowerCase() + "@test.com";

    return createCustomer(randomFirstName, randomLastName, randomEmail, env);
  }

  public static Customer createCustomer(String randomFirstName, String randomLastName, String randomEmail, Environment env)
      throws StripeException {
    StripeClient stripeClient = env.stripeClient();

    CustomerCreateParams.Address address = CustomerCreateParams.Address.builder()
        .setLine1("123 Somewhere St")
        .setCity("Fort Wayne")
        .setState("IN")
        .setPostalCode("46814")
        .setCountry("US")
        .build();

    CustomerCreateParams.Builder customerBuilder = stripeClient.defaultCustomerBuilder(
        randomFirstName + " " + randomLastName,
        randomEmail,
        "tok_visa"
    ).setAddress(address).setPhone("260-123-4567");
    return stripeClient.createCustomer(customerBuilder);
  }

  public static Charge createCharge(Customer customer, Environment env) throws StripeException {
    StripeClient stripeClient = env.stripeClient();

    ChargeCreateParams.Builder chargeBuilder = stripeClient.defaultChargeBuilder(
        customer,
        customer.getSources().getData().get(0),
        100,
        "USD"
    );
    return stripeClient.createCharge(chargeBuilder);
  }

  public static Subscription createSubscription(Customer customer, Environment env, PlanCreateParams.Interval interval ) throws StripeException {
    StripeClient stripeClient = env.stripeClient();

    ProductCreateParams.Builder productBuilder = stripeClient.defaultProductBuilder(customer, 100, "USD");
    PlanCreateParams.Builder planBuilder = stripeClient.defaultPlanBuilder(100, "USD", interval);
    SubscriptionCreateParams.Builder subscriptionBuilder = stripeClient.defaultSubscriptionBuilder(customer, customer.getSources().getData().get(0));
    return stripeClient.createSubscription(productBuilder, planBuilder, subscriptionBuilder);
  }

  public static String createEventJson(String type, JsonObject object, long created) {
    Event event = new Event();
    event.setId("evt_123");
    event.setObject("event");
    event.setApiVersion("2020-08-27");
    event.setCreated(created);
    EventData eventData = new EventData();
    eventData.setObject(object);
    event.setData(eventData);
    event.setType(type);
    return event.toJson();
  }
}
