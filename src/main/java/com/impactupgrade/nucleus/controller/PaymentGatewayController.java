/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.ManageDonationFormData;
import com.impactupgrade.nucleus.model.PaymentGatewayDeposit;
import com.impactupgrade.nucleus.model.PaymentGatewayTransaction;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.service.segment.PaymentGatewayService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Path("/paymentgateway")
public class PaymentGatewayController {

  private static final Logger log = LogManager.getLogger(PaymentGatewayController.class);

  protected final EnvironmentFactory envFactory;

  public PaymentGatewayController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  /**
   * Provides a list of transactions, powered by all supported payment gateways.
   */
  @Path("/transactions")
  @POST
  @Produces(MediaType.APPLICATION_JSON)
  public Response transactions(
      @FormParam("start") String start,
      @FormParam("end") String end,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    Date startDate = new SimpleDateFormat("yyyy-MM-dd").parse(start);
    Date endDate = new SimpleDateFormat("yyyy-MM-dd").parse(end);

    List<PaymentGatewayService> paymentGatewayServices = env.allPaymentGatewayServices();
    List<PaymentGatewayTransaction> transactions = new ArrayList<>();

    for (PaymentGatewayService paymentGatewayService : paymentGatewayServices) {
      transactions.addAll(paymentGatewayService.getTransactions(startDate, endDate));
    }

    // sorting by-date is more important than by-source for this report (for now)
    transactions = transactions.stream().sorted(Comparator.comparing(PaymentGatewayTransaction::date)).collect(Collectors.toList());

    // passing the date range back so the export can access the original params
    Gson gson = new GsonBuilder().create();
    JSONObject jsonObj = new JSONObject()
            .put("transactions", gson.toJsonTree(transactions))
            .put("startDate", start)
            .put("endDate", end);

    return Response.status(200).entity(gson.toJson(jsonObj)).build();
  }

  /**
   * Provides a list of deposits into checking accounts, powered by all supported payment gateways. Aggregates
   * each deposit's unrestricted vs. restricted giving (sometimes determined by campaign metadata) using net received.
   */
  @Path("/deposits")
  @POST
  @Produces(MediaType.APPLICATION_JSON)
  public Response deposits(
      @FormParam("start") String start,
      @FormParam("end") String end,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    Date startDate = new SimpleDateFormat("yyyy-MM-dd").parse(start);
    Date endDate = new SimpleDateFormat("yyyy-MM-dd").parse(end);

    List<PaymentGatewayService> paymentGatewayServices = env.allPaymentGatewayServices();
    List<PaymentGatewayDeposit> deposits = new ArrayList<>();

    for (PaymentGatewayService paymentGatewayService : paymentGatewayServices) {
      // TODO: This will be in date order, but grouped by payment gateway. Likely ok, but maybe needs grouped by date?
      deposits.addAll(paymentGatewayService.getDeposits(startDate, endDate));
      // TODO: At this point, all we have is what was stored in Stripe. SOME clients will have their funds/campaigns
      //  there. Others will need that backfilled from the CRM. Loop through them all and do so?
    }

    return Response.status(200).entity(deposits).build();
  }

  @Path("/update-recurring-donation")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response updateRecurringDonation(
      @BeanParam ManageDonationFormData formData,      // collected form data for event
      Form rawFormData,                                // raw form data
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request, rawFormData.asMap());
    SecurityUtil.verifyApiKey(env);

    ManageDonationEvent manageDonationEvent = new ManageDonationEvent(formData, env);
    env.donationService().updateRecurringDonation(manageDonationEvent);

    return Response.status(200).build();
  }

  @Path("/verify/charges")
  @GET
  public Response verifyCharges(@Context HttpServletRequest request) throws Exception {
    return verifyCharges(null, null, null, request);
  }

  @Path("/verify/charges")
  @POST
  public Response verifyCharges(
      @FormParam("start") String start,
      @FormParam("end") String end,
      @FormParam("nucleus-username") String nucleusUsername,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);

    Date startDate;
    Date endDate;
    if (Strings.isNullOrEmpty(start)) {
      // If dates were not provided, this was likely a cronjob. Do the last 3 days.
      Calendar startCal = Calendar.getInstance();
      startCal.add(Calendar.HOUR, -72);
      startDate = startCal.getTime();
      endDate = Calendar.getInstance().getTime();
    } else {
      startDate = new SimpleDateFormat("yyyy-MM-dd").parse(start);
      endDate = new SimpleDateFormat("yyyy-MM-dd").parse(end);
    }

    Runnable thread = () -> {
      try {
        String jobName = "Payment Gateway: Verify Charges";
        env.startJobLog(JobType.EVENT, nucleusUsername, jobName, "Nucleus Portal");

        for (PaymentGatewayService paymentGatewayService : env.allPaymentGatewayServices()) {
          // TODO: The results from this could be returned as a CSV...
          paymentGatewayService.verifyCharges(startDate, endDate);
          env.logJobProgress(paymentGatewayService.name() + ": charges verified");
        }
        env.endJobLog(jobName);
      } catch (Exception e) {
        log.error("verifyCharges failed", e);
        env.logJobError(e.getMessage());
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  @Path("/replay/charges")
  @GET
  public Response verifyAndReplayCharges(@Context HttpServletRequest request) throws Exception {
    return verifyAndReplayCharges(null, null, null, request);
  }

  @Path("/replay/charges")
  @POST
  public Response verifyAndReplayCharges(
      @FormParam("start") String start,
      @FormParam("end") String end,
      @FormParam("nucleus-username") String nucleusUsername,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);

    Date startDate;
    Date endDate;
    if (Strings.isNullOrEmpty(start)) {
      // If dates were not provided, this was likely a cronjob. Do the last 3 days.
      Calendar startCal = Calendar.getInstance();
      startCal.add(Calendar.HOUR, -72);
      startDate = startCal.getTime();
      endDate = Calendar.getInstance().getTime();
    } else {
      startDate = new SimpleDateFormat("yyyy-MM-dd").parse(start);
      endDate = new SimpleDateFormat("yyyy-MM-dd").parse(end);
    }

    Runnable thread = () -> {
      try {
        String jobName = "Payment Gateway: Verify/Replay Charges";
        env.startJobLog(JobType.EVENT, nucleusUsername, jobName, "Nucleus Portal");

        for (PaymentGatewayService paymentGatewayService : env.allPaymentGatewayServices()) {
          paymentGatewayService.verifyAndReplayCharges(startDate, endDate);
          env.logJobProgress(paymentGatewayService.name() + ": charges verified and replayed");
        }
        env.endJobLog(jobName);
      } catch (Exception e) {
        log.error("verifyAndReplayCharges failed", e);
        env.logJobError(e.getMessage());
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  @Path("/replay/deposits")
  @GET
  public Response verifyAndReplayDeposits(@Context HttpServletRequest request) throws Exception {
    return verifyAndReplayDeposits(null, null, null, request);
  }

  @Path("/replay/deposits")
  @POST
  public Response verifyAndReplayDeposits(
      @FormParam("start") String start,
      @FormParam("end") String end,
      @FormParam("nucleus-username") String nucleusUsername,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);

    Date startDate;
    Date endDate;
    if (Strings.isNullOrEmpty(start)) {
      // If dates were not provided, this was likely a cronjob. Do the last 3 days.
      Calendar startCal = Calendar.getInstance();
      startCal.add(Calendar.HOUR, -72);
      startDate = startCal.getTime();
      endDate = Calendar.getInstance().getTime();
    } else {
      startDate = new SimpleDateFormat("yyyy-MM-dd").parse(start);
      endDate = new SimpleDateFormat("yyyy-MM-dd").parse(end);
    }

    Runnable thread = () -> {
      try {
        String jobName = "Payment Gateway: Verify/Replay Deposits";
        env.startJobLog(JobType.EVENT, nucleusUsername, jobName, "Nucleus Portal");

        for (PaymentGatewayService paymentGatewayService : env.allPaymentGatewayServices()) {
          paymentGatewayService.verifyAndReplayDeposits(startDate, endDate);
          env.logJobProgress(paymentGatewayService.name() + ": deposits verified and replayed");
        }

        env.endJobLog(jobName);
      } catch (Exception e) {
        log.error("verifyAndReplayDeposits failed", e);
        env.logJobError(e.getMessage());
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }
}
