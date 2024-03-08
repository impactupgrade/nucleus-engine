package com.impactupgrade.nucleus.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.Utils;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Optional;

import static com.impactupgrade.nucleus.service.logic.ActivityService.ActivityType.SMS;

/**
 * To receive webhooks from MBT as messages are sent/received.
 */
@Path("/mbt")
public class MinistryByTextController {

  private static final String DATE_FORMAT = "yyyy-MM-dd";

  protected final EnvironmentFactory envFactory;

  public MinistryByTextController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  /**
   * The Inbound Messages webhook is triggered by receipt of a message to your MBT account.
   */
  @Path("/inbound/sms/webhook")
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response inboundSmsWebhook(
      InboundMessageWebhookData inboundMessageWebhookData,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);

    if (!"messagereceived".equalsIgnoreCase(inboundMessageWebhookData.type)) {
      return Response.ok().build();
    }

    String jobName = "SMS Inbound";
    env.startJobLog(JobType.EVENT, null, jobName, "MBT");

    // Using combination of subscriber number and today's date as a conversation id to group all user's messages for current day
    String date = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(Utils.now(env.getConfig().timezoneId));
    String conversationId = inboundMessageWebhookData.subscriberNo  + "::" + new SimpleDateFormat(DATE_FORMAT).format(new Date());
    env.activityService().upsertActivityFromPhoneNumber(
        inboundMessageWebhookData.subscriberNo,
        SMS,
        conversationId,
        inboundMessageWebhookData.message
    );

    env.endJobLog(JobStatus.DONE);

    return Response.ok().build();
  }

  /**
   * The Message Status webhook is triggered as a message sent from an Account progresses to a Subscriber.
   */
  @Path("/outbound/sms/status")
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response outboundSmsWebhook(
      MessageStatusWebhookData messageStatusWebhookData,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);

    if (!"Delivered".equalsIgnoreCase(messageStatusWebhookData.statusCodeDescription)) {
      return Response.ok().build();
    }

    String jobName = "SMS Status";
    env.startJobLog(JobType.EVENT, null, jobName, "MBT");

    Optional<CrmContact> crmContact = env.primaryCrmService()
        .searchContacts(ContactSearch.byPhone(messageStatusWebhookData.subscriberNo)).getSingleResult();
    if (crmContact.isPresent()) {
      // Using combination of msisdn and today's date as a conversation id to group all user's messages' statuses for current day
      String conversationId = messageStatusWebhookData.subscriberNo + "::" + new SimpleDateFormat(DATE_FORMAT).format(new Date());
      env.activityService().upsertActivityFromPhoneNumber(
          messageStatusWebhookData.subscriberNo,
          SMS,
          conversationId,
          messageStatusWebhookData.message
      );
    } else {
      env.logJobWarn("no CRM contact found for phone number: {}", messageStatusWebhookData.subscriberNo);
    }

    env.endJobLog(JobStatus.DONE);

    return Response.ok().build();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class InboundMessageWebhookData {
    @JsonProperty("Type")
    public String type;
    @JsonProperty("Message")
    public String message;
    @JsonProperty("SubscriberNo")
    public String subscriberNo;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class MessageStatusWebhookData {
    @JsonProperty("StatusCodeDescription")
    public String statusCodeDescription;
    @JsonProperty("Message")
    public String message;
    @JsonProperty("SubscriberNo")
    public String subscriberNo;
  }
}
