package com.impactupgrade.common.filter;

import com.impactupgrade.common.environment.RequestEnvironment;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;

/**
 * Many organizations will only have one RequestEnvironment. But some (DR, Donation Spring, etc.) are by-request.
 * In those cases, they'll need to provide their own constructor (and generally, a unique Controller method
 * to go with it). So make it obvious that this is only the default.
 */
@Provider
public class RequestEnvironmentFilter implements ContainerRequestFilter {

  // TODO: How should this be overridden?

  @Override
  public void filter(ContainerRequestContext request) {
    request.setProperty("requestEnv", new RequestEnvironment());
  }
}