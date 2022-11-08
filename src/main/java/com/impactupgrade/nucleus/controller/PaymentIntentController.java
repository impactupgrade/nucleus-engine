package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.DonationFormData;
import com.impactupgrade.nucleus.service.logic.AntiFraudService;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentSource;
import com.stripe.model.Subscription;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentUpdateParams;
import com.stripe.param.PlanCreateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

@Path("/payment-intent")
public class PaymentIntentController {

  private static final Logger log = LogManager.getLogger(PaymentIntentController.class);

  protected final EnvironmentFactory envFactory;

  public PaymentIntentController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response createPaymentIntent(
      @BeanParam DonationFormData formData,
      @Context HttpServletRequest request
  ) {
    log.info(formData);
    Environment env = envFactory.init(request);
    AntiFraudService antiFraudService = new AntiFraudService(env);

    try {
      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // Sanity checks to block bots.
      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      if (
          formData.isFraudAttempt()
              || !antiFraudService.isRecaptchaTokenValid(formData.getRecaptchaToken())
      ) {
        // Return something meaningful, just in case it's actually a human, but not detailed to the point where
        // it tips off the nefarious person/bot to the specific issue we detected.
        return Response.status(400).entity("Payment Intent creation attempt blocked as possible spam. If this is in error, please try again or contact us!").build();
      }

      // INTEGRATION TEST
      if (formData.isIntegrationTest()) {
        log.info("integration test; skipping payment gateway");
        return Response.ok().build();
      }

      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // Payment gateways.
      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      log.info("creating Stripe payment intent");
      PaymentIntent paymentIntent = null;
//      if (formData.isRecurring()) {
//        paymentIntent = createRecurring(null, null, formData, env);
//      } else {
      paymentIntent = createOneTime(formData, env);
//      }

      String paymentIntentJson = new Gson().toJson(paymentIntent);
      return Response.ok(paymentIntentJson).build();
    } catch (Exception e) {
      log.warn("Stripe failed to create payment intent: {}", e.getMessage());
      return Response.status(400).entity(e.getMessage()).build();
    }
  }

  @Path("/{id}")
  @PUT
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response updatePaymentIntent(
      @PathParam("id") String id,
      @BeanParam DonationFormData formData,
      @Context HttpServletRequest request
  ) {
    log.info(formData);
    Environment env = envFactory.init(request);
    AntiFraudService antiFraudService = new AntiFraudService(env);

    try {
      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // Sanity checks to block bots.
      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      if (
          formData.isFraudAttempt()
              || !antiFraudService.isRecaptchaTokenValid(formData.getRecaptchaToken())
      ) {
        // Return something meaningful, just in case it's actually a human, but not detailed to the point where
        // it tips off the nefarious person/bot to the specific issue we detected.
        return Response.status(400).entity("Payment Intent update attempt blocked as possible spam. If this is in error, please try again or contact us!").build();
      }

      // INTEGRATION TEST
      if (formData.isIntegrationTest()) {
        log.info("integration test; skipping payment gateway");
        return Response.ok().build();
      }

      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // Payment gateways.
      ////////////////////////////////////////////////////////////////////////////////////////////////////////////////

      StripeClient stripeClient = env.stripeClient();
      PaymentIntent existing = stripeClient.getPaymentIntent(id);
      if (existing == null) {
        return Response.status(404).entity("Stripe failed to find payment intent for id: {}" + id).build();
      }

      PaymentIntent updated = updateOneTime(existing, formData, env);

      String paymentIntentJson = new Gson().toJson(updated);
      return Response.ok(paymentIntentJson).build();
    } catch (Exception e) {
      log.warn("Stripe failed to update payment intent: {}", e.getMessage());
      return Response.status(400).entity(e.getMessage()).build();
    }
  }

  protected PaymentIntent createOneTime(DonationFormData formData, Environment env) throws StripeException {
    // One-time payment
    Map<String, String> paymentIntentMetadata = new HashMap<>();
    if (formData.getCustomMetadataPaymentIntent() != null) {
      paymentIntentMetadata.putAll(formData.getCustomMetadataPaymentIntent());
    }

    String currency = getCurrency(formData, env);
    StripeClient stripeClient = env.stripeClient();

    PaymentIntentCreateParams.Builder paymentIntentCreateParamsBuilder = stripeClient.defaultPaymentIntentBuilder(
        formData.getAmountInCents(), currency
    );
    paymentIntentCreateParamsBuilder
        .setDescription(formData.getNotes())
        .putAllMetadata(paymentIntentMetadata);

    PaymentIntent paymentIntent = stripeClient.createPaymentIntent(paymentIntentCreateParamsBuilder);
    log.info("created Payment Intent {}", paymentIntent.getId());
    return paymentIntent;
  }

  protected PaymentIntent updateOneTime(PaymentIntent paymentIntent, DonationFormData formData, Environment env) throws StripeException {
    PaymentIntentUpdateParams.Builder updateParamsBuilder = PaymentIntentUpdateParams.builder()
        .setAmount(formData.getAmountInCents())
        .setCurrency(formData.getCurrency());

    if (formData.getCustomMetadataPaymentIntent() != null) {
      updateParamsBuilder.setMetadata(formData.getCustomMetadataPaymentIntent());
    }

    StripeClient stripeClient = env.stripeClient();
    PaymentIntent updated = stripeClient.updatePaymentIntent(paymentIntent, updateParamsBuilder);
    log.info("updated Payment Intent {}", updated.getId());
    return updated;
  }

  // TODO: create subscription - how to?
  protected PaymentIntent createRecurring(Customer stripeCustomer, PaymentSource stripeSource, DonationFormData formData, Environment env) throws StripeException {
    String currency = getCurrency(formData, env);
    StripeClient stripeClient = env.stripeClient();

    ProductCreateParams.Builder productBuilder = stripeClient.defaultProductBuilder(stripeCustomer, formData.getAmountInCents(), currency);
    PlanCreateParams.Builder planBuilder = stripeClient.defaultPlanBuilder(formData.getAmountInCents(), currency);

    Map<String, String> subscriptionMetadata = new HashMap<>();
    subscriptionMetadata.put("campaign", formData.getCampaignId());
    subscriptionMetadata.put("description", formData.getNotes());
    if (formData.getCustomMetadataSubscription() != null) {
      subscriptionMetadata.putAll(formData.getCustomMetadataSubscription());
    }
    SubscriptionCreateParams.Builder subscriptionBuilder = stripeClient.defaultSubscriptionBuilder(stripeCustomer, stripeSource)
        .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
        .setMetadata(subscriptionMetadata);
    Subscription subscription = stripeClient.createSubscription(productBuilder, planBuilder, subscriptionBuilder);
    log.info("created Subscription {}", subscription.getId());

    Invoice invoice = stripeClient.getInvoice(subscription.getLatestInvoice());
    log.info("got latest Invoice {}", invoice.getId());

    PaymentIntent paymentIntent = stripeClient.getPaymentIntent(invoice.getPaymentIntent());
    log.info("got Payment Intent {}", invoice.getPaymentIntent());

    return paymentIntent;
  }

  protected PaymentIntent updateRecurring(PaymentIntent existing, DonationFormData formData, Environment env) throws StripeException {

    StripeClient stripeClient = env.stripeClient();
    String invoiceId = existing.getInvoice();
    Invoice invoice = stripeClient.getInvoice(invoiceId);
    log.info("found Invoice {}", invoiceId);

    stripeClient.updateSubscriptionAmountAndCurrency(invoice.getSubscription(), formData.getAmount(), formData.getCurrency());
    log.info("updated Subscription {}", invoice.getSubscription());

//          invoiceId = subscription.getLatestInvoice();
//          invoice = stripeClient.getInvoice(invoiceId);
//
//          updated = stripeClient.getPaymentIntent(invoice.getPaymentIntent());

    PaymentIntentUpdateParams.Builder updateParamsBuilder = PaymentIntentUpdateParams.builder()
        .setAmount(formData.getAmountInCents())
        .setCurrency(formData.getCurrency());

    if (formData.getCustomMetadataPaymentIntent() != null) {
      updateParamsBuilder.setMetadata(formData.getCustomMetadataPaymentIntent());
    }

    PaymentIntent updated = env.stripeClient().updatePaymentIntent(existing, updateParamsBuilder);
    log.info("updated Payment Intent {}", updated.getId());

    return updated;
  }

  protected String getCurrency(DonationFormData formData, Environment env) {
    String currency = formData.getCurrency();
    if (Strings.isNullOrEmpty(currency)) {
      currency = env.getConfig().currency;
    }
    return currency;
  }

}
