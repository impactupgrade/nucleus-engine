/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.it;

import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;

// TODO: hard coding for now...

public class EnvironmentIT extends Environment {

  @Override
  public SfdcClient sfdcClient() {
    // https://impactupgrade-dev-ed.lightning.force.com
    // technically "production" since we're using a developer education account
    return new SfdcClient(this, "brett@impactupgrade.com", "nnferQqWq6MaEw8aUEohrLWiJN5OIpgpejBeCH7m3", SfdcClient.AUTH_URL_PRODUCTION);
  }

  @Override
  public StripeClient stripeClient() {
    // team@impactupgrade.com test account
    return new StripeClient("sk_test_51Imqu3HAwJOu5brrFI1LeFsnRbGSKo01FLQ9tJijlMtTZPXl4XyB2Kidg9qrWqCP6VlDK5EO0YxSXAbPcWZBp7ey00dhw2OuZg");
  }
}