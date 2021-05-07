package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.stripe.exception.StripeException;
import java.text.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class StripePaymentGatewayService implements PaymentGatewayService {

  private static final Logger log = LogManager.getLogger(StripePaymentGatewayService.class);

  public StripePaymentGatewayService(Environment env) {}

  @Override
  public void updateSubscription(ManageDonationEvent manageDonationEvent) throws StripeException, ParseException {
    StripeClient stripeClient = manageDonationEvent.getRequestEnv().stripeClient();
    if (manageDonationEvent.getAmount() != null && manageDonationEvent.getAmount() > 0) {
      stripeClient.updateSubscriptionAmount(manageDonationEvent.getSubscriptionId(), manageDonationEvent.getAmount());
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
}
