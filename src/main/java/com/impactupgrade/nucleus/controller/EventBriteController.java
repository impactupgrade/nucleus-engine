/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.EventBriteClient;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmCampaign;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.util.Utils;

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

    // takes a while, so spin it off as a new thread
    Runnable thread = () -> {
      try {
        String jobName = "Eventbrite Event";
        env.startJobLog(JobType.EVENT, "webhook", jobName, "Eventbrite");
        env.logJobInfo("received event {}: {}", eventType, webhookPayload.apiUrl);
        processEvent(eventType, webhookPayload, env);
        env.endJobLog(JobStatus.DONE);
      } catch (Exception e) {
        env.logJobError("failed to process the Eventbrite event", e);
        env.logJobError(e.getMessage());
        env.endJobLog(JobStatus.FAILED);
      }
    };
    new Thread(thread).start();

    return Response.status(200).build();
  }

  protected void processEvent(String eventType, WebhookPayload webhookPayload, Environment env) throws Exception {
    CrmService crmService = env.primaryCrmService();
    EventBriteClient eventBriteClient = env.eventBriteClient();

    switch (eventType) {
      case "attendee.updated" -> {
        EventBriteClient.Attendee attendee = eventBriteClient.getAttendee(webhookPayload.apiUrl);

        // attendee can have a partial profile, which could have "Info Requested" as the email/name
        if (Strings.isNullOrEmpty(attendee.profile.email) || !attendee.profile.email.contains("@")) {
          env.logJobInfo("skipping registration with invalid email: {}", attendee.profile.email);
          return;
        }

        CrmContact contact = toCrmContact(attendee);
        // LIFO
        CrmContact existingContact = crmService.getContactsByEmails(List.of(contact.email))
            .stream().reduce((first, second) -> second).orElse(null);
        // Unlikely that they wouldn't already exist, but keep this here as a sanity check.
        upsertCrmContact(contact, Optional.ofNullable(existingContact), crmService);

        // make sure it's not an event that existed prior to our integration
        Optional<CrmCampaign> _campaign = crmService.getCampaignByExternalReference(attendee.eventId);
        CrmCampaign campaign = null;
        if (_campaign.isEmpty()) {
          EventBriteClient.Event event = eventBriteClient.getEvent("https://www.eventbriteapi.com/v3/events/" + attendee.eventId + "/");
          campaign = upsertCrmCampaign(event, crmService, env);
        } else {
          campaign = _campaign.get();
        }

        addContactToCampaign(contact, campaign, crmService, env);
      }

      // Skipping event.created entirely, since it's immediately followed up with an event.updated.
      case "event.updated", "event.published" -> {
        EventBriteClient.Event event = eventBriteClient.getEvent(webhookPayload.apiUrl);
        upsertCrmCampaign(event, crmService, env);
      }
      case "event.unpublished" -> {
        EventBriteClient.Event event = eventBriteClient.getEvent(webhookPayload.apiUrl);

        Optional<CrmCampaign> existingCampaign = crmService.getCampaignByExternalReference(event.id);
        if (existingCampaign.isPresent()) {
          crmService.deleteCampaign(existingCampaign.get().id);
        }
      }

      case "order.placed" -> {
        env.logJobInfo("waiting 10 sec to process order.placed, giving attendee.updated time to create the contact...");
        Thread.sleep(10000);

        EventBriteClient.Order order = eventBriteClient.getOrder(webhookPayload.apiUrl, "attendees");
        processNewOrder(order, crmService, env);
      }

      case "order.refunded" -> {
        EventBriteClient.Order order = eventBriteClient.getOrder(webhookPayload.apiUrl, "attendees");

        // TODO: Should we attempt to get accountId/contactId? Does the webhook give us the contact or email?
        Optional<CrmDonation> existingCrmDonation = crmService.getDonationsByTransactionIds(List.of(order.id), null, null)
            .stream().findFirst();
        if (existingCrmDonation.isPresent()) {
          CrmDonation crmDonation = existingCrmDonation.get();
          crmDonation.status = CrmDonation.Status.FAILED;
          crmService.updateDonation(crmDonation);
        }
      }

      case "order.updated" -> {
        EventBriteClient.Order order = eventBriteClient.getOrder(webhookPayload.apiUrl, "attendees");

        // TODO: Should we attempt to get accountId/contactId? Does the webhook give us the contact or email?
        Optional<CrmDonation> existingCrmDonation = crmService.getDonationsByTransactionIds(List.of(order.id), null, null)
            .stream().findFirst();

        if (existingCrmDonation.isPresent()) {
          CrmDonation crmDonation = toCrmDonation(order);
          // TODO: update only specific fields to avoid "overwrite"?
          crmDonation.id = existingCrmDonation.get().id;
          crmService.updateDonation(crmDonation);
        } else {
          processNewOrder(order, crmService, env);
        }
      }

      default -> {
        System.out.println("Unknown event type: " + eventType);
      }
    }
  }

  protected void processNewOrder(EventBriteClient.Order order, CrmService crmService, Environment env) throws Exception {
    // make sure it's not an event that existed prior to our integration
    Optional<CrmCampaign> campaign = crmService.getCampaignByExternalReference(order.eventId);
    if (campaign.isEmpty()) {
      EventBriteClient.Event event = env.eventBriteClient().getEvent("https://www.eventbriteapi.com/v3/events/" + order.eventId + "/");
      upsertCrmCampaign(event, crmService, env);
    }

    // attendee can have a partial profile, which could have "Info Requested" as the email/name
    List<EventBriteClient.Attendee> attendees = order.attendees.stream()
        .filter(attendee -> !Strings.isNullOrEmpty(attendee.profile.email) && attendee.profile.email.contains("@"))
        .toList();
    if (attendees.isEmpty()) {
      env.logJobInfo("skipping order with invalid email address(es)");
      return;
    }

    // TODO: which attendee/contact to use for donation?
    // TODO: 1 donation per 1 attendee?
    // LIFO
    Optional<CrmContact> crmContact = crmService.getContactsByEmails(List.of(attendees.get(0).profile.email))
        .stream().reduce((first, second) -> second);
    if (crmContact.isEmpty()) {
      env.logJobInfo("skipping order with missing CRM contact");
      return;
    }

    // Use instead the display_price field (if the Ticket Class include_fee field is used) // ?
    if (order.costs.gross.value > 0.0) {
      CrmDonation crmDonation = toCrmDonation(order);
      crmDonation.contact = crmContact.get();
      crmDonation.account = crmContact.get().account;

      crmDonation.campaignId = campaign.get().id;

      crmService.insertDonation(crmDonation);
    }
  }

  protected void upsertCrmContact(CrmContact contact, Optional<CrmContact> existingContact, CrmService crmService) throws Exception {
    if (existingContact.isEmpty()) {
      contact.id = crmService.insertContact(contact);
    } else {
      contact.id = existingContact.get().id;
      crmService.updateContact(contact);
    }
  }

  protected CrmCampaign upsertCrmCampaign(EventBriteClient.Event event, CrmService crmService, Environment env) throws Exception {
    CrmCampaign campaign = buildCrmCampaign(event);
    Optional<CrmCampaign> existingCampaign = crmService.getCampaignByExternalReference(event.id);

    if (existingCampaign.isEmpty()) {
      try {
        campaign.id = crmService.insertCampaign(campaign);
      } catch (Exception e) {
        env.logJobInfo("unable to create new campaign: {}", e.getMessage());
      }
    } else {
      campaign.id = existingCampaign.get().id;
      crmService.updateCampaign(campaign);
    }

    return campaign;
  }

  protected CrmCampaign buildCrmCampaign(EventBriteClient.Event event) {
    ZonedDateTime startDate = Utils.getZonedDateTimeFromDateTimeString(event.start.utc);
    ZonedDateTime endDate = Utils.getZonedDateTimeFromDateTimeString(event.end.utc);

    return new CrmCampaign(
        null,
        event.name.text,
        event.id,
        startDate,
        endDate,
        null,
        null,
        event,
        null
    );
  }

  // allows organizations to override this and add custom logic
  protected void addContactToCampaign(CrmContact contact, CrmCampaign campaign, CrmService crmService, Environment env)
      throws Exception {
    crmService.addContactToCampaign(contact, campaign.id, null);
  }

  protected CrmContact toCrmContact(EventBriteClient.Attendee attendee) {
    CrmContact crmContact = new CrmContact();
    crmContact.firstName = attendee.profile.firstName;
    crmContact.lastName = attendee.profile.lastName;
    crmContact.email = attendee.profile.email;
    crmContact.mobilePhone = attendee.profile.cellPhone;
    crmContact.workPhone = attendee.profile.workPhone;
    crmContact.mailingAddress = toCrmAddress(attendee.profile.addresses.home); //?
    return crmContact;
  }

  protected CrmAddress toCrmAddress(EventBriteClient.Address address) {
    CrmAddress crmAddress = new CrmAddress();
    if (address != null) {
      crmAddress.street = address.address1 + " " + address.address2;
      crmAddress.city = address.city;
      crmAddress.postalCode = address.postalCode;
      crmAddress.state = address.region; //?
      crmAddress.country = address.country;
    }
    return crmAddress;
  }

  protected CrmDonation toCrmDonation(EventBriteClient.Order order) {
    CrmDonation crmDonation = new CrmDonation();
    crmDonation.transactionId = order.id;
    crmDonation.description = order.name + " / " + order.email;
    crmDonation.gatewayName = "EventBrite";
    crmDonation.closeDate = ZonedDateTime.parse(order.created);
    if ("placed".equalsIgnoreCase(order.status)) {
      crmDonation.status = CrmDonation.Status.SUCCESSFUL;
    } else if ("refunded".equalsIgnoreCase(order.status)) {
      crmDonation.status = CrmDonation.Status.FAILED;
    }
    crmDonation.url = order.resourceUri;
    crmDonation.amount = order.costs.gross.value / 100.0;
    crmDonation.feeInDollars = (order.costs.eventbriteFee.value + order.costs.paymentFee.value) / 100.0;
    crmDonation.netAmountInDollars = crmDonation.amount - crmDonation.feeInDollars;
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
