package com.impactupgrade.common.twilio;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.impactupgrade.common.hubspot.HubSpotClientFactory;
import com.impactupgrade.common.security.SecurityUtil;
import com.impactupgrade.integration.hubspot.builder.ContactBuilder;
import com.impactupgrade.integration.hubspot.exception.DuplicateContactException;
import com.impactupgrade.integration.hubspot.exception.HubSpotException;
import com.impactupgrade.integration.hubspot.model.Contact;
import com.impactupgrade.integration.hubspot.model.ContactArray;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.twiml.MessagingResponse;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Gather;
import com.twilio.twiml.voice.Redirect;
import com.twilio.twiml.voice.Say;
import com.twilio.type.PhoneNumber;
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
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * This service provides the ability to send outbound messages through Twilio, as well as be positioned
 * as the Twilio webhook to receive inbound messages and events.
 */
@Path("/twilio")
public class TwilioService {

  private static final Logger log = LogManager.getLogger(TwilioService.class);

  private static final long DEFAULT_HUBSPOT_SMS_LIST_ID = Long.parseLong(System.getenv("HUBSPOT_SMSLISTID"));

  static {
    Twilio.init(System.getenv("TWILIO_ACCOUNTSID"), System.getenv("TWILIO_AUTHTOKEN"));
  }

  @Path("/outbound/hubspot-list")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response outboundToHubSpotList(@FormParam("list-id") List<Long> listIds, @FormParam("message") String message,
      @Context HttpServletRequest request) {
    SecurityUtil.verifyApiKey(request);

    log.info("listIds={} message={}", Joiner.on(",").join(listIds), message);

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      for (Long listId : listIds) {
        log.info("retrieving contacts from list {}", listId);
        ContactArray contactArray = HubSpotClientFactory.client().lists().getContactsInList(listId);
        log.info("found {} contacts in list {}", contactArray.getContacts().size(), listId);
        contactArray.getContacts().stream()
            .filter(c -> c.getProperties().getPhone() != null)
            .map(c -> c.getProperties().getPhone().getValue())
            .map(pn -> pn.replaceAll("[^0-9\\+]", ""))
            .filter(pn -> !Strings.isNullOrEmpty(pn))
            .forEach(pn -> {
              try {
                Message twilioMessage = Message.creator(
                    new PhoneNumber(pn),
                    // TODO: OOPS!
                    new PhoneNumber("+17207789988"),
                    message
                ).create();

                log.info("sent messageSid {} to {}; status={} errorCode={} errorMessage={}",
                    twilioMessage.getSid(), pn, twilioMessage.getStatus(), twilioMessage.getErrorCode(), twilioMessage.getErrorMessage());
              } catch (Exception e) {
                log.warn("message to {} failed", pn, e);
              }
            });
        log.info("FINISHED: outbound/hubspot-list");
      }
    };
    new Thread(thread).start();

    return Response.ok().build();
  }

  // TODO: Let apps decide SF vs HS, etc.
  /**
   * This webhook serves multiple purposes. It can be used directly on a Twilio number, receiving standard From/Body
   * pairs as texts are received. It can also be used by Twilio Studio flows, Twilio Functions, etc. for more complex
   * interactions. Try to make use of the standard form params whenever possible to maintain the overlap!
   *
   * @param from
   * @param message
   * @return
   */
  @Path("/inbound/sms/signup")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_XML)
  public Response webhook(
      @FormParam("From") String from,
      @FormParam("Body") String message,
      @FormParam("FirstName") String firstName,
      @FormParam("LastName") String lastName,
      @FormParam("Email") String email,
      @FormParam("HubSpotListId") Long hsListId
  ) {
    log.info("from={} message={}", from, message);
    log.info("other fields: firstName={}, lastName={}, email={}", firstName, lastName, email);

    // add the new contact
    ContactBuilder contactBuilder = new ContactBuilder()
        .phone(from)
        .firstName(firstName)
        .lastName(lastName)
        .email(email);

    try {
      Contact contact = HubSpotClientFactory.client().contacts().insert(contactBuilder);
      log.info("created HubSpot contact {}", contact.getVid());
      addToHubSpotList(contact.getVid(), hsListId);
    } catch (DuplicateContactException e) {
      log.info("contact already existed in HubSpot");
      addToHubSpotList(e.getVid(), hsListId);
    } catch (HubSpotException e) {
      log.error("HubSpot failed for an unknown reason: {}", e.getMessage());
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
      @QueryParam("owner") String owner) {
    log.info("from={} owner={}", from, owner);

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

  private void addToHubSpotList(long contactVid, Long hsListId) {
    if (hsListId == null || hsListId == 0L) {
      log.info("explicit HubSpot list ID not provided; using the default {}", DEFAULT_HUBSPOT_SMS_LIST_ID);
      hsListId = DEFAULT_HUBSPOT_SMS_LIST_ID;
    }
    // note that HubSpot auto-prevents duplicate entries in lists
    HubSpotClientFactory.client().lists().addContactToList(hsListId, contactVid);
    log.info("added HubSpot contact {} to list {}", contactVid, hsListId);
  }
}
