/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.ProcessContext;
import com.impactupgrade.nucleus.model.event.ManageDonationEvent;
import com.stripe.exception.StripeException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.ParseException;

public class StripePaymentGatewayService implements PaymentGatewayService {

  private static final Logger log = LogManager.getLogger(StripePaymentGatewayService.class);

  protected final ProcessContext processContext;

  public StripePaymentGatewayService(ProcessContext processContext) {
    this.processContext = processContext;
  }

  @Override
  public void updateSubscription(ManageDonationEvent manageDonationEvent) throws StripeException, ParseException {
    StripeClient stripeClient = processContext.stripeClient();
    if (manageDonationEvent.getAmount() != null && manageDonationEvent.getAmount() > 0) {
      stripeClient.updateSubscriptionAmount(manageDonationEvent.getSubscriptionId(), manageDonationEvent.getAmount());
    }

    if (manageDonationEvent.getNextPaymentDate() != null) {
      stripeClient.updateSubscriptionDate(manageDonationEvent.getSubscriptionId(), manageDonationEvent.getNextPaymentDate());
    }

    if (manageDonationEvent.getPauseDonation() == true) {
      stripeClient.pauseSubscription(manageDonationEvent.getSubscriptionId(), manageDonationEvent.getPauseDonationUntilDate());
    }

    if (manageDonationEvent.getResumeDonation() == true) {
      stripeClient.resumeSubscription(manageDonationEvent.getSubscriptionId(), manageDonationEvent.getResumeDonationOnDate());
    }

    if (manageDonationEvent.getStripeToken() != null) {
      stripeClient.updateSubscriptionPaymentMethod(manageDonationEvent.getSubscriptionId(), manageDonationEvent.getStripeToken());
    }
  }

  @Override
  public void cancelSubscription(ManageDonationEvent manageDonationEvent) throws StripeException {
    StripeClient stripeClient = processContext.stripeClient();
    stripeClient.cancelSubscription(manageDonationEvent.getSubscriptionId());
  }
}
