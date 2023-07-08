package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/mailchimp")
public class MailchimpController {

  protected final EnvironmentFactory envFactory;

  public MailchimpController(EnvironmentFactory envFactory){
    this.envFactory = envFactory;
  }

  @Path("/webhook")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response webhook(
      @FormParam("action") String action,
      @FormParam("reason") String reason,
      @FormParam("email") String email,
      @FormParam("list_id") String listId,
      @Context HttpServletRequest request
  ) throws Exception{
    Environment env = envFactory.init(request);

    env.logJobInfo("action = {} reason = {} email = {} list_id = {}", action, reason, email, listId);

    if (action.equalsIgnoreCase("unsub")) {
      // TODO: mark as unsubscribed in the CRM
    } else {
      env.logJobWarn("unexpected event: {}", action);
    }

    return Response.status(200).build();
  }
}
