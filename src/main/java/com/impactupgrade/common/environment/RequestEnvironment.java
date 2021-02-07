package com.impactupgrade.common.environment;

import com.impactupgrade.common.paymentgateway.stripe.StripeClient;

import javax.servlet.http.HttpServletRequest;

/**
 * Much like Environment, this allows context-specific info to be set *per request*. Examples: SaaS flexibility,
 * orgs that have multiple keys within them (DR FNs), etc.
 *
 * Note that not every processing flow needs this! Allow it to be built on-demand by the Controller methods calling
 * Services/Clients where this is truly required.
 */
public class RequestEnvironment {

  // NOTE: Don't build any of these on-demand! Let each request do it once.
  private final StripeClient stripeClient;

  public RequestEnvironment() {
    stripeClient = new StripeClient(System.getenv("STRIPE_KEY"));
  }

  public StripeClient stripeClient() {
    return stripeClient;
  }

  public String currency() {
    return "usd";
  }

  public static RequestEnvironment fromRequest(HttpServletRequest request) {
    return (RequestEnvironment) request.getAttribute("requestEnv");
  }
}
