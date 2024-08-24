/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmActivity;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.service.logic.NotificationService;
import com.impactupgrade.nucleus.util.Utils;
import com.twilio.twiml.MessagingResponse;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.messaging.Body;
import com.twilio.twiml.messaging.Message;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Gather;
import com.twilio.twiml.voice.Redirect;
import com.twilio.twiml.voice.Say;

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
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.entity.JobStatus.DONE;
import static com.impactupgrade.nucleus.entity.JobStatus.FAILED;
import static com.impactupgrade.nucleus.util.Utils.noWhitespace;
import static com.impactupgrade.nucleus.util.Utils.trim;

/**
 * This service provides the ability to send outbound messages through Twilio, as well as be positioned
 * as the Twilio webhook to receive inbound messages and events.
 */
@Path("/twilio")
public class TwilioController {

  protected final EnvironmentFactory envFactory;

  public TwilioController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
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
      @FormParam("ListId") String listId,
      @FormParam("CampaignId") String campaignId,
      @FormParam("OpportunityName") String opportunityName,
      @FormParam("OpportunityRecordTypeId") String opportunityRecordTypeId,
      @FormParam("OpportunityOwnerId") String opportunityOwnerId,
      @FormParam("OpportunityNotes") String opportunityNotes,
      @FormParam("nucleus-username") String nucleusUsername,
      @Context HttpServletRequest request,
      MultivaluedMap<String, Object> customResponses
  ) throws Exception {
    Environment env = envFactory.init(request);
    List<String> params = Arrays.asList("From", "FirstName", "LastName", "FullName", "Email", "EmailOptIn", "SmsOptIn", "Language", "ListId", "HubSpotListId", "CampaignId", "OpportunityName", "OpportunityRecordTypeId", "OpportunityOwnerId", "OpportunityNotes", "nucleus-username");
    for(String key : params) {
      customResponses.remove(key);
    }

    env.startJobLog(JobType.EVENT, nucleusUsername, "SMS Flow", "Twilio");
    env.logJobInfo("from={} firstName={} lastName={} fullName={} email={} emailOptIn={} smsOptIn={} language={} listId={} campaignId={} opportunityName={} opportunityRecordTypeId={} opportunityOwnerId={} opportunityNotes={}",
        from, _firstName, _lastName, fullName, _email, emailOptIn, smsOptIn, _language, listId, campaignId, opportunityName, opportunityRecordTypeId, opportunityOwnerId, opportunityNotes);

    _firstName = trim(_firstName);
    _lastName = trim(_lastName);
    fullName = trim(fullName);
    final String email = noWhitespace(_email);
    final String language = noWhitespace(_language);

    String firstName;
    String lastName;
    if (!Strings.isNullOrEmpty(fullName)) {
      String[] split = Utils.fullNameToFirstLast(fullName);
      firstName = split[0];
      lastName = split[1];
    } else {
      firstName = _firstName;
      lastName = _lastName;
    }

    Runnable thread = () -> {
      try {
        CrmContact crmContact = env.messagingService().processSignup(
            from,
            firstName,
            lastName,
            email,
            emailOptIn,
            smsOptIn,
            language,
            campaignId,
            listId,
            Utils.toMap(customResponses)
        );

        // avoid the insertOpportunity call unless we're actually creating a non-donation opportunity
        CrmOpportunity crmOpportunity = new CrmOpportunity();
        crmOpportunity.contact.id = crmContact.id;

        if (!Strings.isNullOrEmpty(opportunityName)) {
          crmOpportunity.name = opportunityName;
          crmOpportunity.recordTypeId = opportunityRecordTypeId;
          crmOpportunity.ownerId = opportunityOwnerId;
          crmOpportunity.campaignId = campaignId;
          crmOpportunity.description = opportunityNotes;
          env.messagingCrmService().insertOpportunity(crmOpportunity);
        }

        env.endJobLog(DONE);
      } catch (Exception e) {
        env.logJobError("inbound SMS flow failed", e);
        env.endJobLog(FAILED);
      }
    };
    new Thread(thread).start();

    // TODO: This builds TwiML, which we could later use to send back dynamic responses.
    MessagingResponse response = new MessagingResponse.Builder().build();
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

    env.startJobLog(JobType.EVENT, null, "SMS Inbound", "Twilio");
    MultivaluedMap<String, String> smsData = rawFormData.asMap();
    env.logJobInfo(smsData.entrySet().stream().map(e -> e.getKey() + "=" + String.join(",", e.getValue())).collect(Collectors.joining(" ")));

    Body responseBody = null;

    String from = smsData.get("From").get(0);
    if (smsData.containsKey("Body")) {
      String body = smsData.get("Body").get(0).trim();
      // prevent opt-out messages, like "STOP", from polluting the notifications
      if (!STOP_WORDS.contains(body.toUpperCase(Locale.ROOT))) {
        // https://www.baeldung.com/java-regex-validate-phone-numbers
        String pnPatterns
            = "(\\+\\d{1,3}( )?)?((\\(\\d{3}\\))|\\d{3})[- .]?\\d{3}[- .]?\\d{4}"
            + "|(\\+\\d{1,3}( )?)?(\\d{3}[ ]?){2}\\d{3}"
            + "|(\\+\\d{1,3}( )?)?(\\d{3}[ ]?)(\\d{2}[ ]?){2}\\d{2}";
        Pattern r = Pattern.compile("reply\s+(" + pnPatterns + ")\s+(.*)");
        Matcher m = r.matcher(body.toLowerCase(Locale.ROOT));

        if (m.find()) {
          String mobilePhone = m.group(1);
          // PN matching contains multiple, inner groups, so the message is the very last one.
          String message = m.group(m.groupCount());

          String twilioNumber = smsData.get("To").get(0);

          CrmContact crmContact = new CrmContact();
          crmContact.mobilePhone = mobilePhone;
          env.messagingService().sendMessage(message, null, crmContact, twilioNumber);
        } else if (env.notificationService().notificationConfigured("sms:inbound-default")) {
          String subject = "Text Message Received";
          String message = "Text message received from " + from + " :: " + body;
          NotificationService.Notification notification = new NotificationService.Notification(subject, message);
          notification.smsBody = message + " // To respond: type 'reply', then their phone number, and then your message. Ex: reploy 260-123-4567 Thanks, I got your message!";

          String targetId = env.messagingCrmService().searchContacts(ContactSearch.byPhone(from)).getSingleResult().map(c -> c.id).orElse(null);

          env.notificationService().sendNotification(notification, targetId, "sms:inbound-default");
        } else if (!Strings.isNullOrEmpty(env.getConfig().twilio.defaultResponse)) {
          env.logJobInfo("responding with: {}", env.getConfig().twilio.defaultResponse);

          responseBody = new Body.Builder(env.getConfig().twilio.defaultResponse).build();
        }
      }
    }

    env.endJobLog(DONE);

    MessagingResponse.Builder responseBuilder = new MessagingResponse.Builder();
    if (responseBody != null) {
      responseBuilder.message(new Message.Builder().body(responseBody).build());
    }
    return Response.ok().entity(responseBuilder.build().toXml()).build();
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
    Environment env = envFactory.init(request);

    env.startJobLog(JobType.EVENT, null, "SMS Voice Proxy", "Twilio");
    env.logJobInfo("from={} owner={}", from, owner);

    String xml;

    // if the owner of this number is calling, assume they want to proxy an outbound call
    if (from.equals(owner)) {
      // beginning of the process, so prompt for the destination number
      if (Strings.isNullOrEmpty(digits)) {
        env.logJobInfo("prompting owner for recipient phone number");
        xml = new VoiceResponse.Builder()
            .gather(new Gather.Builder().say(new Say.Builder("Please enter the destination phone number, followed by #.").build()).build())
            // redirect to this endpoint again, which will include the response in a Digits form param
            .redirect(new Redirect.Builder("/api/twilio/proxy/voice?owner=" + owner).build())
            .build().toXml();
      }
      // we already prompted, then redirected back to this endpoint, so use the Digits that were included to dial the recipient
      else {
        env.logJobInfo("owner provided recipient phone number; dialing {}", digits);
        xml = new VoiceResponse.Builder()
            // note: in this case, 'to' is the Twilio masking number
            .dial(new Dial.Builder(digits).callerId(to).build())
            .build().toXml();
      }
    }
    // else, it's someone else calling inbound, so send it to the owner
    else {
      env.logJobInfo("inbound call from {}; connecting to {}", from, owner);
      xml = new VoiceResponse.Builder()
          .dial(new Dial.Builder(owner).build())
          .build().toXml();
    }

    env.endJobLog(DONE);

    return Response.ok().entity(xml).build();
  }

  /**
   * This webhook handles 'onMessageAdded' event for Conversations, creating CRM activities. However, note that
   * tracking of one-off messages is instead handled by inboundWebhook!
   */
  @Path("/callback/conversations")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response conversationsWebhook(
      @FormParam("EventType") String eventType,
      @FormParam("ConversationSid") String conversationSid,
      @FormParam("MessageSid") String messageSid,
      @FormParam("MessagingServiceSid") String messagingServiceSid,
      @FormParam("Index") Integer index,
      @FormParam("DateCreated") String date, //ISO8601 time
      @FormParam("Body") String body,
      @FormParam("Author") String author,
      @FormParam("ParticipantSid") String participantSid,
      @FormParam("Attributes") String attributes,
      @FormParam("Media") String media, // Stringified JSON array of attached media objects
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    env.startJobLog(JobType.EVENT, null, "Conversation Webhook", "Twilio");
    env.logJobInfo("eventType={} conversationSid={} messageSid={} messagingServiceSid={} index={} date={} body={} author={} participantSid={} attributes={} media={}",
        eventType, conversationSid, messageSid, messagingServiceSid, index, date, body, author, participantSid, attributes, media);

    switch (eventType) {
      case "onMessageAdded":
        env.activityService().upsertActivityFromPhoneNumbers(
            List.of(author), // TODO: Won't this fail to find a record if the author was the organization's number, not the outside recipient?
            CrmActivity.Type.CALL,
            conversationSid,
            Calendar.getInstance(),
            "SMS " + conversationSid,
            body
        );
        env.endJobLog(DONE);
        return Response.ok().build();
      default:
        env.logJobWarn("unexpected eventType: " + eventType);
        env.endJobLog(FAILED);
        return Response.status(422).build();
    }
  }
}
