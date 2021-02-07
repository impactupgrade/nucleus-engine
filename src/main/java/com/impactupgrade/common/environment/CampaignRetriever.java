package com.impactupgrade.common.environment;

import com.google.common.base.Strings;
import com.impactupgrade.common.paymentgateway.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Subscription;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// TODO: PaymentSpring? Or keep that in LJI/TER and hope it goes away?
// TODO: Move to the paymentgateway package and make it abstract?
// TODO: Could this be replaced by overriding services?
public class CampaignRetriever {

  private static final Logger log = LogManager.getLogger(CampaignRetriever.class);

  private final StripeClient stripeClient;
  private final String[] metadataKeys;
  private Charge stripeCharge = null;
  private PaymentIntent stripePaymentIntent = null;
  private Subscription stripeSubscription = null;
  private Customer stripeCustomer = null;

  public CampaignRetriever(Environment env, RequestEnvironment requestEnv) {
    stripeClient = requestEnv.stripeClient();
    this.metadataKeys = env.campaignMetadataKeys();
  }

  public CampaignRetriever stripeCharge(Charge stripeCharge) {
    this.stripeCharge = stripeCharge;
    return this;
  }
  public CampaignRetriever stripePaymentIntent(PaymentIntent stripePaymentIntent) {
    this.stripePaymentIntent = stripePaymentIntent;
    return this;
  }
  public CampaignRetriever stripeSubscription(Subscription stripeSubscription) {
    this.stripeSubscription = stripeSubscription;
    return this;
  }
  public CampaignRetriever stripeCustomer(Customer stripeCustomer) {
    this.stripeCustomer = stripeCustomer;
    return this;
  }

  public String getCampaign() {
    String campaignId = null;

    for (String metadataKey : metadataKeys) {
      if (stripePaymentIntent != null) {
        campaignId = stripePaymentIntent.getMetadata().get(metadataKey);
        if (!Strings.isNullOrEmpty(campaignId)) break;

        if (stripePaymentIntent.getCustomerObject() != null) {
          campaignId = stripePaymentIntent.getCustomerObject().getMetadata().get(metadataKey);
          if (!Strings.isNullOrEmpty(campaignId)) break;
        }
      }

      if (stripeCharge != null) {
        campaignId = stripeCharge.getMetadata().get(metadataKey);
        if (!Strings.isNullOrEmpty(campaignId)) break;

        // If the campaign isn't on the charge, try pulling the intent from the API. There are cases where the intent
        // may not have been passed into CampaignRetriever explicitly. Ex: getting transactions from a payout.
        if (!Strings.isNullOrEmpty(stripeCharge.getPaymentIntent())) {
          try {
            PaymentIntent paymentIntent = stripeClient.getPaymentIntent(stripeCharge.getPaymentIntent());
            campaignId = paymentIntent.getMetadata().get(metadataKey);
            if (!Strings.isNullOrEmpty(campaignId)) break;
          } catch (StripeException e) {
            // For now, don't let this become a checked exception...
            log.error("unable to retrieve PaymentIntent", e);
          }
        }

        if (stripeCharge.getCustomerObject() != null) {
          campaignId = stripeCharge.getCustomerObject().getMetadata().get(metadataKey);
          if (!Strings.isNullOrEmpty(campaignId)) break;
        }
      }

      if (stripeSubscription != null) {
        campaignId = stripeSubscription.getMetadata().get(metadataKey);
        if (!Strings.isNullOrEmpty(campaignId)) break;

        if (stripeSubscription.getCustomerObject() != null) {
          campaignId = stripeSubscription.getCustomerObject().getMetadata().get(metadataKey);
          if (!Strings.isNullOrEmpty(campaignId)) break;
        }
      }

      if (stripeCustomer != null) {
        campaignId = stripeCustomer.getMetadata().get(metadataKey);
        if (!Strings.isNullOrEmpty(campaignId)) break;
      }

      // TODO: If the stripeCustomer isn't explicitly given, and it wasn't expanded in the charge/intent/subscription,
      // should we try to pull it from the API using the charge/intent/subscription's customerId?
      // Perf hit, but the ultimate fallback.
    }

    if (campaignId != null) {
      // IMPORTANT: The designation code is copy/pasted by a human and we've had issues with whitespace. Strip it!
      campaignId = campaignId.replaceAll("[^A-Za-z0-9_-]", "");
    }

    return campaignId;
  }
}
