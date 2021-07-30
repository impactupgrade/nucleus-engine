/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.ManageDonationFormData;
import com.impactupgrade.nucleus.security.SecurityUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/paymentgateway")
public class PaymentGatewayController {

  private static final Logger log = LogManager.getLogger(PaymentGatewayController.class);

  protected final EnvironmentFactory envFactory;

  public PaymentGatewayController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
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
