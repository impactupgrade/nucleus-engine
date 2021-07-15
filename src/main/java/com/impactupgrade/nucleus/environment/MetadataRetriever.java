/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.environment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Subscription;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// TODO: PaymentSpring? Or keep that in LJI/TER and hope it goes away?
// TODO: Move to the paymentgateway package and make it abstract?
// TODO: Could this be replaced by overriding services?
public class MetadataRetriever {

  private static final Logger log = LogManager.getLogger(MetadataRetriever.class);

  // Allow unique circumstances (one-off vendors, etc.) to provide raw context as needed.
  private final Map<String, String> rawContext = new HashMap<>();

  private final StripeClient stripeClient;
  private Charge stripeCharge = null;
  private PaymentIntent stripePaymentIntent = null;
  private Subscription stripeSubscription = null;
  private Customer stripeCustomer = null;

  public MetadataRetriever(Environment env) {
    stripeClient = env.stripeClient();
  }

  public MetadataRetriever rawContext(String k, String v) {
    rawContext.put(k, v);
    return this;
  }

  public MetadataRetriever stripeCharge(Charge stripeCharge) {
    this.stripeCharge = stripeCharge;
    return this;
  }
  public MetadataRetriever stripePaymentIntent(PaymentIntent stripePaymentIntent) {
    this.stripePaymentIntent = stripePaymentIntent;
    return this;
  }
  public MetadataRetriever stripeSubscription(Subscription stripeSubscription) {
    this.stripeSubscription = stripeSubscription;
    return this;
  }
  public MetadataRetriever stripeCustomer(Customer stripeCustomer) {
    this.stripeCustomer = stripeCustomer;
    return this;
  }

  public String getMetadataValue(String metadataKey) {
    return getMetadataValue(Set.of(metadataKey));
  }

  public String getMetadataValue(Collection<String> metadataKeys) {
    String metadataValue = null;

    for (String metadataKey : metadataKeys) {
      // Always start with the raw context and let it trump everything else.
      if (rawContext.containsKey(metadataKey) && !Strings.isNullOrEmpty(rawContext.get(metadataKey))) {
        return rawContext.get(metadataKey);
      }

      // TODO: The following looks off. Shouldn't these be in an order of precedent and return if a value is found?

      if (stripePaymentIntent != null) {
        metadataValue = stripePaymentIntent.getMetadata().get(metadataKey);
        if (!Strings.isNullOrEmpty(metadataValue)) break;

        if (stripePaymentIntent.getCustomerObject() != null) {
          metadataValue = stripePaymentIntent.getCustomerObject().getMetadata().get(metadataKey);
          if (!Strings.isNullOrEmpty(metadataValue)) break;
        }
      }

      if (stripeCharge != null) {
        metadataValue = stripeCharge.getMetadata().get(metadataKey);
        if (!Strings.isNullOrEmpty(metadataValue)) break;

        // If the key isn't on the charge, try pulling the intent from the API. There are cases where the intent
        // may not have been passed into MetadataRetriever explicitly. Ex: getting transactions from a payout.
        if (!Strings.isNullOrEmpty(stripeCharge.getPaymentIntent())) {
          try {
            // TODO: If this is retrieved, cache it?
            stripePaymentIntent = stripeClient.getPaymentIntent(stripeCharge.getPaymentIntent());
            metadataValue = stripePaymentIntent.getMetadata().get(metadataKey);
            if (!Strings.isNullOrEmpty(metadataValue)) break;
          } catch (StripeException e) {
            // For now, don't let this become a checked exception...
            log.error("unable to retrieve PaymentIntent", e);
          }
        }

        if (stripeCharge.getCustomerObject() != null) {
          metadataValue = stripeCharge.getCustomerObject().getMetadata().get(metadataKey);
          if (!Strings.isNullOrEmpty(metadataValue)) break;
        }
      }

      if (stripeSubscription != null) {
        metadataValue = stripeSubscription.getMetadata().get(metadataKey);
        if (!Strings.isNullOrEmpty(metadataValue)) break;

        if (stripeSubscription.getCustomerObject() != null) {
          metadataValue = stripeSubscription.getCustomerObject().getMetadata().get(metadataKey);
          if (!Strings.isNullOrEmpty(metadataValue)) break;
        }
      }

      if (stripeCustomer != null) {
        metadataValue = stripeCustomer.getMetadata().get(metadataKey);
        if (!Strings.isNullOrEmpty(metadataValue)) break;
      }

      // TODO: If the stripeCustomer isn't explicitly given, and it wasn't expanded in the charge/intent/subscription,
      // should we try to pull it from the API using the charge/intent/subscription's customerId?
      // Perf hit, but the ultimate fallback.
    }

    if (metadataValue != null) {
      // IMPORTANT: The designation code is copy/pasted by a human and we've had issues with whitespace. Strip it!
      metadataValue = metadataValue.trim();
    }

    return metadataValue;
  }
}
