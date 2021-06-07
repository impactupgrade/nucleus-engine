/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.event.ManageDonationEvent;
import com.stripe.exception.StripeException;
import java.text.ParseException;

public interface PaymentGatewayService {

  void updateSubscription(ManageDonationEvent manageDonationEvent) throws StripeException, ParseException;
  void cancelSubscription(ManageDonationEvent manageDonationEvent) throws StripeException, ParseException;
}
