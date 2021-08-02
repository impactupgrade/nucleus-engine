/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.ManageDonationFormData;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.service.logic.DonationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Controller;

import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Controller
@Path("/api/paymentgateway")
public class PaymentGatewayController {

  private static final Logger log = LogManager.getLogger(PaymentGatewayController.class);

  protected final Environment env;
  protected final DonationService donationService;

  public PaymentGatewayController(Environment env, DonationService donationService) {
    this.env = env;
    this.donationService = donationService;
  }

  @Path("/update-recurring-donation")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response updateRecurringDonation(
      @BeanParam ManageDonationFormData formData
  ) throws Exception {
    SecurityUtil.verifyApiKey(env);

    ManageDonationEvent manageDonationEvent = new ManageDonationEvent(formData, env);
    donationService.updateRecurringDonation(manageDonationEvent);

    return Response.status(200).build();
  }
}
