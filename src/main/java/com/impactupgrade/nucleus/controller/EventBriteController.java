package com.impactupgrade.nucleus.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.impactupgrade.nucleus.client.EventBriteClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmCampaign;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.apache.commons.collections.CollectionUtils;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Path("/eventbrite")
public class EventBriteController {

  protected final EnvironmentFactory envFactory;

  public EventBriteController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @Path("/webhook")
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.TEXT_PLAIN)
  public Response webhook(String json, @Context HttpServletRequest request) throws Exception {
    Environment env = envFactory.init(request);

    String eventType = request.getHeader("X-Eventbrite-Event");
    WebhookPayload webhookPayload = new ObjectMapper().readValue(json, WebhookPayload.class);

    switch (eventType) {
      case "attendee.updated" -> {
        EventBriteClient.Attendee attendee = new EventBriteClient(env).getAttendee(webhookPayload.apiUrl);
        CrmContact crmContact = toCrmContact(attendee);

        CrmService crmService = env.primaryCrmService();
        CrmContact existingContact = crmService.getContactsByEmails(List.of(crmContact.email))
                .stream().findFirst().orElse(null);
        if (existingContact == null) {
          // TODO: decide if should fail here bcz contact does not exist
          crmService.insertContact(crmContact);
        } else {
          crmContact.id = existingContact.id;
          crmService.updateContact(crmContact);
        }
      }

      case "barcode.checked_in", "barcode.un_checked_in" -> {
        //TODO:?
      }

      case "event.created", "event.updated" -> {
        EventBriteClient.Event event = new EventBriteClient(env).getEvent(webhookPayload.apiUrl);
        CrmCampaign campaign = new CrmCampaign(event.id, event.name.text);
        //TODO: switch to upsert? find existing and update?
        env.primaryCrmService().insertCampaign(campaign);
      }
      case "event.published", "event.unpublished" -> {
        //TODO:?
      }

      case "order.placed" -> {
        EventBriteClient.Order order = new EventBriteClient(env).getOrder(webhookPayload.apiUrl);
        CrmDonation crmDonation = toCrmDonation(order);
        CrmContact crmContact = null;

        List<String> emails = order.attendees.stream().map(attendee -> attendee.profile.email).collect(Collectors.toList());
        //TODO: order can have more than 1 attendee - how to map this case?
        if (CollectionUtils.isNotEmpty(emails)) {
          crmContact = env.primaryCrmService().getContactsByEmails(emails)
                  .stream().findFirst().orElse(null);
        }

        crmDonation.contact = crmContact;
        env.primaryCrmService().insertDonation(crmDonation);
      }

      case "order.refunded", "order.updated" -> {
        EventBriteClient.Order order = new EventBriteClient(env).getOrder(webhookPayload.apiUrl);
        CrmDonation crmDonation = toCrmDonation(order);
        Optional<CrmDonation> existingCrmDonation = env.primaryCrmService().getDonationByTransactionId(order.id);
        if (existingCrmDonation.isPresent()) {
          // TODO: update only specific fields to avoid "overwrite"?
          crmDonation.id = existingCrmDonation.get().id;
          env.primaryCrmService().updateDonation(crmDonation);
        }
      }

      case "organizer.updated" -> {
        //TODO:?
      }

      case "ticket_class.created", "ticket_class.deleted", "ticket_class.updated" -> {
        //TODO:?
      }

      case "venue.update" -> {
        //TODO:?
      }

      default -> {
        System.out.println("Unknown event type: " + eventType);
      }

    }
    return Response.status(200).entity(json).build();
  }

  private CrmContact toCrmContact(EventBriteClient.Attendee attendee) {
    CrmContact crmContact = new CrmContact();
    crmContact.firstName = attendee.profile.firstName;
    crmContact.lastName = attendee.profile.lastName;
    crmContact.email = attendee.profile.email;
    crmContact.mobilePhone = attendee.profile.cellPhone;
    crmContact.workPhone = attendee.profile.workPhone;
    crmContact.mailingAddress = toCrmAddress(attendee.profile.addresses.home); //?
    return crmContact;
  }

  private CrmAddress toCrmAddress(EventBriteClient.Address address) {
    CrmAddress crmAddress = new CrmAddress();
    crmAddress.street = address.address1 + " " + address.address2;
    crmAddress.city = address.city;
    crmAddress.postalCode = address.postalCode;
    crmAddress.state = address.region; //?
    crmAddress.country = address.country;
    return crmAddress;
  }

  private CrmDonation toCrmDonation(EventBriteClient.Order order) {
    CrmDonation crmDonation = new CrmDonation();
    crmDonation.transactionId = order.id;
    crmDonation.secondaryId = order.event.id; // ?
    crmDonation.description = order.name + "/" + order.email;
    crmDonation.gatewayName = "Event Brite";
    crmDonation.closeDate = ZonedDateTime.parse(order.created);
    if ("placed".equalsIgnoreCase(order.status)) {
      crmDonation.status = CrmDonation.Status.SUCCESSFUL;
    } else if ("refunded".equalsIgnoreCase(order.status)) {
      crmDonation.status = CrmDonation.Status.REFUNDED;
    }
    crmDonation.url = order.resourceUri;
    crmDonation.originalAmountInDollars = order.costs.basePrice.value / 100.0;
    crmDonation.originalCurrency = order.costs.basePrice.currency;
    //TODO: taxes?

    return crmDonation;
  }

  //{
  //  "api_url": "https://www.eventbriteapi.com/v3/events/794181005767/",
  //  "config": {
  //    "action": "event.updated",
  //    "endpoint_url": "https://9fd8-45-12-27-52.ngrok-free.app/api/eventbrite/webhook",
  //    "user_id": "1945575527183",
  //    "webhook_id": "12064831"
  //  }
  //}
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class WebhookPayload {
    @JsonProperty("api_url")
    public String apiUrl;
    public WebhookConfig config;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class WebhookConfig {
    public String action;
    @JsonProperty("endpoint_url")
    public String endpointUrl;
    @JsonProperty("user_id")
    public String userId;
    @JsonProperty("webhook_id")
    public String webhookId;
  }
}
