/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.security;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;

public class SecurityExceptionMapper implements ExceptionMapper<SecurityException> {

  @Override
  public Response toResponse(SecurityException exception) {
    return Response.status(Response.Status.UNAUTHORIZED).build();
  }
}
