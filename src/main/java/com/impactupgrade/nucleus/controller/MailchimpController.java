/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.impactupgrade.nucleus.service.logic.ActivityService.ActivityType.EMAIL;

@Path("/mailchimp")
public class MailchimpController {

  private static final String DATE_FORMAT = "yyyy-MM-dd";

  protected final EnvironmentFactory envFactory;

  public MailchimpController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @Path("/webhook")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response webhook(
      @FormParam("action") String action,
      @FormParam("reason") String reason,
      @FormParam("email") String email,
      @FormParam("list_id") String listId,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);

    env.logJobInfo("action = {} reason = {} email = {} list_id = {}", action, reason, email, listId);

    if (action.equalsIgnoreCase("unsub")) {
      // TODO: mark as unsubscribed in the CRM
    } else {
      env.logJobWarn("unexpected event: {}", action);
    }

    return Response.status(200).build();
  }

  // Message events

  @Path("/webhook/message")
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response messageEvent(
      MessageWebhookPayload webhookPayload,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);

    String jobName = "Mailchimp message webhook events batch";
    env.startJobLog(JobType.EVENT, null, jobName, "Mailchimp");

    env.logJobInfo("Mailchimp message event batch received. Batch size: {}", webhookPayload.events.size());

    JobStatus jobStatus = JobStatus.DONE;
    for (Event event: webhookPayload.events) {
      try {
        processMessageEvent(event, env);
      } catch (Exception e) {
        env.logJobError("Failed to process event! Event type/email: {}/{}",
            event.eventType, event.message.email, e);
      }
    }

    env.endJobLog(jobStatus);

    return Response.status(200).build();
  }

  // Audience events
  @Path("/webhook/audience")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response audienceEvent(
          AudienceEvent audienceEvent,
          @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);

    String jobName = "Mailchimp audience event webhook";
    env.startJobLog(JobType.EVENT, null, jobName, "Mailchimp");
    env.logJobInfo("Mailchimp audience event received. Type/fired-at/list-id/email: {}/{}/{}/{}",
            audienceEvent.type, audienceEvent.firedAt, audienceEvent.data.listId, audienceEvent.data.email);
    JobStatus jobStatus = JobStatus.DONE;

    try {
      if ("subscribe".equalsIgnoreCase(audienceEvent.type)) {
        //TODO:
      } else if ("unsubscribe".equalsIgnoreCase(audienceEvent.type)) {
        //TODO:
      } else {
        env.logJobInfo("skipping event type {}...", audienceEvent.type);
      }

    } catch (Exception e) {
      env.logJobError("Failed to process audience event! Type/fired-at/list-id/email: {}/{}/{}/{}",
            audienceEvent.type, audienceEvent.firedAt, audienceEvent.data.listId, audienceEvent.data.email);
      jobStatus = JobStatus.FAILED;
    }

    env.endJobLog(jobStatus);

    return Response.status(200).build();
  }

  // Mailchimp sends a GET to make sure the webhook is available (silly...)
  @Path("/webhook/message")
  @GET
  public Response messageEvent() throws Exception {
    return Response.status(200).build();
  }
  
  private void processMessageEvent(Event event, Environment env) throws Exception {
    if (event == null) {
      return;
    }
    if ("send".equalsIgnoreCase(event.eventType)) {
      // Using sender::recipient::sent-date
      // as a conversation id 
      Date sentAt = Date.from(Instant.ofEpochSecond(event.message.timestamp));
      String conversationId = event.message.sender + "::" + event.message.email + "::" + new SimpleDateFormat(DATE_FORMAT).format(sentAt);
      env.activityService().upsertActivityFromEmail(
          event.message.email,
          EMAIL,
          conversationId,
          event.message.subject); // using subject instead of message body (body n\a in the webhook's payload)
    } else {
      env.logJobInfo("skipping event type {}...", event.eventType);
    }
  }

  public static final class AudienceEvent {
    public String type;
    @JsonProperty("fired_at") // Date format 2009-03-26 21:35:57
    public String firedAt;
    public Data data;
  }

  public static final class Data {
    public String id;
    @JsonProperty("list_id")
    public String listId;
    public String email;
    @JsonProperty("email_type")
    public String emailType;
    @JsonProperty("ip_opt")
    public String ipOpt;
    @JsonProperty("ip_signup")
    public String ipSignup;
    public Map<String, String> merges;
  }
  
  public static final class MessageWebhookPayload {
    @JsonProperty("mandrill_events")
    public List<Event> events;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Event {
    @JsonProperty("_id")
    public String id;
    @JsonProperty("event")
    public String eventType;
    @JsonProperty("msg")
    public Message message;

    //@JsonProperty("ts")
    //public Long timestamp;
    //public String url;
    //public String ip;
    //@JsonProperty("user_agent")
    //public String userAgent;
    //public Object location;
    //@JsonProperty("user_agent_parsed")
    //public List<Object> userAgentParsed;
    
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Message {
    @JsonProperty("_id")
    public String id;
    public String state; // One of: sent, rejected, spam, unsub, bounced, or soft-bounced
    public String email;
    public String sender;
    public String subject;
    @JsonProperty("ts")
    public Long timestamp; // the integer UTC UNIX timestamp when the message was sent 
    //TODO: add object definitions, if needed
    //@JsonProperty("smtp_events")
    //public List<Object> smtpEvents;
    //public List<Object> opens;
    //public List<Object> clicks;
    //public List<String> tags;
    //public Map<String, Object> metadata;
    //@JsonProperty("subaccount")
    //public String subAccount;
    //public String diag; //specific SMTP response code and bounce description, if any, received from the remote server
    //@JsonProperty("bounce_description")
    //public String bounceDescription;
    //public String template;
  }

  //TODO: Sync events: add/remove (to either of allowlist or denylist)
  //TODO: inbound messages
}
