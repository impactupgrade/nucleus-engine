package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.EnvironmentFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
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
  public Response syncDaily(@Context HttpServletRequest request) throws Exception {
    return new CommunicationController(envFactory).syncDaily(request);
  }

  @GET
  @Path("/sync/all")
  public Response syncAll(@Context HttpServletRequest request) throws Exception {
    return new CommunicationController(envFactory).syncAll(request);
  }

  @POST
  @Path("/upsert")
  @Consumes("application/x-www-form-urlencoded")
  public Response upsertContact(
      @FormParam("contact-id") String contactId,
      @Context HttpServletRequest request
  ) throws Exception {
    return new CommunicationController(envFactory).upsertContact(contactId, request);
  }
}
