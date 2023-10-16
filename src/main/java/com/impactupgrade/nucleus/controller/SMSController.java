package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.util.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.util.Utils.noWhitespace;
import static com.impactupgrade.nucleus.util.Utils.trim;

@Path("/messaging")
public class SMSController {
  private static final Logger log = LogManager.getLogger(SMSController.class);

  protected final EnvironmentFactory envFactory;
  public SMSController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  /**
   * This webhook is to be used by SMS flows for more complex
   * interactions. Try to make use of the standard form params whenever possible to maintain the overlap!
   */
  @Path("/inbound/sms/signup")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_XML)
  public Response inboundSignup(
      @FormParam("From") String from,
      @FormParam("FirstName") String _firstName,
      @FormParam("LastName") String _lastName,
      @FormParam("FullName") String fullName,
      @FormParam("Email") String _email,
      @FormParam("EmailOptIn") String emailOptIn,
      @FormParam("SmsOptIn") String smsOptIn,
      @FormParam("Language") String _language,
      @FormParam("ListId") String _listId,
      @FormParam("HubSpotListId") @Deprecated Long hsListId,
      @FormParam("CampaignId") String campaignId,
      @FormParam("OpportunityName") String opportunityName,
      @FormParam("OpportunityRecordTypeId") String opportunityRecordTypeId,
      @FormParam("OpportunityOwnerId") String opportunityOwnerId,
      @FormParam("OpportunityNotes") String opportunityNotes,
      @FormParam("nucleus-username") String nucleusUsername,
      @Context HttpServletRequest request
  ) throws Exception {
    log.info("from={} firstName={} lastName={} fullName={} email={} emailOptIn={} smsOptIn={} language={} listId={} hsListId={} campaignId={} opportunityName={} opportunityRecordTypeId={} opportunityOwnerId={} opportunityNotes={}",
        from, _firstName, _lastName, fullName, _email, emailOptIn, smsOptIn, _language, _listId, hsListId, campaignId, opportunityName, opportunityRecordTypeId, opportunityOwnerId, opportunityNotes);
    Environment env = envFactory.init(request);

    _firstName = trim(_firstName);
    _lastName = trim(_lastName);
    fullName = trim(fullName);
    final String email = noWhitespace(_email);
    final String language = noWhitespace(_language);

    String firstName;
    String lastName;
    if (!Strings.isNullOrEmpty(fullName)) {
      String[] split = Utils.fullNameToFirstLast(fullName);
      firstName = split[0];
      lastName = split[1];
    } else {
      firstName = _firstName;
      lastName = _lastName;
    }

    String listId;
    if (hsListId != null && hsListId > 0) {
      listId = hsListId + "";
    } else {
      listId = _listId;
    }
    Runnable thread = () -> {
      try {
        String jobName = "SMS Flow";
        env.startJobLog(JobType.EVENT, null, jobName, env.smsService().name());
        CrmContact crmContact = env.messagingService().processSignup(
            from,
            firstName,
            lastName,
            email,
            emailOptIn,
            smsOptIn,
            language,
            campaignId,
            listId
        );

        // avoid the insertOpportunity call unless we're actually creating a non-donation opportunity
        CrmOpportunity crmOpportunity = new CrmOpportunity();
        crmOpportunity.contact.id = crmContact.id;

        if (!Strings.isNullOrEmpty(opportunityName)) {
          crmOpportunity.name = opportunityName;
          crmOpportunity.recordTypeId = opportunityRecordTypeId;
          crmOpportunity.ownerId = opportunityOwnerId;
          crmOpportunity.campaignId = campaignId;
          crmOpportunity.description = opportunityNotes;
          env.messagingCrmService().insertOpportunity(crmOpportunity);
          //TODO: Out of date log process
//          env.endJobLog(jobName);
        }
      } catch (Exception e) {
        log.warn("inbound SMS signup failed", e);
        env.logJobError(e.getMessage());
      }
    };
    new Thread(thread).start();

    //TODO removed OG Twilio response, revisit after sorting out MB
    return Response.ok().build();
  }

  private static final List<String> STOP_WORDS = List.of("STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT");

  /**
   * This webhook serves as a more generic catch-all endpoint for inbound messages from SMS Services.
   */
  @Path("/inbound/sms/webhook")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_XML)
  public Response inboundWebhook(
      Form rawFormData,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);

    MultivaluedMap<String, String> smsData = rawFormData.asMap();
    log.info(smsData.entrySet().stream().map(e -> e.getKey() + "=" + String.join(",", e.getValue())).collect(Collectors.joining(" ")));

    String from = smsData.get("From").get(0);
    if (smsData.containsKey("Body")) {
      String body = smsData.get("Body").get(0).trim();
      // prevent opt-out messages, like "STOP", from polluting the notifications
      if (!STOP_WORDS.contains(body.toUpperCase(Locale.ROOT))) {
        String jobName = "SMS Inbound";
        env.startJobLog(JobType.EVENT, null, jobName, env.smsService().name());
        String targetId = env.messagingCrmService().searchContacts(ContactSearch.byPhone(from)).getSingleResult().map(c -> c.id).orElse(null);
        //TODO: Out of date process
//        env.notificationService().sendNotification(
//            "Text Message Received",
//            "Text message received from " + from + ": " + body,
//            targetId,
//            "sms:inbound-default"
//        );
//        env.endJobLog(jobName);
      }
    }
    //TODO removed OG Twilio response, revisit after sorting out MB
    return Response.ok().build();
  }

}
