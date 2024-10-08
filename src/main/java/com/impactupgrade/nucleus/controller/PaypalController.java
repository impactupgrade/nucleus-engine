/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

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
import com.impactupgrade.nucleus.util.TestUtil;
import com.paypal.api.payments.Event;
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

    String jobName = "Paypal Event";
    env.startJobLog(JobType.EVENT, "webhook", jobName, "Paypal");
    env.logJobInfo("received event from Paypal");
    env.logJobInfo("json: {}", json);
    env.logJobInfo("headers: {}", getHeadersInfo(request));

    boolean isValid = isValidWebhookRequest(request, json, env);
    if (!isValid) {
      env.logJobError("Paypal data was invalid!");
      env.endJobLog(JobStatus.FAILED);
      return Response.status(400).build();
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
          processEvent(event, env);
          env.endJobLog(JobStatus.DONE);
        } catch (Exception e) {
          env.logJobError("failed to process the Paypal event: {}", e.getMessage(), e);
          env.endJobLog(JobStatus.FAILED);
        }
      };
      new Thread(thread).start();
    }

    return Response.status(200).build();
  }

  private boolean isValidWebhookRequest(HttpServletRequest request, String requestBody, Environment env) throws Exception {
    return env.paypalClient().isValidWebhookData(
            request.getHeader("Paypal-Transmission-Id"), request.getHeader("Paypal-Transmission-Time"),
            request.getHeader("Paypal-Cert-Url"), request.getHeader("Paypal-Auth-Algo"), request.getHeader("Paypal-Transmission-Sig"),
            env.getConfig().paypal.webhookId, requestBody);
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
        String eventType = event.getEventType();
        env.logJobInfo("Got event type: '" + eventType + "'");
        env.logJobInfo("Got payment gateway event: ", paymentGatewayEvent);

        // must first process the account/contact so they're available
        env.logJobInfo("Processing donor for '" + eventType + "' event...");
        //env.contactService().processDonor(paymentGatewayEvent);
        env.logJobInfo("Donor processed for '" + eventType + "' event...");

        env.logJobInfo("Creating donation for '" + eventType + "' event...");
        //env.donationService().createDonation(paymentGatewayEvent);
        env.logJobInfo("Donation created for '" + eventType + "' event.");
        env.logJobInfo("Processing transaction for '" + eventType + "' event...");
        //env.accountingService().processTransaction(paymentGatewayEvent);
        env.logJobInfo("Transaction processed for '" + eventType + "' event.");
      }
      case "PAYMENT.CAPTURE.DECLINED" -> {
        //A payment capture is declined.
        PaypalClient.Capture capture = getCapture(event);

        PaymentGatewayEvent paymentGatewayEvent = toPaymentGatewayEvent(capture, env);
        String eventType = event.getEventType();
        env.logJobInfo("Got event type: '" + eventType + "'");
        env.logJobInfo("Got payment gateway event: ", paymentGatewayEvent);

        env.logJobInfo("Processing donor for '" + eventType + "' event...");
        //env.contactService().processDonor(paymentGatewayEvent);
        env.logJobInfo("Donor processed for '" + eventType + "' event...");
        env.logJobInfo("Creating donation for '" + eventType + "' event...");
        //env.donationService().createDonation(paymentGatewayEvent);
        env.logJobInfo("Donation created for '" + eventType + "' event.");
      }
      case "PAYMENT.CAPTURE.REFUNDED" -> {
        //A merchant refunds a payment capture.
        PaypalClient.Capture capture = getCapture(event);

        PaymentGatewayEvent paymentGatewayEvent = toPaymentGatewayEvent(capture, env);
        String eventType = event.getEventType();
        env.logJobInfo("Got event type: '" + eventType + "'");
        env.logJobInfo("Got payment gateway event: ", new Gson().toJson(paymentGatewayEvent));

        env.logJobInfo("Refunding donation for '" + eventType + "' event...");
        //env.donationService().refundDonation(paymentGatewayEvent);
        env.logJobInfo("Donation refunded for '" + eventType + "' event.");
      }
      case "BILLING.SUBSCRIPTION.CREATED" -> {
        //A billing agreement is created.
        String resourceId = getResourceJson(event).getString("id");
        // Calling get Subscription manually to get more details (plan data) than event has
        PaypalClient.Subscription subscription = env.paypalClient().getSubscription(resourceId);

        PaymentGatewayEvent paymentGatewayEvent = toPaymentGatewayEvent(subscription, env);
        String eventType = event.getEventType();
        env.logJobInfo("Got event type: '" + eventType + "'");
        env.logJobInfo("Got payment gateway event: ", paymentGatewayEvent);

        env.logJobInfo("Processing donor for '" + eventType + "' event...");
        //env.contactService().processDonor(paymentGatewayEvent);
        env.logJobInfo("Donor processed for '" + eventType + "' event...");

        env.logJobInfo("Creating donation for '" + eventType + "' event...");
        //env.donationService().createDonation(paymentGatewayEvent);
        env.logJobInfo("Donation created for '" + eventType + "' event.");
        env.logJobInfo("Processing transaction for '" + eventType + "' event...");
        //env.accountingService().processTransaction(paymentGatewayEvent);
        env.logJobInfo("Transaction processed for '" + eventType + "' event.");
      }
      case "BILLING.SUBSCRIPTION.CANCELLED" -> {
        //A billing agreement is canceled.
        String resourceId = getResourceJson(event).getString("id");
        // Calling get Subscription manually to get more details (plan data) than event has
        PaypalClient.Subscription subscription = env.paypalClient().getSubscription(resourceId);

        PaymentGatewayEvent paymentGatewayEvent = toPaymentGatewayEvent(subscription, env);
        String eventType = event.getEventType();

        env.logJobInfo("Got event type: '" + eventType + "'");
        env.logJobInfo("Got payment gateway event: ", new Gson().toJson(paymentGatewayEvent));

        env.logJobInfo("Processing donor for '" + eventType + "' event...");
        //env.contactService().processDonor(paymentGatewayEvent);
        env.logJobInfo("Donor processed for '" + eventType + "' event...");

        env.logJobInfo("Closing recurring donation for '" + eventType + "' event...");
        //env.donationService().closeRecurringDonation(paymentGatewayEvent);
        env.logJobInfo("Recurring donation closed for '" + eventType + "' event.");
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
