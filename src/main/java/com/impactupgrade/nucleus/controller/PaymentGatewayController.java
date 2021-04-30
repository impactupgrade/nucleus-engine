package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.ManageDonationFormData;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.service.logic.DonationService;
import com.impactupgrade.nucleus.service.segment.CrmService;

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
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/paymentgateway")
public class PaymentGatewayController {

  private static final Logger log = LogManager.getLogger(PaymentGatewayController.class);

  protected final Environment env;
  protected final CrmService crmService;
  protected final DonationService donationService;

  public PaymentGatewayController(Environment env) {
    this.env = env;
    crmService = env.crmService();
    donationService = env.donationService();
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

  @Path("/update-recurring-donation-amount")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response updateRecurringDonationAmount(
      @BeanParam ManageDonationFormData formData,
      @Context HttpServletRequest request
  ) throws Exception {
    SecurityUtil.verifyApiKey(request);
    final Environment.RequestEnvironment requestEnv = env.newRequestEnvironment(request);

    ManageDonationEvent manageDonationEvent = new ManageDonationEvent(requestEnv, formData);
    donationService.updateRecurringDonation(manageDonationEvent);

    return Response.status(200).build();
  }

  @Path("/update-recurring-donation-payment-method")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response updateRecurringDonationPaymentMethod(
      @FormParam("rd-id") String recurringDonationId,
      @FormParam("amount") Double amount,
      @Context HttpServletRequest request
  ) throws Exception {
    SecurityUtil.verifyApiKey(request);

    final Environment.RequestEnvironment requestEnv = env.newRequestEnvironment(request);

    // TODO: helper method taking form data -> ManageDonationEvent
    ManageDonationEvent manageDonationEvent = new ManageDonationEvent(requestEnv);
    manageDonationEvent.setDonationId(recurringDonationId);
    manageDonationEvent.setAmount(amount);

    donationService.updateRecurringDonation(manageDonationEvent);

    return Response.status(200).build();
  }
}
