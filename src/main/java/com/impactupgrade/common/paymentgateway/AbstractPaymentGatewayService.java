package com.impactupgrade.common.paymentgateway;

import com.google.common.base.Strings;
import com.impactupgrade.common.paymentgateway.paymentspring.PaymentSpringClientFactory;
import com.impactupgrade.common.paymentgateway.stripe.StripeClient;
import com.impactupgrade.common.security.SecurityUtil;
import com.impactupgrade.common.sfdc.SFDCClient;
import com.impactupgrade.integration.paymentspring.model.Subscription;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import com.stripe.exception.StripeException;
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
import java.util.Optional;
import java.util.function.BiConsumer;

@Path("/paymentgateway")
public abstract class AbstractPaymentGatewayService {

  private static final Logger log = LogManager.getLogger(AbstractPaymentGatewayService.class);

  private static final SFDCClient sfdcClient = new SFDCClient();

  @Path("/cancelrecurringdonation")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response cancelRecurringDonation(@FormParam("recurringDonationId") String recurringDonationId,
      @Context HttpServletRequest request) throws ConnectionException, InterruptedException {
    SecurityUtil.verifyApiKey(request);

    return processSubscription(
        recurringDonationId,
        (stripeSubscriptionId, stripeCustomerId) -> {
          try {
            // this will trigger a webhook to be sent from Stripe to SFDC to clear out the donation
            StripeClient.cancelSubscription(stripeSubscriptionId, StripeClient.defaultRequestOptions());
          } catch (StripeException e) {
            throw new RuntimeException(e);
          }
        },
        (paymentspringSubscriptionId, paymentspringCustomerId) -> {
          log.info("canceling PaymentSpring subscription {} for customer {}...",
              paymentspringSubscriptionId, paymentspringCustomerId);
          Subscription subscription = PaymentSpringClientFactory.client().subscriptions().getById(
              paymentspringSubscriptionId, paymentspringCustomerId);
          String planId = subscription.getPlanId();
          PaymentSpringClientFactory.client().plans().unsubscribe(planId, paymentspringCustomerId);
          log.info("canceled PaymentSpring subscription {} for customer {}",
              paymentspringSubscriptionId, paymentspringCustomerId);
        }
    );
  }

  /**
   * Use a bit of FP to act upon a given recurring donation, using passed functions to handle each payment gateway.
   *
   * @param recurringDonationId
   * @param stripeAction
   * @param paymentspringAction
   * @return
   * @throws ConnectionException
   * @throws InterruptedException
   */
  private Response processSubscription(String recurringDonationId, BiConsumer<String, String> stripeAction,
      BiConsumer<String, String> paymentspringAction) throws ConnectionException, InterruptedException {
    Optional<SObject> recurringDonationSO = sfdcClient.getRecurringDonationById(recurringDonationId);
    if (recurringDonationSO.isPresent()) {
      SObject recurringDonation = recurringDonationSO.get();
      if (!Strings.isNullOrEmpty(getStripeSubscriptionIdFromRecurringDonation(recurringDonation))) {
        stripeAction.accept(getStripeSubscriptionIdFromRecurringDonation(recurringDonation), getStripeCustomerIdFromRecurringDonation(recurringDonation));
        return Response.ok().build();
      } else if (!Strings.isNullOrEmpty(getPaymentSpringSubscriptionIdFromRecurringDonation(recurringDonation))) {
        paymentspringAction.accept(getPaymentSpringSubscriptionIdFromRecurringDonation(recurringDonation), getPaymentSpringCustomerIdFromRecurringDonation(recurringDonation));
        return Response.ok().build();
      }
    }

    log.warn("{} does not have a supported payment gateway subscription id", recurringDonationId);
    return Response.status(Response.Status.NOT_FOUND).build();
  }

  protected abstract String getStripeCustomerIdFromRecurringDonation(SObject recurringDonation);
  protected abstract String getStripeSubscriptionIdFromRecurringDonation(SObject recurringDonation);
  protected abstract String getPaymentSpringCustomerIdFromRecurringDonation(SObject recurringDonation);
  protected abstract String getPaymentSpringSubscriptionIdFromRecurringDonation(SObject recurringDonation);
}
