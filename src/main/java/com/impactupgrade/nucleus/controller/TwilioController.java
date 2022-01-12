/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.TwilioClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.OpportunityEvent;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.util.Utils;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.twiml.MessagingResponse;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.messaging.Body;
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
import java.util.stream.Collectors;

/**
 * This service provides the ability to send outbound messages through Twilio, as well as be positioned
 * as the Twilio webhook to receive inbound messages and events.
 */
@Path("/twilio")
public class TwilioController {

  private static final Logger log = LogManager.getLogger(TwilioController.class);

  protected final EnvironmentFactory envFactory;

  public TwilioController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @Path("/outbound/crm-list")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response outboundToCrmList(@FormParam("list-id") List<String> listIds, @FormParam("message") String message,
      @Context HttpServletRequest request) {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    TwilioClient twilioClient = env.twilioClient();

    log.info("listIds={} message={}", Joiner.on(",").join(listIds), message);

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      for (String listId : listIds) {
        try {
          log.info("retrieving contacts from list {}", listId);
          List<CrmContact> contacts = env.messagingCrmService().getContactsFromList(listId);
          log.info("found {} contacts in list {}", contacts.size(), listId);
          contacts.stream()
              .filter(c -> c.mobilePhone != null || c.homePhone != null)
              .forEach(c -> {
                try {
                  String pn = c.mobilePhone;
                  if (Strings.isNullOrEmpty(pn)) {
                    // Just in case...
                    pn = c.homePhone;
                  }
                  pn = pn.replaceAll("[^0-9\\+]", "");

                  if (!Strings.isNullOrEmpty(pn)) {
                    Message twilioMessage = twilioClient.sendMessage(pn, message, null);

                    log.info("sent messageSid {} to {}; status={} errorCode={} errorMessage={}",
                        twilioMessage.getSid(), pn, twilioMessage.getStatus(), twilioMessage.getErrorCode(), twilioMessage.getErrorMessage());
                  }
                } catch (ApiException e1) {
                  if (e1.getCode() == 21610) {
                    log.info("message to {} failed due to blacklist; updating contact in CRM", c.mobilePhone);
                    try {
                      env.messagingService().optOut(c);
                    } catch (Exception e2) {
                      log.error("CRM contact update failed", e2);
                    }
                  } else if (e1.getCode() == 21408 || e1.getCode() == 21211) {
                    log.info("invalid phone number: {}; updating contact in CRM", c.mobilePhone);
                    try {
                      env.messagingService().optOut(c);
                    } catch (Exception e2) {
                      log.error("CRM contact update failed", e2);
                    }
                  } else {
                    log.warn("message to {} failed: {} {}", c.mobilePhone, e1.getCode(), e1.getMessage(), e1);
                  }
                } catch (Exception e) {
                  log.warn("message to {} failed", c.mobilePhone, e);
                }
              });
        } catch (Exception e) {
          log.warn("failed to retrieve contacts from list {}", listId, e);
        }
      }
      log.info("FINISHED: outbound/crm-list");
    };
    new Thread(thread).start();

    return Response.ok().build();
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
      @FormParam("FirstName") String __firstName,
      @FormParam("LastName") String __lastName,
      @FormParam("FullName") String fullName,
      @FormParam("Email") String email,
      @FormParam("EmailOptIn") String emailOptIn,
      @FormParam("SmsOptIn") String smsOptIn,
      @FormParam("ListId") String __listId,
      @FormParam("HubSpotListId") @Deprecated Long hsListId,
      @FormParam("CampaignId") String campaignId,
      @FormParam("OpportunityName") String opportunityName,
      @FormParam("OpportunityRecordTypeId") String opportunityRecordTypeId,
      @FormParam("OpportunityOwnerId") String opportunityOwnerId,
      @FormParam("OpportunityNotes") String opportunityNotes,
      @Context HttpServletRequest request
  ) throws Exception {
    log.info("from={} firstName={} lastName={} fullName={} email={} emailOptIn={} smsOptIn={} listId={} hsListId={} campaignId={} opportunityName={} opportunityRecordTypeId={} opportunityOwnerId={} opportunityNotes={}",
        from, __firstName, __lastName, fullName, email, emailOptIn, smsOptIn, __listId, hsListId, campaignId, opportunityName, opportunityRecordTypeId, opportunityOwnerId, opportunityNotes);
    Environment env = envFactory.init(request);
    OpportunityEvent opportunityEvent = new OpportunityEvent(env);

    String firstName;
    String lastName;
    if (!Strings.isNullOrEmpty(fullName)) {
      String[] split = Utils.fullNameToFirstLast(fullName);
      firstName = split[0];
      lastName = split[1];
    } else {
      firstName = __firstName;
      lastName = __lastName;
    }

    String listId;
    if (hsListId != null && hsListId > 0) {
      listId = hsListId + "";
    } else {
      listId = __listId;
    }

    Runnable thread = () -> {
      try {
        env.messagingService().processSignup(
            opportunityEvent,
            from,
            firstName,
            lastName,
            email,
            emailOptIn,
            smsOptIn,
            campaignId,
            listId
        );

        // avoid the insertOpportunity call unless we're actually creating a non-donation opportunity
        if (!Strings.isNullOrEmpty(opportunityName)) {
          opportunityEvent.setName(opportunityName);
          opportunityEvent.setRecordTypeId(opportunityRecordTypeId);
          opportunityEvent.setOwnerId(opportunityOwnerId);
          opportunityEvent.setCampaignId(campaignId);
          opportunityEvent.setNotes(opportunityNotes);
          env.messagingCrmService().insertOpportunity(opportunityEvent);
        }
      } catch (Exception e) {
        log.warn("inbound SMS signup failed", e);
      }
    };
    new Thread(thread).start();

    // TODO: This builds TwiML, which we could later use to send back dynamic responses.
    MessagingResponse response = new MessagingResponse.Builder().build();
    return Response.ok().entity(response.toXml()).build();
  }

  // TODO: Ultimately not helpful since Studio HTTP can't process twiml responses, so this would need wired into
  //  Functions. But we may be replacing the whole concept with the new flows concept in Portal...
  @Path("/inbound/sms/give")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_XML)
  public Response give(
      @FormParam("From") String from,
      @FormParam("ResponseMessage") String responseMessage,
      @FormParam("ResponseGiveUrl") String responseGiveUrl,
      @FormParam("ResponseContactIdParam") String responseContactIdParam,
      @FormParam("ResponseCampaignIdParam") String responseCampaignIdParam,
      @FormParam("ResponseCampaignId") String responseCampaignId,
      @Context HttpServletRequest request
  ) throws Exception {
    log.info("From={} ResponseMessage={} ResponseGiveUrl={} ResponseContactIdParam={} ResponseCampaignIdParam={} ResponseCampaignId={}", from, responseMessage, responseGiveUrl, responseContactIdParam, responseCampaignIdParam, responseCampaignId);
    Environment env = envFactory.init(request);
    CrmService crmService = env.primaryCrmService();

    String content = (responseMessage + " " + responseGiveUrl);

    List<String> params = new ArrayList<>();
    if (!Strings.isNullOrEmpty(responseContactIdParam)) {
      crmService.getContactByPhone(from).ifPresent(c -> {
        log.info("adding CRM Contact {}", c.id);
        params.add(responseContactIdParam + "=" + c.id);
      });
    }
    if (!Strings.isNullOrEmpty(responseCampaignId)) {
      params.add(responseCampaignIdParam + "=" + responseCampaignId);
    }

    if (!params.isEmpty()) {
      content += "?" + String.join("&", params);
    }

    Body body = new Body.Builder(content).build();
    com.twilio.twiml.messaging.Message message = new com.twilio.twiml.messaging.Message.Builder().body(body).build();
    MessagingResponse response = new MessagingResponse.Builder().message(message).build();
    return Response.ok().entity(response.toXml()).build();
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
    Environment env = envFactory.init(request);

    MultivaluedMap<String, String> smsData = rawFormData.asMap();
    log.info(smsData.entrySet().stream().map(e -> e.getKey() + "=" + String.join(",", e.getValue())).collect(Collectors.joining(" ")));

    String from = smsData.get("From").get(0);
    if (smsData.containsKey("Body")) {
      String body = smsData.get("Body").get(0).trim();
      // prevent opt-out messages, like "STOP", from polluting the notifications
      if (!STOP_WORDS.contains(body.toUpperCase(Locale.ROOT))) {
        // for now, simply send an email if it's configured -- in the future, update this to allow Activities to be stored on the SFDC contact, forward to a staff member, etc
        env.notificationService().sendEmailNotification(
            "Text Message Received",
            "Text message received from " + from + ": " + body,
            "sms:inbound-default"
        );
      }
    }

    // TODO: This builds TwiML, which we could later use to send back dynamic responses.
    MessagingResponse response = new MessagingResponse.Builder().build();
    return Response.ok().entity(response.toXml()).build();
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
