/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Gather;
import com.twilio.twiml.voice.Redirect;
import com.twilio.twiml.voice.Say;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.util.Utils.noWhitespace;
import static com.impactupgrade.nucleus.util.Utils.trim;

/**
 * This service provides the ability to send outbound messages through Twilio, as well as be positioned
 * as the Twilio webhook to receive inbound messages and events.
 */
@Path("/twilio")
public class TwilioController {

  private static final Logger log = LogManager.getLogger(TwilioController.class);

  protected final EnvironmentFactory envFactory;
  private MessagingController messagingController;
  public TwilioController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
    this.messagingController = new MessagingController(envFactory);
  }


  /**
   * This webhook is to be used by Twilio Studio flows, Twilio Functions, etc. for more complex
   * interactions. Try to make use of the standard form params whenever possible to maintain the overlap!
   */
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
    log.warn("Out of Date, switch to MessagingController");
    return messagingController.inboundSignup(from, _firstName, _lastName, fullName, _email, emailOptIn, smsOptIn, _language, _listId, hsListId, campaignId, opportunityName, opportunityRecordTypeId, opportunityOwnerId, opportunityNotes, nucleusUsername, request);
  }

  private static final List<String> STOP_WORDS = List.of("STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT");

  /**
   * This webhook serves as a more generic catch-all endpoint for inbound messages from Twilio.
   */
  @Path("/inbound/sms/webhook")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_XML)
  public Response inboundWebhook(
      Form rawFormData,
      @Context HttpServletRequest request
  ) throws Exception {
    log.warn("Out of Date, switch to MessagingController");
    return messagingController.inboundWebhook(rawFormData, request);
  }

  /**
   * Positioned as a webhook on fundraiser proxy phone numbers, allowing outbound masked calls and inbound call forwarding.
   *
   * @param from
   * @param to
   * @param digits
   * @param owner
   * @return
   */
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
    log.info("from={} owner={}", from, owner);
    Environment env = envFactory.init(request);

    String xml;

    // if the owner of this number is calling, assume they want to proxy an outbound call
    if (from.equals(owner)) {
      // beginning of the process, so prompt for the destination number
      if (Strings.isNullOrEmpty(digits)) {
        log.info("prompting owner for recipient phone number");
        xml = new VoiceResponse.Builder()
            .gather(new Gather.Builder().say(new Say.Builder("Please enter the destination phone number, followed by #.").build()).build())
            // redirect to this endpoint again, which will include the response in a Digits form param
            .redirect(new Redirect.Builder("/api/twilio/proxy/voice?owner=" + owner).build())
            .build().toXml();
      }
      // we already prompted, then redirected back to this endpoint, so use the Digits that were included to dial the recipient
      else {
        log.info("owner provided recipient phone number; dialing {}", digits);
        xml = new VoiceResponse.Builder()
            // note: in this case, 'to' is the Twilio masking number
            .dial(new Dial.Builder(digits).callerId(to).build())
            .build().toXml();
      }
    }
    // else, it's someone else calling inbound, so send it to the owner
    else {
      log.info("inbound call from {}; connecting to {}", from, owner);
      xml = new VoiceResponse.Builder()
          .dial(new Dial.Builder(owner).build())
          .build().toXml();
    }

    return Response.ok().entity(xml).build();
  }

  // TODO: Temporary method to prototype an MMS replacement of the mobile app. In the future,
  // this can be molded into an API...
  public static void main(String[] args) {
//    Message.creator(
//        new PhoneNumber(to),
//        new PhoneNumber("+12607862676"),
//        "Destiny Rescue is committed to rescuing children from the sex trade and empowering them to stay free. Our rescue agents risk their lives searching for underaged children in brothels, red light districts and sexually abusive situations."
//    ).setMediaUrl(Arrays.asList(
//        URI.create("https://www.destinyrescue.org/us/files/2019/07/undercover.jpg")
//    )).create();
//
//    // TODO: Instead, need to wait for the success callback from the previous message
//    try {
//      Thread.sleep(7000);
//    } catch (InterruptedException e) {
//      e.printStackTrace();
//    }
//
//    Message.creator(
//        new com.twilio.type.PhoneNumber(to),
//        new com.twilio.type.PhoneNumber("+12607862676"),
//        "And this is a demo of multiple pictures..."
//    ).setMediaUrl(Arrays.asList(
//        URI.create("https://www.destinyrescue.org/us/files/2016/08/0f3925c4508498abbcfba6dcdec996ca.png"),
//        URI.create("https://www.destinyrescue.org/us/files/2016/08/43c4d8f8ad80dae89a7301004f75d9de.png"),
//        URI.create("https://www.destinyrescue.org/us/files/2016/07/df88c43ce3f312428cfe55c4adcdca60.jpg")
//    )).create();
//
//    // TODO: Instead, need to wait for the success callback from the previous message
//    try {
//      Thread.sleep(7000);
//    } catch (InterruptedException e) {
//      e.printStackTrace();
//    }
//
//    Message.creator(
//        new PhoneNumber("+12603495732"),
//        new PhoneNumber("+17272737283"),
//        "Finally, a call to action. Learn more about how we’re working to rescue the enslaved: https://www.somewebsite.com/action"
//    ).create();
  }
}
