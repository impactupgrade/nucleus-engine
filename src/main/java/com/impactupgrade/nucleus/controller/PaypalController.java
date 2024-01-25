package com.impactupgrade.nucleus.controller;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.impactupgrade.nucleus.client.PaypalClient;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.service.segment.EnrichmentService;
import com.impactupgrade.nucleus.util.TestUtil;
import com.paypal.api.payments.Event;
import com.paypal.base.Constants;
import com.paypal.base.rest.APIContext;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/paypal")
public class PaypalController {

  protected final EnvironmentFactory envFactory;

  public PaypalController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @Path("/webhook")
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response webhook(String json, @Context HttpServletRequest request) throws Exception {
    Environment env = envFactory.init(request);

    try {
      validateWebhookRequest(request, json, env);
    } catch (Exception e) {
      return Response.status(400, e.getMessage()).build();
    }

    Gson gson = new GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create();
    Event event = gson.fromJson(json, Event.class);
    
    if (TestUtil.SKIP_NEW_THREADS) {
      processEvent(event, env);
    } else {
      // takes a while, so spin it off as a new thread
      Runnable thread = () -> {
        try {
          String jobName = "Paypal Event";
          env.startJobLog(JobType.EVENT, "webhook", jobName, "Paypal");
          processEvent(event, env);
          env.endJobLog(JobStatus.DONE);
        } catch (Exception e) {
          env.logJobError("failed to process the Paypal event", e);
          env.logJobError(e.getMessage());
          env.endJobLog(JobStatus.FAILED);
          // TODO: email notification?
        }
      };
      new Thread(thread).start();
    }

    return Response.status(200).build();
  }
  
  private void validateWebhookRequest(HttpServletRequest request, String requestBody, Environment env) throws Exception {
    APIContext apiContext = new APIContext(
        env.getConfig().paypal.clientId, 
        env.getConfig().paypal.clientSecret, 
        env.getConfig().paypal.mode);
    apiContext.addConfiguration(Constants.PAYPAL_WEBHOOK_ID, env.getConfig().paypal.webhookId);
    
    boolean validEvent = Event.validateReceivedEvent(apiContext, getHeadersInfo(request), requestBody);
    if (!validEvent) {
      throw new IllegalArgumentException("Invalid webhook event!");
    }
  }
  
  private Map<String, String> getHeadersInfo(HttpServletRequest request) {
    Map<String, String> map = new HashMap<>();
    Enumeration headerNames = request.getHeaderNames();
    while (headerNames.hasMoreElements()) {
      String key = (String) headerNames.nextElement();
      String value = request.getHeader(key);
      map.put(key, value);
    }
    return map;
  }

  private void processEvent(Event event, Environment env) throws Exception {
    switch (event.getEventType()) {
      case "PAYMENT.CAPTURE.COMPLETED" -> {
        PaypalClient.Capture capture = getCapture(event);

        PaymentGatewayEvent paymentGatewayEvent = toPaymentGatewayEvent(capture, env);
        // must first process the account/contact so they're available for the enricher
        env.contactService().processDonor(paymentGatewayEvent);

        enrich(paymentGatewayEvent, env);

        env.donationService().processDonation(paymentGatewayEvent);
        env.accountingService().processTransaction(paymentGatewayEvent);
      }
      case "PAYMENT.CAPTURE.DECLINED" -> {
        //A payment capture is declined.
        PaypalClient.Capture capture = getCapture(event);

        PaymentGatewayEvent paymentGatewayEvent = toPaymentGatewayEvent(capture, env);
        env.contactService().processDonor(paymentGatewayEvent);
        env.donationService().processDonation(paymentGatewayEvent);
      }
      case "PAYMENT.CAPTURE.REFUNDED" -> {
        //A merchant refunds a payment capture.
        PaypalClient.Capture capture = getCapture(event);

        PaymentGatewayEvent paymentGatewayEvent = toPaymentGatewayEvent(capture, env);
        env.donationService().refundDonation(paymentGatewayEvent);
      }
      case "BILLING.SUBSCRIPTION.CREATED" -> {
        //A billing agreement is created.
        String resourceId = getResourceJson(event).getString("id");
        // Calling get Subscription manually to get more details (plan data) than event has
        PaypalClient.Subscription subscription = env.paypalClient().getSubscription(resourceId);

        PaymentGatewayEvent paymentGatewayEvent = toPaymentGatewayEvent(subscription, env);

        env.contactService().processDonor(paymentGatewayEvent);

        enrich(paymentGatewayEvent, env);

        env.donationService().processDonation(paymentGatewayEvent);
        env.accountingService().processTransaction(paymentGatewayEvent);
      }
      case "BILLING.SUBSCRIPTION.CANCELLED" -> {
        //A billing agreement is canceled.
        String resourceId = getResourceJson(event).getString("id");
        // Calling get Subscription manually to get more details (plan data) than event has
        PaypalClient.Subscription subscription = env.paypalClient().getSubscription(resourceId);

        PaymentGatewayEvent paymentGatewayEvent = toPaymentGatewayEvent(subscription, env);

        env.contactService().processDonor(paymentGatewayEvent);

        enrich(paymentGatewayEvent, env);
        env.donationService().closeRecurringDonation(paymentGatewayEvent);
      }
      default -> env.logJobInfo("unhandled Paypal webhook event type: {}", event.getEventType());
    }
  }

  //TODO: move to gateway service?
  private PaymentGatewayEvent toPaymentGatewayEvent(PaypalClient.Capture capture, Environment env) {
    PaymentGatewayEvent paymentGatewayEvent = new PaymentGatewayEvent(env);
    paymentGatewayEvent.initPaypal(capture);
    return paymentGatewayEvent;
  }

  private PaymentGatewayEvent toPaymentGatewayEvent(PaypalClient.Subscription subscription, Environment env) {
    PaymentGatewayEvent paymentGatewayEvent = new PaymentGatewayEvent(env);
    paymentGatewayEvent.initPaypalSubscription(subscription);
    return paymentGatewayEvent;
  }

  private void enrich(PaymentGatewayEvent paymentGatewayEvent, Environment env)
      throws Exception {
    List<EnrichmentService> enrichmentServices = env.allEnrichmentServices().stream()
        .filter(es -> es.eventIsFromPlatform(paymentGatewayEvent.getCrmDonation())).toList();
    for (EnrichmentService enrichmentService : enrichmentServices) {
      enrichmentService.enrich(paymentGatewayEvent.getCrmDonation());
    }
  }

  private JSONObject getResourceJson(Event event) {
    return new JSONObject(event).getJSONObject("resource");
  }

  private PaypalClient.Capture getCapture(Event event) {
    if (!"capture".equalsIgnoreCase(event.getResourceType())) {
      return null;
    }
    Gson gson = new GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create();
    JSONObject resourceJson = getResourceJson(event);
    return gson.fromJson(resourceJson.toString(), PaypalClient.Capture.class);
  }
}
