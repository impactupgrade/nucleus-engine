/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.stripe.exception.StripeException;
import java.text.ParseException;

public interface PaymentGatewayService extends SegmentService {

  void updateSubscription(ManageDonationEvent manageDonationEvent) throws StripeException, ParseException;
  void closeSubscription(ManageDonationEvent manageDonationEvent) throws StripeException, ParseException;
}
