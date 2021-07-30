/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.impactupgrade.integration.paymentspring.model.Customer;
import com.impactupgrade.integration.paymentspring.model.Event;
import com.impactupgrade.integration.paymentspring.model.Subscription;
import com.impactupgrade.integration.paymentspring.model.Transaction;
import com.impactupgrade.nucleus.client.PaymentSpringClientFactory;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.PaymentGatewayWebhookEvent;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.sforce.soap.partner.sobject.SObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * This service acts as the central webhook endpoint for PaymentSpring events, handling everything from
 * successful/failed charges, subscription changes, etc. It also houses anything PaymentSpring-specific called by the
 * Portal UI.
 */
@Path("/paymentspring")
public class PaymentSpringController {

  private static final Logger log = LogManager.getLogger(PaymentSpringController.class);

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final ObjectMapper objectMapper = new ObjectMapper();

  protected final EnvironmentFactory envFactory;

  public PaymentSpringController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  /**
   * Receives and processes *all* webhooks from PaymentSpring.
   *
   * @param json
   * @return
   */
  @Path("/webhook")
  @POST
  @Produces(MediaType.APPLICATION_JSON)
  public Response webhook(String json, @Context HttpServletRequest request) {
    // PaymentSpring is so bloody difficult to replay. For now, as we're fixing issues, always log the raw requests.
//    LoggingUtil.verbose(log, json);
    log.info(json);
    Environment env = envFactory.init(request);

    Event event;
    try {
      event = objectMapper.readValue(json, Event.class);
    } catch (IOException e) {
      log.error("PaymentSpring deserialization failed", e);
      return Response.status(500).build();
    }

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      // don't log the whole thing -- can be found in PaymentSpring's dashboard
      // log this within the new thread for traceability's sake
      log.info("received event {}: {}", event.getEventResource(), event.getEventType());

      try {
        PaymentGatewayWebhookEvent paymentGatewayEvent = new PaymentGatewayWebhookEvent(env);

        switch (event.getEventResource()) {
          case "transaction":
            Transaction transaction = objectMapper.readValue(event.getPayloadJson().toString(), Transaction.class);
            processTransaction(event, transaction, paymentGatewayEvent, env);
            break;
          case "subscription":
            switch (event.getEventType()) {
              case "created":
                // NOTE: We currently do nothing with this. Unlike Stripe, PaymentSpring does not support
                // a "trialing" concept, or we're not using it. Either way, no need to create the recurring donation
                // ahead of time. Just let the first transaction do it.
                break;
              case "destroyed":
                Subscription subscription = objectMapper.readValue(event.getPayloadJson().toString(), Subscription.class);
                log.info("found subscription {}", subscription.getId());

                paymentGatewayEvent.initPaymentSpring(subscription);

                env.donationService().closeRecurringDonation(paymentGatewayEvent);
                break;
              default:
                log.info("unhandled PaymentSpring subscription event type: {}", event.getEventType());
            }
            break;
          default:
            log.info("unhandled PaymentSpring event resource: {}", event.getEventResource());
        }
      } catch (Exception e) {
        log.error("failed to process the PaymentSpring event", e);
        // TODO: email notification?
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  private void processTransaction(Event event, Transaction transaction, PaymentGatewayWebhookEvent paymentGatewayEvent,
      Environment env) throws Exception {
    log.info("found transaction {}", transaction.getId());

    Optional<Customer> transactionCustomer;
    if (Strings.isNullOrEmpty(transaction.getCustomerId())) {
      transactionCustomer = Optional.empty();
    } else {
      transactionCustomer = Optional.of(PaymentSpringClientFactory.client(env).customers().getById(transaction.getCustomerId()));
      log.info("found customer {}", transactionCustomer.get().getId());
    }

    Optional<Subscription> transactionSubscription;
    if (!transaction.getMetadata().containsKey("plan_id")
        || Strings.isNullOrEmpty(transaction.getMetadata().get("plan_id"))) {
      transactionSubscription = Optional.empty();
    } else {
      String transactionPlanId = transaction.getMetadata().get("plan_id");
      transactionSubscription = Optional.of(PaymentSpringClientFactory.client(env).subscriptions().getByPlanId(
          transactionPlanId, transaction.getCustomerId()));
      log.info("found subscription {}", transactionSubscription.get().getId());
    }

    paymentGatewayEvent.initPaymentSpring(transaction, transactionCustomer, transactionSubscription);

    switch (event.getEventType()) {
      case "created" -> {
        env.donorService().processAccount(paymentGatewayEvent);
        env.donationService().createDonation(paymentGatewayEvent);
      }
      case "failed" -> {
        env.donorService().processAccount(paymentGatewayEvent);
        env.donationService().createDonation(paymentGatewayEvent);
      }
      case "refunded" -> env.donationService().refundDonation(paymentGatewayEvent);
      default -> log.info("unhandled PaymentSpring transaction event type: {}", event.getEventType());
    }
  }

  @Path("/failed-transactions")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response failedTransactions(
      @FormParam("start_date") String startDate,
      @FormParam("end_date") String endDate,
      @Context HttpServletRequest request
  ) {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    // Sort failed transactions list by created datetime
    List<Transaction> sortedList = PaymentSpringClientFactory.client(env).transactions().getTransactionsBetweenDates(startDate, endDate).stream()
        // Grab only the failed transactions from list
        .filter(t -> t.getAmountFailed() > 0)
        .sorted(Comparator.comparing(Transaction::getCreatedAt).reversed())
        .collect(Collectors.toList());

    String jsonList = GSON.toJson(sortedList);

    return Response.ok().entity(jsonList).type(MediaType.APPLICATION_JSON).build();
  }

  // TODO: To be wrapped in a REST call for the UI to kick off, etc.
  public void verifyAndReplayPaymentSpringCharges(String startDate, String endDate, Environment env) {
    SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
    List<Transaction> charges = PaymentSpringClientFactory.client(env).transactions().getTransactionsBetweenDates(startDate, endDate);
    int count = 0;
    for (Transaction charge : charges) {
//      if (!charge.getStatus().equalsIgnoreCase("succeeded")) {
//        continue;
//      }

      count++;

      try {
        String chargeId = charge.getId();
        Optional<SObject> opportunity = Optional.empty();
        if (!Strings.isNullOrEmpty(chargeId)) {
          // TODO: Needs pulled to CrmService
          opportunity = env.sfdcClient().getDonationByTransactionId(chargeId);
        }

        if (opportunity.isEmpty()) {
          System.out.println("(" + count + ") MISSING: " + chargeId + " " + SDF.format(charge.getCreatedAt()));

          Event event = new Event();
          event.setEventType("created");
          processTransaction(event, charge, new PaymentGatewayWebhookEvent(env), env);
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }
}
