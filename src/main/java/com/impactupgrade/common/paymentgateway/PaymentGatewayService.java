package com.impactupgrade.common.paymentgateway;

import com.google.common.base.Strings;
import com.impactupgrade.common.environment.Environment;
import com.impactupgrade.common.paymentgateway.paymentspring.PaymentSpringClientFactory;
import com.impactupgrade.common.paymentgateway.stripe.StripeClient;
import com.impactupgrade.common.security.SecurityUtil;
import com.impactupgrade.common.sfdc.SFDCClient;
import com.impactupgrade.integration.paymentspring.model.Subscription;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import com.stripe.exception.StripeException;
import com.stripe.model.Payout;
import com.stripe.net.RequestOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

@Path("/paymentgateway")
public class PaymentGatewayService {

  private static final Logger log = LogManager.getLogger(PaymentGatewayService.class);

  protected final Environment env;
  private final SFDCClient sfdcClient;

  public PaymentGatewayService(Environment env) {
    this.env = env;
    sfdcClient = new SFDCClient(env);
  }

  /**
   * Provides a list of deposits into checking accounts, powered by all supported payment gateways. Aggregates
   * each deposit's unrestricted vs. restricted giving (generally determined by campaign metadata) using net received.
   */
  @Path("/deposits")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response deposits(
      @QueryParam("start") long startMillis,
      @QueryParam("end") long endMillis,
      @Context HttpServletRequest request
  ) throws StripeException, ConnectionException, InterruptedException {
    SecurityUtil.verifyApiKey(request);

    Date start = new Date(startMillis);
    Date end = new Date(endMillis);

    // STRIPE

    List<PaymentGatewayDeposit> deposits = new ArrayList<>();
    List<Payout> payouts = StripeClient.getPayouts(start, end, 100, getStripeRequestOptions());
    for (Payout payout : payouts) {
      Calendar c = Calendar.getInstance();
      c.setTimeInMillis(payout.getArrivalDate() * 1000);
      PaymentGatewayDeposit deposit = new PaymentGatewayDeposit(c);

      List<SObject> opps = sfdcClient.getDonationsInDeposit(payout.getId());
      for (SObject opp : opps) {
        double amount = Double.parseDouble(opp.getField(env.sfdcFieldOppDepositNet()).toString());
        String campaignId = opp.getField("CampaignId").toString();
        deposit.addTransaction(amount, campaignId);
      }
      deposits.add(deposit);
    }

    // TODO: OTHERS

    return Response.status(200).entity(deposits).build();
  }

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
      if (!Strings.isNullOrEmpty(env.getStripeSubscriptionIdFromRecurringDonation(recurringDonation))) {
        stripeAction.accept(env.getStripeSubscriptionIdFromRecurringDonation(recurringDonation), env.getStripeCustomerIdFromRecurringDonation(recurringDonation));
        return Response.ok().build();
      } else if (!Strings.isNullOrEmpty(env.getPaymentSpringSubscriptionIdFromRecurringDonation(recurringDonation))) {
        paymentspringAction.accept(env.getPaymentSpringSubscriptionIdFromRecurringDonation(recurringDonation), env.getPaymentSpringCustomerIdFromRecurringDonation(recurringDonation));
        return Response.ok().build();
      }
    }

    log.warn("{} does not have a supported payment gateway subscription id", recurringDonationId);
    return Response.status(Response.Status.NOT_FOUND).build();
  }

  // TODO: This likely won't work for DR. Instead need to let the endpoint method be overloaded...
  protected RequestOptions getStripeRequestOptions() {
    return StripeClient.defaultRequestOptions();
  }
}
