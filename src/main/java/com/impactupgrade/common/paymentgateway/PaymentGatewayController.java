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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

@Path("/paymentgateway")
public class PaymentGatewayController {

  private static final Logger log = LogManager.getLogger(PaymentGatewayController.class);

  protected final Environment env;
  private final SFDCClient sfdcClient;
  private final StripeClient stripeClient;

  public PaymentGatewayController(Environment env) {
    this.env = env;
    sfdcClient = new SFDCClient(env);
    stripeClient = new StripeClient(env);
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

    // Keep a cache of parent campaigns we visit, just to ease hits on the SFDC API.
    Map<String, Optional<SObject>> campaignCache = new HashMap<>();

    // STRIPE

    List<PaymentGatewayDeposit> deposits = new ArrayList<>();
    List<Payout> payouts = stripeClient.getPayouts(start, end, 100);
    for (Payout payout : payouts) {
      PaymentGatewayDeposit deposit = new PaymentGatewayDeposit();
      deposit.setUrl("https://dashboard.stripe.com/payouts/" + payout.getId());
      Calendar c = Calendar.getInstance();
      c.setTimeInMillis(payout.getArrivalDate() * 1000);
      deposit.setDate(c);

      List<SObject> opps = sfdcClient.getDonationsInDeposit(payout.getId());
      for (SObject opp : opps) {
        double gross = Double.parseDouble(opp.getField("Amount").toString());
        double net = Double.parseDouble(opp.getField(env.sfdcFieldOppDepositNet()).toString());
        if (opp.getField("CampaignId") != null && opp.getField("CampaignId").toString().length() > 0) {
          String campaignId = opp.getField("CampaignId").toString();
          Optional<SObject> campaign = findCampaign(campaignId, campaignCache);
          Optional<SObject> parentCampaign = findParentCampaign(campaign, campaignCache);

          if (campaign.isPresent()) {
            if (parentCampaign.isPresent()) {
              deposit.addTransaction(gross, net, parentCampaign.get().getId(), parentCampaign.get().getField("Name").toString(),
                  campaignId, campaign.get().getField("Name").toString());
            } else {
              deposit.addTransaction(gross, net, campaignId, campaign.get().getField("Name").toString());
            }
          }
        }
      }

      deposits.add(deposit);
    }

    // TODO: OTHERS

    return Response.status(200).entity(deposits).build();
  }

  private Optional<SObject> findCampaign(String campaignId, Map<String, Optional<SObject>> campaignCache) throws ConnectionException, InterruptedException {
    if (campaignCache.containsKey(campaignId)) {
      return campaignCache.get(campaignId);
    }

    log.info("campaign {} not cached; visiting...", campaignId);
    Optional<SObject> campaign = sfdcClient.getCampaignById(campaignId);
    campaignCache.put(campaignId, campaign);
    return campaign;
  }

  private Optional<SObject> findParentCampaign(Optional<SObject> campaign, Map<String, Optional<SObject>> campaignCache) throws ConnectionException, InterruptedException {
    if (campaign.isEmpty()) {
      return campaign;
    }

    if (campaign.get().getField("ParentId") == null || campaign.get().getField("ParentId").toString().isEmpty()) {
      // reached the top of the tree
      return campaign;
    }

    String parentId = campaign.get().getField("ParentId").toString();

    Optional<SObject> parentCampaign;
    if (campaignCache.containsKey(parentId)) {
      parentCampaign = campaignCache.get(parentId);
    } else {
      log.info("campaign {} not cached; visiting...", parentId);
      parentCampaign = sfdcClient.getCampaignById(parentId);
      campaignCache.put(parentId, parentCampaign);
    }

    if (parentCampaign.isPresent()) {
      return findParentCampaign(parentCampaign, campaignCache);
    } else {
      return Optional.empty();
    }
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
            stripeClient.cancelSubscription(stripeSubscriptionId);
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
}
