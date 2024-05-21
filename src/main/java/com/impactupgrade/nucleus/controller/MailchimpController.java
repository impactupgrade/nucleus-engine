/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.ecwid.maleorang.method.v3_0.campaigns.content.ContentInfo;
import com.ecwid.maleorang.method.v3_0.reports.sent_to.SentToInfo;
import com.impactupgrade.nucleus.client.MailchimpClient;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.CrmActivity;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

@Path("/mailchimp")
public class MailchimpController {

  protected final EnvironmentFactory envFactory;

  public MailchimpController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @Path("/webhook/audience")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response audienceEvent(
      @BeanParam AudienceEvent audienceEvent,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);

    String jobName = "Mailchimp audience event webhook";
    env.startJobLog(JobType.EVENT, null, jobName, "Mailchimp");
    env.logJobInfo("Mailchimp audience event received: {}", audienceEvent);
    JobStatus jobStatus = JobStatus.DONE;

    try {
      if ("subscribe".equalsIgnoreCase(audienceEvent.type)) {
        //TODO:
      } else if ("unsubscribe".equalsIgnoreCase(audienceEvent.type)) {
        //TODO:
      } else if ("campaign".equalsIgnoreCase(audienceEvent.type)) {
        processCampaignEvent(audienceEvent, env);
      } else {
        env.logJobInfo("skipping event type {}...", audienceEvent.type);
      }

    } catch (Exception e) {
      env.logJobError("Failed to process audience event!", e);
      jobStatus = JobStatus.FAILED;
    }

    env.endJobLog(jobStatus);

    return Response.status(200).build();
  }

  // Mailchimp sends a GET to make sure the webhook is available (silly...)
  @Path("/webhook/audience")
  @GET
  public Response messageEvent() throws Exception {
    return Response.status(200).build();
  }
  
  private void processCampaignEvent(AudienceEvent event, Environment env) throws Exception {
    if (!"sent".equalsIgnoreCase(event.status)) {
      return;
    }

    // TODO: Clunky way to retrieve the specific account keys, when all we have is the List ID..
    EnvironmentConfig.Mailchimp mailchimpConfig = null;
    for (EnvironmentConfig.Mailchimp _mailchimpConfig : env.getConfig().mailchimp) {
      for (EnvironmentConfig.CommunicationList communicationList : _mailchimpConfig.lists) {
        if (event.listId.equalsIgnoreCase(communicationList.id)) {
          mailchimpConfig = _mailchimpConfig;
          break;
        }
      }
    }
    if (mailchimpConfig == null) {
      env.logJobError("unable to find ListID={}", event.listId);
      return;
    }
    MailchimpClient mailchimpClient = new MailchimpClient(mailchimpConfig, env);

    List<SentToInfo> sentTos = mailchimpClient.getCampaignRecipients(event.id);
    List<String> emails = sentTos.stream()
        .filter(member -> member.status == SentToInfo.Status.SEND)
        .map(member -> member.email_address)
        .toList();

    ContentInfo contentInfo = mailchimpClient.getCampaignContent(event.id);

    Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(event.firedAt);
    Calendar c = Calendar.getInstance();
    c.setTime(d);

    env.activityService().upsertActivityFromEmails(
        emails,
        CrmActivity.Type.EMAIL,
        event.id,
        c,
        event.subject,
        contentInfo.plain_text
    );
  }

  public static final class AudienceEvent {
    @FormParam("type") public String type;
    @FormParam("fired_at") public String firedAt; // Date format 2009-03-26 21:35:57
    @FormParam("data[id]") public String id;
    @FormParam("data[list_id]") public String listId;
    @FormParam("data[email]") public String email;
    @FormParam("data[email_type]") public String emailType;
    @FormParam("data[ip_opt]") public String ipOpt;
    @FormParam("data[ip_signup]") public String ipSignup;
    @FormParam("data[reason]") public String reason;
    @FormParam("data[status]") public String status;
    @FormParam("data[subject]") public String subject;

    @Override
    public String toString() {
      return "AudienceEvent{" +
          "type='" + type + '\'' +
          ", firedAt='" + firedAt + '\'' +
          ", id='" + id + '\'' +
          ", listId='" + listId + '\'' +
          ", email='" + email + '\'' +
          ", emailType='" + emailType + '\'' +
          ", ipOpt='" + ipOpt + '\'' +
          ", ipSignup='" + ipSignup + '\'' +
          ", reason='" + reason + '\'' +
          ", status='" + status + '\'' +
          ", subject='" + subject + '\'' +
          '}';
    }
  }
}
