package com.impactupgrade.common.paymentgateway;

import com.impactupgrade.common.crm.CrmSourceService;
import com.impactupgrade.common.environment.Environment;
import com.impactupgrade.common.security.SecurityUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/paymentgateway")
public class PaymentGatewayController {

  private static final Logger log = LogManager.getLogger(PaymentGatewayController.class);

  protected final Environment env;
  protected final CrmSourceService crmSourceService;

  public PaymentGatewayController(Environment env) {
    this.env = env;
    crmSourceService = env.crmSourceService();
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
}
