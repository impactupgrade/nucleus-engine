/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.twilio.rest.conversations.v1.conversation.Participant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.capitalize;

@Path("/twilio/frontline")
public class TwilioFrontlineController {

  private static final Logger log = LogManager.getLogger(TwilioFrontlineController.class);

  protected final EnvironmentFactory envFactory;

  public TwilioFrontlineController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  // https://www.twilio.com/docs/frontline/my-customers
  @Path("/callback/crm")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response crmCallback(
      @FormParam("Location") String location,
      @FormParam("Worker") String workerIdentity,
      @FormParam("PageSize") Integer pageSize,
      @FormParam("NextPageToken") String nextPageToken,
      // Using the above for offset-based pagination, but anchor pagination is supported if needed
//      @FormParam("Anchor") String pageLastCustomerId,
      @FormParam("CustomerId") String customerId,
      @FormParam("Query") String searchQuery,
      @Context HttpServletRequest request
  ) throws Exception {
    log.info("location={} workerIdentity={} pageSize={} nextPageToken={} customerId={} searchQuery={}", location, workerIdentity, pageSize, nextPageToken, customerId, searchQuery);
    Environment env = envFactory.init(request);
    CrmService crmService = env.primaryCrmService();
    String crmName = capitalize(crmService.name());

    // TODO: https://www.twilio.com/docs/frontline/callbacks-security

    FrontlineCrmResponse frontlineResponse = new FrontlineCrmResponse();

    switch (location) {
      case "GetCustomerDetailsByCustomerId":
        Optional<CrmContact> crmContact = crmService.getContactById(customerId);
        frontlineResponse.objects.customer = toFrontlineCustomer(crmContact.get(), workerIdentity, crmName);
        break;
      case "GetCustomersList":
        // TODO (from Scott): Worker is an email address in all their examples. I'm pretty sure this maps to the email you
        //  log into the mobile app with. But that might depend on the SSO you use, and docs are unclear, only saying it's a
        //  string of the "app user identity". https://www.twilio.com/docs/frontline/data-transfer-objects#customer
        Optional<CrmUser> owner = crmService.getUserByEmail(workerIdentity);
        if (owner.isEmpty()) {
          log.error("unexpected owner: " + workerIdentity);
          return Response.status(422).build();
        }
        String ownerId = owner.get().id();

        ContactSearch contactSearch = new ContactSearch();
        contactSearch.keywords = searchQuery;
        // TODO: Disabling this for now! Easier to have access to everyone during testing.
//        contactSearch.ownerId = ownerId;
        contactSearch.hasPhone = true;
        contactSearch.pageSize = pageSize;
        contactSearch.pageToken = nextPageToken;
        PagedResults<CrmContact> crmContacts = crmService.searchContacts(contactSearch);
        frontlineResponse.objects.customers = crmContacts.getResults().stream().map(c -> toFrontlineCustomer(c, workerIdentity, crmName)).collect(Collectors.toList());
        // TODO: If I'm reading https://www.twilio.com/docs/frontline/my-customers#customer-search correctly,
        //  this always needs to be true in order to tell Frontline that this service handles custom searches.
//        if (!Strings.isNullOrEmpty(searchQuery)) {
          frontlineResponse.objects.searchable = true;
//        }
        if (crmContacts.hasMorePages()) {
          frontlineResponse.objects.next_page_token = crmContacts.getNextPageToken();
        }
        break;
      default:
        log.error("unexpected location: " + location);
        return Response.status(422).build();
    }

    return Response.ok().entity(frontlineResponse).build();
  }

  private static FrontlineCustomer toFrontlineCustomer(CrmContact crmContact, String workerIdentity, String crmName) {
    FrontlineCustomer frontlineCustomer = new FrontlineCustomer();
    frontlineCustomer.customer_id = crmContact.id;
    frontlineCustomer.display_name = crmContact.fullName();
    if (!Strings.isNullOrEmpty(crmContact.phoneNumberForSMS())) {
      frontlineCustomer.display_name += " :: " + crmContact.phoneNumberForSMS();
    }
    // TODO: love this idea, but they don't support text wrapping nor newlines, so the real estate doesn't support this much text
//    if (!Strings.isNullOrEmpty(crmContact.address.stateAndCountry())) {
//      frontlineCustomer.display_name += " :: " + crmContact.address.stateAndCountry();
//    }

    // TODO: This will show up under Contact Details, but clicking it does nothing. Not sure if it's intended to
    //  act like a mailto: link. For now, skipping this and centering on the mailto: in the links.
//    if (!Strings.isNullOrEmpty(crmContact.email)) {
//      FrontlineChannel frontlineChannel = new FrontlineChannel();
//      frontlineChannel.type = "email";
//      frontlineChannel.value = crmContact.email;
//      frontlineCustomer.channels.add(frontlineChannel);
//    }
    if (!Strings.isNullOrEmpty(crmContact.phoneNumberForSMS())) {
      FrontlineChannel frontlineChannel = new FrontlineChannel();
      frontlineChannel.type = "sms";
      frontlineChannel.value = crmContact.phoneNumberForSMS();
      frontlineCustomer.channels.add(frontlineChannel);
    }
    // TODO: WhatsApp?
    // TODO: Chat?

    FrontlineLink crmLink = new FrontlineLink();
    crmLink.type = crmName;
    crmLink.display_name = "Contact's full profile in " + crmName;
    crmLink.value = crmContact.crmUrl;
    frontlineCustomer.links.add(crmLink);

    if (!Strings.isNullOrEmpty(crmContact.email)) {
      FrontlineLink emailLink = new FrontlineLink();
      emailLink.type = "Email";
      emailLink.display_name = crmContact.email;
      emailLink.value = "mailto:" + crmContact.email;
      frontlineCustomer.links.add(emailLink);
    }

    if (!Strings.isNullOrEmpty(crmContact.address.street)) {
      FrontlineLink addressLink = new FrontlineLink();
      addressLink.type = "Address";
      addressLink.display_name = crmContact.address.toString();
      try {
        addressLink.value = "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(crmContact.address.toString(), StandardCharsets.UTF_8.toString());
      } catch (UnsupportedEncodingException e) {
        // will never happen
      }
      frontlineCustomer.links.add(addressLink);
    }

    frontlineCustomer.details.title = "Profile Snapshot";
    // TODO: This will need to be super configurable!
    double totalDonations = crmContact.totalDonationAmount == null ? 0.0 : crmContact.totalDonationAmount;
    int numberDonations = crmContact.numDonations == null ? 0 : crmContact.numDonations;
    String lastDate = crmContact.lastDonationDate == null ? "n/a" : new SimpleDateFormat("yyyy-MM-dd").format(crmContact.lastDonationDate.getTime());
    String firstDate = crmContact.firstDonationDate == null ? "n/a" : new SimpleDateFormat("yyyy-MM-dd").format(crmContact.firstDonationDate.getTime());
    String notes = Strings.isNullOrEmpty(crmContact.notes) ? "" : "Notes:\n" + crmContact.notes;
    frontlineCustomer.details.content = "Total Donations: $" + new DecimalFormat("#.##").format(totalDonations) + "\nNumber of Donations: " + numberDonations + "\nLast Donation Date: " + lastDate + "\nFirst Donation Date: " + firstDate + "\n\n" + notes;

    frontlineCustomer.worker = workerIdentity;

    return frontlineCustomer;
  }

  // being lazy and using camel-case to match the explicit field names
  public static class FrontlineCrmResponse {
    public FrontlineObjects objects = new FrontlineObjects();
  }
  public static class FrontlineObjects {
    public FrontlineCustomer customer;
    public List<FrontlineCustomer> customers;
    public String next_page_token;
    public Boolean searchable;
  }
  public static class FrontlineCustomer {
    public String customer_id;
    public String display_name;
    public List<FrontlineChannel> channels = new ArrayList<>();
    public List<FrontlineLink> links = new ArrayList<>();
    public FrontlineDetails details = new FrontlineDetails();
    public String worker;
    public String avatar;
  }
  public static class FrontlineChannel {
    public String type;
    public String value;
  }
  public static class FrontlineLink {
    public String type;
    // can be both https and mailto
    public String value;
    public String display_name;
  }
  public static class FrontlineDetails {
    public String title;
    public String content;
  }
  public static class FrontlineConversation {
    public String friendlyName;
    public FrontlineAttributes attributes = new FrontlineAttributes();
  }
  public static class FrontlineUser {
    public String friendlyName;
    public String identity;
    public FrontlineAttributes attributes = new FrontlineAttributes();
  }
  public static class FrontlineParticipant {
    public FrontlineAttributes attributes = new FrontlineAttributes();
  }
  public static class FrontlineAttributes {
    public String avatar;
    public String customer_id;
    public String display_name;
  }

  // https://www.twilio.com/docs/frontline/outgoing-conversations
  @Path("/callback/outgoing-conversation")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response outgoingConversationCallback(
      @FormParam("Location") String location,
      @FormParam("Worker") String workerIdentity,
      @FormParam("CustomerId") String customerId,
      @FormParam("ChannelType") String customerChannel,
      @FormParam("ChannelValue") String customerAddress,
      @Context HttpServletRequest request
  ) throws Exception {
    log.info("location={} workerIdentity={} customerId={} customerChannel={} customerAddress={}", location, workerIdentity, customerId, customerChannel, customerAddress);
    Environment env = envFactory.init(request);

    // TODO: https://www.twilio.com/docs/frontline/callbacks-security

    switch (location) {
      case "GetProxyAddress":
        FrontlineOutgoingConversationResponse frontlineResponse = new FrontlineOutgoingConversationResponse();
        // TODO: For now, likely to be env.json's single sender, but that's going to need to change quickly.
        //  Choose from multiple based on the worker's defined proxy number? And take channels into consideration?
        // TODO: Also, this will change for group messaging!
        frontlineResponse.proxy_address = env.getConfig().twilio.senderPn;
        return Response.ok().entity(frontlineResponse).build();
      default:
        log.error("unexpected location: " + location);
        return Response.status(422).build();
    }
  }

  // being lazy and using camel-case to match the explicit field names
  public static class FrontlineOutgoingConversationResponse {
    public String proxy_address;
  }

  // TODO: Does the SFDC API support doing this programmatically? https://www.twilio.com/docs/frontline/sso/salesforce

  // Frontline onConversationRoute: lookup worker and add to conversation (or handle dynamic routing)
  // https://www.twilio.com/docs/frontline/handle-incoming-conversations
  @Path("/callback/routing")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response routingCallback(
      @FormParam("ConversationSid") String conversationSid,
      @FormParam("FriendlyName") String friendlyName,
      @FormParam("UniqueName") String uniqueName,
      // raw JSON string
      @FormParam("Attributes") String attributesJson,
      @FormParam("ConversationServiceSid") String conversationServiceSid,
      @FormParam("MessagingBinding.ProxyAddress") String proxyAddress,
      @FormParam("MessagingBinding.Address") String customerAddress,
      // TODO: Group MMS
//      @FormParam("MessagingBinding.ProjectedAddress") String projectedAddress,
      // TODO: Group MMS
//      @FormParam("MessagingBinding.AuthorAddress") String authorAddress,
      @FormParam("State") String state,
      // TODO: DateCreated & DateUpdated (string, ISO8601 time)
      @Context HttpServletRequest request
  ) throws Exception {
    log.info("conversationSid={} friendlyName={} uniqueName={} attributesJson={} conversationServiceSid={} proxyAddress={} customerAddress={} state={}", conversationSid, friendlyName, uniqueName, attributesJson, conversationServiceSid, proxyAddress, customerAddress, state);
    Environment env = envFactory.init(request);
    CrmService crmService = env.primaryCrmService();

    // TODO: https://www.twilio.com/docs/frontline/callbacks-security

    // TODO: will need tweaked for WhatsApp
    // TODO: verify the phone number formatting works in the search
    Optional<CrmContact> crmContact = crmService.searchContacts(ContactSearch.byPhone(customerAddress)).getSingleResult();
    if (crmContact.isPresent()) {
      Optional<CrmUser> crmOwner = crmService.getUserById(crmContact.get().ownerId);
      env.twilioClient().createConversationParticipant(conversationSid, crmOwner.get().email());
      return Response.ok().build();
    } else {
      // TODO: fall back to random routing? or a default worker?
      log.error("could not find CrmContact: " + customerAddress);
      return Response.status(422).build();
    }
  }

  // Conversations onConversationAdd: set conversation name and avatar
  // Conversations onParticipantAdded: set the customer id, avatar, and display name
  // https://www.twilio.com/docs/frontline/conversations-webhooks
  // TODO: https://www.twilio.com/docs/conversations/conversations-webhooks#onconversationadd
  // TODO: https://www.twilio.com/docs/conversations/conversations-webhooks#onparticipantadded
  @Path("/callback/conversations")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response conversationsCallback(
      @FormParam("EventType") String eventType,
      @FormParam("MessagingBinding.Address") String customerAddress,
      @FormParam("ConversationSid") String conversationSid,
      @FormParam("ParticipantSid") String participantSid,
      @FormParam("Identity") String identity,
      @Context HttpServletRequest request
  ) throws Exception {
    log.info("eventType={} customerAddress={} conversationSid={} participantSid={} identity={}", eventType, customerAddress, conversationSid, participantSid, identity);
    Environment env = envFactory.init(request);
    CrmService crmService = env.primaryCrmService();

    // TODO: https://www.twilio.com/docs/frontline/callbacks-security

    switch (eventType) {
      case "onConversationAdd":
        // TODO: In example code, no customerAddress seemed to mean "not an incoming conversation". But this seems off?
        if (Strings.isNullOrEmpty(customerAddress)) {
          return Response.status(200).build();
        }

        // TODO: will need tweaked for WhatsApp
        // TODO: verify the phone number formatting works in the search
        Optional<CrmContact> crmContact = crmService.searchContacts(ContactSearch.byPhone(customerAddress)).getSingleResult();
        if (crmContact.isPresent()) {
          FrontlineConversation frontlineConversation = new FrontlineConversation();
          // TODO: Append the phone number too? Phone number only if no name?
          frontlineConversation.friendlyName = crmContact.get().fullName();
          // TODO
//          frontlineConversation.attributes.avatar = ;
          return Response.ok().entity(frontlineConversation).build();
        } else {
          log.error("could not find CrmContact: " + customerAddress);
          return Response.status(422).build();
        }
      case "onParticipantAdded":
        // TODO: Also saw this in example code. Assuming it means that a worker being added is a no-op action.
        if (Strings.isNullOrEmpty(customerAddress) || !Strings.isNullOrEmpty(identity)) {
          return Response.status(200).build();
        }

        // TODO: will need tweaked for WhatsApp
        // TODO: verify the phone number formatting works in the search
        crmContact = crmService.searchContacts(ContactSearch.byPhone(customerAddress)).getSingleResult();
        if (crmContact.isPresent()) {
          Participant participant = Participant.fetcher(conversationSid, participantSid).fetch();
          JSONObject attributes = new JSONObject(participant.getAttributes())
              // TODO
//              .append("avatar", )
              .append("customer_id", crmContact.get().id)
              // TODO: Append the phone number too? Phone number only if no name?
              .append("display_name", crmContact.get().fullName());
          // TODO: ensure the JSON attributes formatting is correct
          Participant.updater(conversationSid, participantSid).setAttributes(attributes.toString());
        } else {
          log.error("could not find CrmContact: " + customerAddress);
          return Response.status(422).build();
        }
      default:
        log.error("unexpected eventType: " + eventType);
        return Response.status(422).build();
    }
  }

  // TODO: WA templates
//  @Path("/callback/templates")
//  @POST
//  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
//  @Produces(MediaType.APPLICATION_JSON)
//  public Response templatesCallback(
//      @Context HttpServletRequest request
//  ) throws Exception {
//
//  }

  // TODO: Use https://www.twilio.com/docs/frontline/deep-linking as a CRM field so they can open the app from it?
}
