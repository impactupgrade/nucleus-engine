package com.impactupgrade.nucleus.integration;

import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;

import javax.servlet.http.HttpServletRequest;

// TODO: hard coding for now...

public class IntegrationTestEnvironment extends Environment {

  @Override
  public SfdcClient sfdcClient() {
    // https://impactupgrade-dev-ed.lightning.force.com
    // technically "production" since we're using a developer education account
    return new SfdcClient(this, "brett@impactupgrade.com", "?-s~X{\"zzW])\"-4}XuJN932Z53KTCp97coeL50ER", SfdcClient.AUTH_URL_PRODUCTION);
  }

  @Override
  public RequestEnvironment newRequestEnvironment(HttpServletRequest request) {
    return new RequestEnvironment(request) {
      @Override
      public StripeClient stripeClient() {
        // team@impactupgrade.com test account
        return new StripeClient("sk_test_51Imqu3HAwJOu5brrFI1LeFsnRbGSKo01FLQ9tJijlMtTZPXl4XyB2Kidg9qrWqCP6VlDK5EO0YxSXAbPcWZBp7ey00dhw2OuZg");
      }
    };
  }
}
