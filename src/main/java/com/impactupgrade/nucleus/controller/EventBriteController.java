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
import java.util.Map;
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

  private void processEvent(String eventType, WebhookPayload webhookPayload, Environment env) throws Exception {
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

        CrmContact crmContact = toCrmContact(attendee);
        CrmContact existingContact = crmService.getContactsByEmails(List.of(crmContact.email))
            .stream().findFirst().orElse(null);
        // Unlikely that they wouldn't already exist, but keep this here as a sanity check.
        upsertCrmContact(crmContact, Optional.ofNullable(existingContact), crmService);

        // make sure it's not an event that existed prior to our integration
        Optional<CrmCampaign> campaign = crmService.getCampaignByExternalReference(attendee.eventId);
        if (campaign.isEmpty()) {
          EventBriteClient.Event event = eventBriteClient.getEvent("https://www.eventbriteapi.com/v3/events/" + attendee.eventId + "/");
          upsertCrmCampaign(event, crmService);
        }

        crmService.addContactToCampaign(crmContact, campaign.get().id);
      }

      // Skipping event.created entirely, since it's immediately followed up with an event.updated.
      case "event.updated", "event.published" -> {
        EventBriteClient.Event event = eventBriteClient.getEvent(webhookPayload.apiUrl);
        upsertCrmCampaign(event, crmService);
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

  private void upsertCrmContacts(List<CrmContact> contacts, CrmService crmService) throws Exception {
    List<String> emails = contacts.stream()
            .map(crmContact -> crmContact.email)
            .toList();
    List<CrmContact> existingContacts = crmService.getContactsByEmails(emails);
    Map<String, CrmContact> contactsByEmails = existingContacts.stream()
            .collect(Collectors.toMap(crmContact -> crmContact.email, crmContact -> crmContact));

    for (CrmContact crmContact : contacts) {
      Optional<CrmContact> existingContact = Optional.ofNullable(contactsByEmails.get(crmContact.email));
      upsertCrmContact(crmContact, existingContact, crmService);
    }
  }

  private void processNewOrder(EventBriteClient.Order order, CrmService crmService, Environment env) throws Exception {
    // make sure it's not an event that existed prior to our integration
    Optional<CrmCampaign> campaign = crmService.getCampaignByExternalReference(order.eventId);
    if (campaign.isEmpty()) {
      EventBriteClient.Event event = env.eventBriteClient().getEvent("https://www.eventbriteapi.com/v3/events/" + order.eventId + "/");
      upsertCrmCampaign(event, crmService);
    }

    // attendee can have a partial profile, which could have "Info Requested" as the email/name
    List<CrmContact> crmContacts = order.attendees.stream()
        .filter(attendee -> !Strings.isNullOrEmpty(attendee.profile.email) && attendee.profile.email.contains("@"))
        .map(this::toCrmContact)
        .toList();
    if (crmContacts.isEmpty()) {
      env.logJobInfo("skipping order with invalid email address(es)");
      return;
    }

    // Use instead the display_price field (if the Ticket Class include_fee field is used) // ?
    if (order.costs.basePrice.value > 0.0) {
      CrmDonation crmDonation = toCrmDonation(order);
      // TODO: which attendee/contact to use for donation?
      // TODO: 1 donation per 1 attendee?
      crmDonation.contact = crmContacts.stream().findFirst().get();

      crmDonation.campaignId = campaign.get().id;

      crmService.insertDonation(crmDonation);
    }
  }

  private void addContactsToCampaign(List<CrmContact> contacts, String campaignExternalReference, CrmService crmService) throws Exception {
    if (CollectionUtils.isEmpty(contacts) || Strings.isNullOrEmpty(campaignExternalReference)) {
      return;
    }
    Optional<CrmCampaign> campaign = crmService.getCampaignByExternalReference(campaignExternalReference);
    if (campaign.isPresent()) {
      for (CrmContact contact : contacts) {
        crmService.addContactToCampaign(contact, campaign.get().id);
      }
    }
  }

  private void upsertCrmContact(CrmContact contact, Optional<CrmContact> existingContact, CrmService crmService) throws Exception {
    if (existingContact.isEmpty()) {
      contact.id = crmService.insertContact(contact);
    } else {
      contact.id = existingContact.get().id;
      crmService.updateContact(contact);
    }
  }

  private void upsertCrmCampaign(EventBriteClient.Event event, CrmService crmService) throws Exception {
    CrmCampaign campaign = new CrmCampaign("", event.name.text, event.id);
    Optional<CrmCampaign> existingCampaign = crmService.getCampaignByExternalReference(event.id);

    if (existingCampaign.isEmpty()) {
      campaign.id = crmService.insertCampaign(campaign);
    } else {
      campaign.id = existingCampaign.get().id;
      crmService.updateCampaign(campaign);
    }
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
    if (address != null) {
      crmAddress.street = address.address1 + " " + address.address2;
      crmAddress.city = address.city;
      crmAddress.postalCode = address.postalCode;
      crmAddress.state = address.region; //?
      crmAddress.country = address.country;
    }
    return crmAddress;
  }

  private CrmDonation toCrmDonation(EventBriteClient.Order order) {
    CrmDonation crmDonation = new CrmDonation();
    crmDonation.transactionId = order.id;
    crmDonation.description = order.name + "/" + order.email;
    crmDonation.gatewayName = "EventBrite";
    crmDonation.closeDate = ZonedDateTime.parse(order.created);
    if ("placed".equalsIgnoreCase(order.status)) {
      crmDonation.status = CrmDonation.Status.SUCCESSFUL;
    } else if ("refunded".equalsIgnoreCase(order.status)) {
      crmDonation.status = CrmDonation.Status.FAILED;
    }
    crmDonation.url = order.resourceUri;
    crmDonation.amount = order.costs.basePrice.value;
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
