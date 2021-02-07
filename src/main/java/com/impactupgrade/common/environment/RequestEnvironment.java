package com.impactupgrade.common.environment;

import com.impactupgrade.common.paymentgateway.stripe.StripeClient;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.container.ContainerRequestContext;

/**
 * Much like Environment, this allows context-specific info to be set *per request*. Examples: SaaS flexibility,
 * orgs that have multiple keys within them (DR FNs), etc.
 *
 * Note that not every processing flow needs this! Allow it to be built on-demand by the Controller methods calling
 * Services/Clients where this is truly required.
 */
public class RequestEnvironment {

  protected final ContainerRequestContext context;

  public RequestEnvironment(ContainerRequestContext context) {
    this.context = context;
  }

  public StripeClient stripeClient() {
    return new StripeClient(System.getenv("STRIPE_KEY"));
  }

  public String currency() {
    return "usd";
  }

  public static RequestEnvironment fromRequest(HttpServletRequest request) {
    return (RequestEnvironment) request.getAttribute("requestEnv");
  }
}
