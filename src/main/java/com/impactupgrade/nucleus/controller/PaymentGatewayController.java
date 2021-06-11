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
import javax.ws.rs.FormParam;
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

  // TODO: Rethink this method. I'm not excited about getRecurringDonation having to return the gateway-specific
  // subscriptionId -- might be better to instead have the UI/caller pass that info to us...
  @Path("/cancelrecurringdonation")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response cancelRecurringDonation(@FormParam("recurringDonationId") String recurringDonationId,
      @Context HttpServletRequest request) throws Exception {
    SecurityUtil.verifyApiKey(request);

//    Optional<CrmRecurringDonation> recurringDonation = crmSourceService.getRecurringDonation(recurringDonationId);
//    if (recurringDonation.isPresent()) {
//      // TODO: Need to make check and see if the RD actually exists first!
//      // TODO: But I'm not a huge fan of this approach. Think through a better pattern that knows the specific gateway up-front.
//      for (PaymentGatewayService paymentGatewayService : paymentGatewayServices) {
//        paymentGatewayService.cancelSubscription(
//            recurringDonation.get().paymentGatewaySubscriptionId(), recurringDonation.get().paymentGatewayCustomerId());
//      }
      return Response.ok().build();
//    } else {
//      log.warn("{} was not found in the source CRM", recurringDonationId);
//      return Response.status(Response.Status.NOT_FOUND).build();
//    }
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
    SecurityUtil.verifyApiKey(request);
    Environment env = envFactory.init(request, rawFormData.asMap());

    ManageDonationEvent manageDonationEvent = new ManageDonationEvent(formData, env);
    env.donationService().updateRecurringDonation(manageDonationEvent);

    return Response.status(200).build();
  }
}
