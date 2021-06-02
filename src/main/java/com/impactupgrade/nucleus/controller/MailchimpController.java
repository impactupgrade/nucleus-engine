package com.impactupgrade.nucleus.controller;

import com.ecwid.maleorang.MailchimpObject;
import com.ecwid.maleorang.method.v3_0.lists.members.MemberInfo;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.service.logic.EmailService;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.service.segment.EmailPlatformService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

@Path("/mailchimp")
public class MailchimpController {

  private static final Logger log = LogManager.getLogger(MailchimpController.class);

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
      @FormParam("list_id") String list_id,
      @Context HttpServletRequest request
  )throws Exception{
    log.info("action = {} reason = {} email = {} list_id = {}",
        action, reason, email, list_id);

    Environment env = envFactory.init(request);
    if (action.equalsIgnoreCase("unsub")){
      env.emailPlatformService().unsubscribeContact(email,list_id); //working with list_Ids for this particular method instead of names
    }else{
      log.warn("Wrong event (not unsub), no action occurred");
    }
  return Response.status(200).build();
  }


}
