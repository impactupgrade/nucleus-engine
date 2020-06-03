package com.impactupgrade.common.security;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class SecurityExceptionMapper implements ExceptionMapper<SecurityException> {

  @Override
  public Response toResponse(SecurityException exception) {
    return Response.status(Response.Status.UNAUTHORIZED).build();
  }
}
