package com.impactupgrade.nucleus.controller;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

@Path("/messaging")
public class MessagingController {

  @Path("/outbound/crm-list")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response outboundToCrmList(
          @FormParam("list-id") List<String> listIds,
          @FormParam("sender") String _sender,
          @FormParam("message") String message,
          @FormParam("nucleus-username") String nucleusUsername,
          @FormParam("nucleus-email") String nucleusEmail,
          @Context HttpServletRequest request) throws Exception {
    return null;
  }

  @Path("/inbound/sms/signup")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_XML)
  public Response inboundSignup(
          @FormParam("From") String from,
          @FormParam("FirstName") String _firstName,
          @FormParam("LastName") String _lastName,
          @FormParam("FullName") String fullName,
          @FormParam("Email") String _email,
          @FormParam("EmailOptIn") String emailOptIn,
          @FormParam("SmsOptIn") String smsOptIn,
          @FormParam("Language") String _language,
          @FormParam("ListId") String _listId,
          @FormParam("HubSpotListId") @Deprecated Long hsListId,
          @FormParam("CampaignId") String campaignId,
          @FormParam("OpportunityName") String opportunityName,
          @FormParam("OpportunityRecordTypeId") String opportunityRecordTypeId,
          @FormParam("OpportunityOwnerId") String opportunityOwnerId,
          @FormParam("OpportunityNotes") String opportunityNotes,
          @FormParam("nucleus-username") String nucleusUsername,
          @Context HttpServletRequest request
  ) throws Exception {
    return null;
  }

  @Path("/inbound/sms/webhook")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_XML)
  public Response inboundWebhook(
          Form rawFormData,
          @Context HttpServletRequest request
  ) throws Exception {
    return null;
  }

  @Path("/proxy/voice")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_XML)
  public Response proxyVoice(
          @FormParam("From") String from,
          @FormParam("To") String to,
          @FormParam("Digits") String digits,
          @QueryParam("owner") String owner,
          @Context HttpServletRequest request
  ) {
    return null;
  }
}
