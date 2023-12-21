package com.impactupgrade.nucleus.controller;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import static com.impactupgrade.nucleus.service.logic.ActivityService.ActivityType.SMS;

/**
 * To receive webhooks from MBT as messages are sent/received.
 */
@Path("/mbt")
public class MinistryByTextController {

  private static final String DATE_FORMAT = "yyyy-MM-dd";
  private static final String DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss"; 

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

    String jobName = "SMS Inbound";
    env.startJobLog(JobType.EVENT, null, jobName, "MBT");

    // Using combination of subscriber number and today's date 
    // as a conversation id 
    // to group all user's messages for current day
    String conversationId = inboundMessageWebhookData.subscriberNo  + "::" + new SimpleDateFormat(DATE_FORMAT).format(new Date());
    env.activityService().upsertActivity(
        SMS,
        conversationId, // TODO: use customParams to contain conversation id?
        inboundMessageWebhookData.externalReferenceId,
        inboundMessageWebhookData.message); 

    env.endJobLog(JobStatus.DONE);

    return Response.ok().build();
  }

  /**
   * The Message Status webhook is triggered as a message sent from an Account progresses to a Subscriber.
   */
  @Path("/sms/status")
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response smsStatusWebhook(
      MessageStatusWebhookData messageStatusWebhookData,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);

    String jobName = "SMS Status";
    env.startJobLog(JobType.EVENT, null, jobName, "MBT");

    // Using combination of msisdn and today's date 
    // as a conversation id 
    // to group all user's messages' statuses for current day
    String conversationId = messageStatusWebhookData.msisdn  + "::" + new SimpleDateFormat(DATE_FORMAT).format(new Date());
    env.activityService().upsertActivity(
        SMS,
        conversationId, // TODO: use customParams to contain conversation id?
        messageStatusWebhookData.messageId,
        messageStatusWebhookData.message);

    env.endJobLog(JobStatus.DONE);

    return Response.ok().build();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class InboundMessageWebhookData {
    public String externalReferenceId;
    public String type;
    public String message;
    public String subscriberNo;
    public String groupName;
    public String groupId;
    public String communicationCode;
    public String messageType;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DATE_TIME_FORMAT)
    public Date receivedTime;
    // Every message received sends the data shown in sample to the target URL. 
    // The customParams parameters may be specified and will be implemented by MBT.
    public Map<String, String> customParams;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class MessageStatusWebhookData {
    public String accountId;
    public String message;
    public String msisdn;
    public String groupName;
    public String groupId;
    public String communicationCode;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DATE_TIME_FORMAT)
    public Date deliveredTime;
    public Map<String, String> properties;
    public String statusCode;
    public String statusCodeDescription;
    public String messageId;
    public String referenceId;
  }
}
