/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.EnvironmentFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

@Deprecated
@Path("/email")
public class EmailController {

  protected final EnvironmentFactory envFactory;

  public EmailController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @GET
  @Path("/sync/daily")
  public Response syncDaily(
          @QueryParam("service") String serviceName,
          @QueryParam("syncDays") Integer syncDays,
          @Context HttpServletRequest request
  ) throws Exception {
    return new CommunicationController(envFactory).syncDaily(serviceName, syncDays, request);
  }

  @GET
  @Path("/sync/all")
  public Response syncAll(
          @QueryParam("service") String serviceName,
          @Context HttpServletRequest request
  ) throws Exception {
    return new CommunicationController(envFactory).syncAll(serviceName, request);
  }

  @POST
  @Path("/upsert")
  @Consumes("application/x-www-form-urlencoded")
  public Response upsertContact(
      @FormParam("contact-id") String contactId,
      @QueryParam("service") String serviceName,
      @Context HttpServletRequest request
  ) throws Exception {
    return new CommunicationController(envFactory).upsertContact(contactId, serviceName, request);
  }
}
