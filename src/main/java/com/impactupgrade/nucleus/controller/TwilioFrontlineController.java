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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
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

  @Path("/callback/crm")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_JSON)
  public Response crmCallback(
      @FormParam("Location") String location,
      @FormParam("Worker") String workerIdentity,
      @FormParam("PageSize") Integer pageSize,
      @FormParam("NextPageToken") String nextPageToken,
      @FormParam("CustomerId") String customerId,
      @FormParam("Query") String searchQuery,
      @Context HttpServletRequest request
  ) throws Exception {
    log.info("location={}", location);
    Environment env = envFactory.init(request);

    FrontlineCrmResponse frontlineResponse = new FrontlineCrmResponse();

    switch (location) {
      case "GetCustomerDetailsByCustomerId":
        Optional<CrmContact> crmContact = env.primaryCrmService().getContactById(customerId);
        if (crmContact.isEmpty()) {
          // TODO: theoretically not possible, since the app wouldn't get here if they didn't exist, but return an error anyway?
        }
        frontlineResponse.objects.customer = toFrontlineCustomer(crmContact.get(), env);
        break;
      case "GetCustomersList":
        // TODO (from Scott): Worker is an email address in all their examples. I'm pretty sure this maps to the email you
        //  log into the mobile app with. But that might depend on the SSO you use, and docs are unclear, only saying it's a
        //  string of the "app user identity". https://www.twilio.com/docs/frontline/data-transfer-objects#customer
        Optional<CrmUser> owner = env.primaryCrmService().getUserByEmail(workerIdentity);
        if (owner.isEmpty()) {
          log.error("unexpected owner: " + workerIdentity);
          return Response.status(422).build();
        }
        String ownerId = owner.get().id();

        ContactSearch contactSearch = new ContactSearch();
        contactSearch.keywords = searchQuery;
        contactSearch.ownerId = ownerId;
        contactSearch.pageSize = pageSize;
        contactSearch.pageToken = nextPageToken;
        PagedResults<CrmContact> crmContacts = env.primaryCrmService().searchContacts(contactSearch);
        frontlineResponse.objects.customers = crmContacts.getResults().stream().map(c -> toFrontlineCustomer(c, env)).collect(Collectors.toList());
        if (!Strings.isNullOrEmpty(searchQuery)) {
          frontlineResponse.objects.searchable = true;
        }
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

  private static FrontlineCustomer toFrontlineCustomer(CrmContact crmContact, Environment env) {
    FrontlineCustomer frontlineCustomer = new FrontlineCustomer();
    frontlineCustomer.customer_id = crmContact.id;
    // TODO: May want phone/email or physical location to differentiate common names.
    frontlineCustomer.display_name = crmContact.fullName();

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
    String crmName = capitalize(env.primaryCrmService().name());
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

    frontlineCustomer.details.title = "Profile Snapshot";
    // TODO: This will need to be super configurable!
    frontlineCustomer.details.content = "TODO1\nTODO2\n\nTODO3\nTODO4";

    // TODO: See note above. Not sure if this is supposed to be the ID, email, username, or something else.
    frontlineCustomer.worker = crmContact.ownerId;

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
    // TODO: quickstart had this as an integer, but docs say any format is usable -- confirm
    public String customer_id;
    public String display_name;
    public List<FrontlineChannel> channels = new ArrayList<>();
    public List<FrontlineLink> links = new ArrayList<>();
    public FrontlineDetails details = new FrontlineDetails();
    public String worker;
    // TODO: quickstart had an avatar field, but no mention in docs
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
    log.info("location={}", location);
    Environment env = envFactory.init(request);

    switch (location) {
      case "GetProxyAddress":
        FrontlineOutgoingConversationResponse frontlineResponse = new FrontlineOutgoingConversationResponse();
        // TODO: For now, likely to be env.json's single sender, but that's going to need to change quickly.
        //  Choose from multiple based on the worker's defined proxy number? And take channels into consideration?
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
}
