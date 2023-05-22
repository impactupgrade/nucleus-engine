package com.impactupgrade.nucleus.controller;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.security.SecurityUtil;
import com.impactupgrade.nucleus.service.logic.MessagingService;
import com.impactupgrade.nucleus.util.Utils;
import com.twilio.twiml.MessagingResponse;
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

@Path("/messaging")
public class MessagingController {
  private static final Logger log = LogManager.getLogger(TwilioController.class);

  protected final EnvironmentFactory envFactory;
  public MessagingController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }
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
    Environment env = envFactory.init(request);
    SecurityUtil.verifyApiKey(env);

    MessagingService messagingService = env.messagingService();

    log.info("listIds={} sender={} message={}", Joiner.on(",").join(listIds), _sender, message);

    String sender;
    if (!Strings.isNullOrEmpty(_sender)) {
      sender = _sender;
    } else {
      Map<String, EnvironmentConfig.TwilioUser> users = env.getConfig().twilio.users; //TODO NOTE twilio specific
      if (users != null && (users.containsKey(nucleusUsername) || users.containsKey(nucleusEmail))) {
        if (users.containsKey(nucleusUsername)) {
          sender = users.get(nucleusUsername).senderPn;
        } else {
          sender = users.get(nucleusEmail).senderPn;
        }
      } else {
        sender = env.getConfig().twilio.senderPn;
      }
    }

    // first grab all the contacts, since we want to fail early if there's an issue and give a clear error in the portal
    List<CrmContact> contacts = new ArrayList<>();
    for (String listId : listIds) {
      try {
        log.info("retrieving contacts from list {}", listId);
        contacts.addAll(env.messagingCrmService().getContactsFromList(listId));
        log.info("found {} contacts in list {}", contacts.size(), listId);
      } catch (Exception e) {
        log.warn("failed to retrieve list {}", listId, e);
        return Response.serverError().build();
      }
    }

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      try {
        String jobName = "SMS Blast";
        log.info("STARTED: {}", jobName);
        env.startJobLog(JobType.PORTAL_TASK, null, jobName, env.textingService().name());

        List<CrmContact> filteredContacts = contacts.stream()
                .filter(c -> !Strings.isNullOrEmpty(c.phoneNumberForSMS()))
                .collect(Collectors.toList());
        int messagesSent = 0;

        for (CrmContact c: filteredContacts) {
          messagingService.sendMessage(message, c, sender);
          env.logJobProgress(++messagesSent + " message(s) sent");
        }

        env.endJobLog(jobName);
        log.info("FINISHED: {}", jobName);

      } catch (Exception e) {
        log.error("job failed", e);
        env.logJobError(e.getMessage());
      }

    };
    new Thread(thread).start();

    return Response.ok().build();
  }

  /**
   * This webhook is to be used by Twilio Studio flows, Twilio Functions, etc. for more complex
   * interactions. Try to make use of the standard form params whenever possible to maintain the overlap!
   */
  @Path("twilio/inbound/sms/signup") //TODO make this generic
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
    log.info("from={} firstName={} lastName={} fullName={} email={} emailOptIn={} smsOptIn={} language={} listId={} hsListId={} campaignId={} opportunityName={} opportunityRecordTypeId={} opportunityOwnerId={} opportunityNotes={}",
            from, _firstName, _lastName, fullName, _email, emailOptIn, smsOptIn, _language, _listId, hsListId, campaignId, opportunityName, opportunityRecordTypeId, opportunityOwnerId, opportunityNotes);
    Environment env = envFactory.init(request);

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

    String listId;
    if (hsListId != null && hsListId > 0) {
      listId = hsListId + "";
    } else {
      listId = _listId;
    }

    Runnable thread = () -> {
      try {
        String jobName = "SMS Flow";
        env.startJobLog(JobType.EVENT, null, jobName, env.textingService().name());
        CrmContact crmContact = env.messagingService().processSignup(
                from,
                firstName,
                lastName,
                email,
                emailOptIn,
                smsOptIn,
                language,
                campaignId,
                listId
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
          env.endJobLog(jobName);
        }
      } catch (Exception e) {
        log.warn("inbound SMS signup failed", e);
        env.logJobError(e.getMessage());
      }
    };
    new Thread(thread).start();

    //TODO removed OG Twilio response, revisit after sorting out MB
    return Response.ok().build();
  }

  private static final List<String> STOP_WORDS = List.of("STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT");

  /**
   * This webhook serves as a more generic catch-all endpoint for inbound messages from Twilio.
   */
  @Path("twilio/inbound/sms/webhook")
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
        String jobName = "SMS Inbound";
        env.startJobLog(JobType.EVENT, null, jobName, "Twilio");
        String targetId = env.messagingCrmService().searchContacts(ContactSearch.byPhone(from)).getSingleResult().map(c -> c.id).orElse(null);
        env.notificationService().sendNotification(
                "Text Message Received",
                "Text message received from " + from + ": " + body,
                targetId,
                "sms:inbound-default"
        );
        env.endJobLog(jobName);
      }
    }
    //TODO removed OG Twilio response, revisit after sorting out MB
    return Response.ok().build();
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
  @Path("/twilio/proxy/voice") //TODO adding a /twilio path for now to keep platform specific endpoints
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
}
