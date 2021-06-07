package com.impactupgrade.common.twilio;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.impactupgrade.common.crm.hubspot.HubSpotClientFactory;
import com.impactupgrade.common.security.SecurityUtil;
import com.impactupgrade.common.util.Utils;
import com.impactupgrade.integration.hubspot.v1.builder.ContactBuilder;
import com.impactupgrade.integration.hubspot.v1.exception.DuplicateContactException;
import com.impactupgrade.integration.hubspot.v1.exception.HubSpotException;
import com.impactupgrade.integration.hubspot.v1.model.Contact;
import com.impactupgrade.integration.hubspot.v1.model.ContactArray;
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
public class TwilioController {

  private static final Logger log = LogManager.getLogger(TwilioController.class);

  private static final String DEFAULT_HUBSPOT_SMS_LIST_ID = System.getenv("HUBSPOT_SMSLISTID");

  private static final String TWILIO_SENDER_PN = System.getenv("TWILIO_SENDER_PN");

  static {
    if (!Strings.isNullOrEmpty(System.getenv("TWILIO_ACCOUNTSID"))) {
      Twilio.init(System.getenv("TWILIO_ACCOUNTSID"), System.getenv("TWILIO_AUTHTOKEN"));
    }
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
        ContactArray contactArray = HubSpotClientFactory.client().contactList().getContactsInList(listId);
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
                    new PhoneNumber(TWILIO_SENDER_PN),
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

  // TODO: Use abstract CRMs, not HS directly!

  /**
   * This webhook serves multiple purposes. It can be used directly on a Twilio number, receiving standard From/Body
   * pairs as texts are received. It can also be used by Twilio Studio flows, Twilio Functions, etc. for more complex
   * interactions. Try to make use of the standard form params whenever possible to maintain the overlap!
   *
   * TODO: This should use the abstract CRM and not assume HS!
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
      @FormParam("FullName") String fullName,
      @FormParam("Email") String email,
      @FormParam("HubSpotListId") Long hsListId
  ) {
    log.info("from={} message={}", from, message);
    log.info("other fields: firstName={}, lastName={}, fullName={}, email={}, hsListId={}",
        firstName, lastName, fullName, email, hsListId);

    // Hubspot doesn't seem to support country codes when phone numbers are used to search. Strip it off.
    from = from.replace("+1", "");

    if (!Strings.isNullOrEmpty(fullName)) {
      String[] split = Utils.fullNameToFirstLast(fullName);
      firstName = split[0];
      lastName = split[1];
    }

    // First, look for an existing contact based off the phone number. Important to use the PN since the
    // Twilio Studio flow has some flavors that assume the contact is already in HS, so only the PN (From) will be
    // provided. Other flows allow email to be optional.
    Contact contact = null;
    ContactArray contacts = HubSpotClientFactory.client().contact().search(from);
    if (contacts.getContacts().isEmpty()) {
      // Didn't exist, so attempt to create it.
      ContactBuilder contactBuilder = new ContactBuilder()
          .phone(from)
          .firstName(firstName)
          .lastName(lastName);

      // They'll send "no", etc. for email if they don't want to opt-in. Simply look for @, to be flexible.
      if (email != null && email.contains("@")) {
        contactBuilder.email(email);
      }

      try {
        contact = HubSpotClientFactory.client().contact().insert(contactBuilder);
        log.info("created HubSpot contact {}", contact.getVid());
      } catch (DuplicateContactException e) {
        // likely due to an email collision...
        log.info("contact already existed in HubSpot: {}", e.getVid());
      } catch (HubSpotException e) {
        log.error("HubSpot failed for an unknown reason: {}", e.getMessage());
      }
    } else {
      // Existed, so use it
      contact = contacts.getContacts().get(0);
      log.info("contact already existed in HubSpot: {}", contact.getVid());

      boolean update = false;
      ContactBuilder contactBuilder = new ContactBuilder();
      if ((contact.getProperties().getFirstname() == null || Strings.isNullOrEmpty(contact.getProperties().getFirstname().getValue()))
          && !Strings.isNullOrEmpty(firstName)) {
        log.info("contact {} missing firstName; updating it...", contact.getVid());
        update = true;
        contactBuilder.firstName(firstName);
      }
      if ((contact.getProperties().getLastname() == null || Strings.isNullOrEmpty(contact.getProperties().getLastname().getValue()))
          && !Strings.isNullOrEmpty(lastName)) {
        log.info("contact {} missing lastName; updating it...", contact.getVid());
        update = true;
        contactBuilder.lastName(lastName);
      }
      if ((contact.getProperties().getEmail() == null || Strings.isNullOrEmpty(contact.getProperties().getEmail().getValue()))
          && !Strings.isNullOrEmpty(email)) {
        log.info("contact {} missing email; updating it...", contact.getVid());
        update = true;
        contactBuilder.email(email);
      }
      if (update) {
        HubSpotClientFactory.client().contact().updateById(contactBuilder, contact.getVid() + "");
      }
    }

    if (contact != null) {
      addToHubSpotList(contact.getVid(), hsListId);
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
      if (Strings.isNullOrEmpty(DEFAULT_HUBSPOT_SMS_LIST_ID)) {
        log.info("explicit HubSpot list ID not provided; skipping the list insert...");
        return;
      } else {
        log.info("explicit HubSpot list ID not provided; using the default {}", DEFAULT_HUBSPOT_SMS_LIST_ID);
        hsListId = Long.parseLong(DEFAULT_HUBSPOT_SMS_LIST_ID);
      }
    }
    // note that HubSpot auto-prevents duplicate entries in lists
    HubSpotClientFactory.client().contactList().addContactToList(hsListId, contactVid);
    log.info("added HubSpot contact {} to list {}", contactVid, hsListId);
  }

  // TODO: Temporary method to prototype an MMS replacement of the mobile app. In the future,
  // this can be molded into an API...
//  public void mmsDemo(String to) {
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
//        new PhoneNumber(to),
//        new PhoneNumber("+12607862676"),
//        "Finally, a call to action. Learn more about how we’re working to rescue the enslaved: https://www.destinyrescue.org/us/what-we-do/rescue/"
//    ).create();
//  }
}