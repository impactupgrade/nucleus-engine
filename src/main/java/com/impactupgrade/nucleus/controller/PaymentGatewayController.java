/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

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

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

    return Response.status(200).entity(transactions).build();
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
}
