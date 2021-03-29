package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.stripe.exception.StripeException;

public interface PaymentGatewayService {

  void updateSubscription(ManageDonationEvent manageDonationEvent) throws StripeException;
}
