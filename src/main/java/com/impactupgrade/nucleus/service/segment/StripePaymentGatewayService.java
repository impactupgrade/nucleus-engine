/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.stripe.exception.StripeException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import java.text.ParseException;

public class StripePaymentGatewayService implements PaymentGatewayService {

  private static final Logger log = LogManager.getLogger(StripePaymentGatewayService.class);

  @Autowired
  protected Environment env;
  @Autowired
  protected StripeClient stripeClient;

  @Override
  public void updateSubscription(ManageDonationEvent manageDonationEvent) throws StripeException, ParseException {
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
  public void closeSubscription(ManageDonationEvent manageDonationEvent) throws StripeException {
    stripeClient.cancelSubscription(manageDonationEvent.getSubscriptionId());
  }
}
