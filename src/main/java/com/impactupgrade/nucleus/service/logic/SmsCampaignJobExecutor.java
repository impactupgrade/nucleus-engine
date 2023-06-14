package com.impactupgrade.nucleus.service.logic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.entity.JobFrequency;
import com.impactupgrade.nucleus.entity.JobProgress;
import com.impactupgrade.nucleus.entity.JobSequenceOrder;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.jayway.jsonpath.JsonPath;
import net.minidev.json.JSONArray;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.stream.Collectors;

public class SmsCampaignJobExecutor implements JobExecutor {

  private static final Logger log = LogManager.getLogger(SmsCampaignJobExecutor.class);

  private static final ObjectMapper objectMapper = new ObjectMapper();

  private final Environment env;

  private final HibernateDao<Long, Job> jobDao;
  private final HibernateDao<Long, JobProgress> jobProgressDao;
  private final CrmService crmService;
  private final MessagingService messagingService;

  public SmsCampaignJobExecutor(Environment env) {
    this.env = env;

    this.jobDao = new HibernateDao<>(Job.class);
    this.jobProgressDao = new HibernateDao<>(JobProgress.class);
    this.crmService = env.messagingCrmService();
    this.messagingService = env.messagingService();
  }

  @Override
  public void execute(Job job, Instant now) throws Exception {
    // First, is it time to fire off this job?
    if (getJsonLong(job.payload, "lastTimestamp") == null) {
      if (now.isBefore(job.scheduleStart)) {
        // too soon for a new run
        return;
      }
    } else {
      Instant lastTimestamp = Instant.ofEpochMilli(getJsonLong(job.payload, "lastTimestamp"));
      Optional<Instant> nextFireTime = getNextFireTime(job, lastTimestamp);
      // One time send
      if (nextFireTime.isEmpty()
          // or too soon for a new run
          || now.isBefore(nextFireTime.get())) {
        return;
      }
    }

    String jobName = "SMS Campaign";
    env.startJobLog(JobType.PORTAL_TASK, null, jobName, "Nucleus Portal");

    log.info("job {} is ready for the next message", job.id);

    String contactListId = getJsonText(job.payload, "crm_list");
    if (Strings.isNullOrEmpty(contactListId)) {
      log.warn("Failed to get contact list id for job id {}! Skipping...", job.id);
      return;
    }

    env.logJobProgress("Retrieving contacts using contactListId " + contactListId);

    List<CrmContact> crmContacts = crmService.getContactsFromList(contactListId);
    if (CollectionUtils.isEmpty(crmContacts)) {
      env.logJobProgress("No contacts returned for job id {}! Skipping...");
      return;
    }

    Map<String, JobProgress> progressesByContacts = job.jobProgresses.stream()
        .filter(jp -> !Strings.isNullOrEmpty(jp.targetId))
        .collect(Collectors.toMap(
            jp -> jp.targetId,
            jp -> jp,
            (jp1, jp2) -> {
              log.info("ignoring duplicate: {}", jp2.targetId);
              return jp1;
            }
        ));

    for (CrmContact crmContact : crmContacts) {
      String targetId = crmContact.phoneNumberForSMS();
      if (Strings.isNullOrEmpty(targetId)) {
        continue;
      }
      targetId = targetId.replaceAll("[\\D]", "");

      try {
        int nextMessage;

        JobProgress jobProgress = progressesByContacts.get(targetId);

        // TODO: We originally used the contact id to track progress, but switched to using the phone number so we could
        //  support non-CRM sources. Keeping this for now as a fallback for existing campaigns, as of May 2023.
        //  Remove in the future!
        if (jobProgress == null) {
          log.info("Failed to get job progress using target id {}. Trying to find job progress using contact id {}...", targetId, crmContact.id);
          jobProgress = progressesByContacts.get(crmContact.id);
        }

        if (jobProgress == null) {
          log.info("Contact {} does not have any progress so far...", targetId);

          jobProgress = new JobProgress();
          jobProgress.targetId = targetId;
          jobProgress.payload = objectMapper.createObjectNode();
          jobProgress.job = job;
          jobProgressDao.create(jobProgress);

          if (job.sequenceOrder == JobSequenceOrder.BEGINNING) {
            nextMessage = 1;
          } else {
            Integer lastMessage = getJsonInt(job.payload, "lastMessage");
            if (lastMessage == null) {
              nextMessage = 1;
            } else {
              nextMessage = lastMessage + 1;
            }
          }
        } else {
          Integer lastMessage = getJsonInt(jobProgress.payload, "lastMessage");
          log.info("Last sent message id for contact {} is {}", targetId, lastMessage);
          nextMessage = lastMessage + 1;
          log.info("Next message id to send: {}", nextMessage);
        }

        JsonNode messagesNode = getJsonNode(job.payload, "messages");

        if (nextMessage > messagesNode.size()) {
          log.info("All messages sent for contact {}!", targetId);
          continue;
        }

        // TODO: getMessage assumes the code ("EN") use, not the language name ("English"). The SMS campaign JSON
        //  technically includes a "languages" array with both. So if the CRM uses the full name, may need to convert
        //  here using the JSON mappings.
        String defaultLanguageCode = getDefaultLanguage(getJsonNode(job.payload, "languages"));
        if (Strings.isNullOrEmpty(defaultLanguageCode)) {
          defaultLanguageCode = "EN";
        }
        
        String languageCode = crmContact.language;
        if (Strings.isNullOrEmpty(languageCode)) {
          log.info("Failed to get contact language for contact {}; assuming {}", targetId, defaultLanguageCode);
          languageCode = defaultLanguageCode;
        }
        languageCode = languageCode.toUpperCase(Locale.ROOT);
       
        String message = getMessage(messagesNode, nextMessage, languageCode, defaultLanguageCode);

        // If the message is empty, it's likely due to the campaign not being configured for the given language. Skip
        // attempting to send the message, but still log "progress" so that this step isn't reattempted over and over
        // for a single contact.
        if (!Strings.isNullOrEmpty(message)) {
          String sender = getJsonText(job.payload, "campaign_phone");
          messagingService.sendMessage(message, crmContact, sender);
        }

        updateJobProgress(jobProgress.payload, nextMessage);

        // Switch to new target id (phone number) instead of contact id
        if (!StringUtils.equalsIgnoreCase(jobProgress.targetId, targetId)) {
          log.info("Updating job progress target id from {} to {}...", crmContact.id, targetId);
          jobProgress.targetId = targetId;
        }

        jobProgressDao.update(jobProgress);
      } catch (Exception e) {
        env.logJobError("scheduled job failed for contact " + targetId, e, false);
      }
    }

    env.endJobLog(jobName);

    if (job.scheduleFrequency == JobFrequency.ONETIME) {
      job.status = JobStatus.DONE;
    } else {
      updateJob(job, now);
    }
    jobDao.update(job);
  }

  private String getDefaultLanguage(JsonNode languagesNode) {
    JsonNode jsonNode = readJsonPath(languagesNode, "$.[?(@.default==true)].code");
    return jsonNode != null ? jsonNode.asText() : null;
  }

  private String getMessage(JsonNode messagesNode, Integer id, String languageCode, String defaultLanguageCode) {
    String message = null;
    String attachmentUrl = null;

    JsonNode messageNode = readJsonPath(
        messagesNode, 
        "$.[?(@.id=='" + id + "')].languages." + languageCode
    );
    
    if (messageNode != null) {
      message = getJsonText(messageNode, "message");
      String useAttachment = getJsonText(messageNode, "useAttachment"); 
      
      if ("own_attachment".equals(useAttachment)) {
        attachmentUrl = messageNode.get("attachmentUrl").asText();
      } else if ("primary_attachment".equals(useAttachment)) {
        JsonNode defaultLanguageMessageNode = readJsonPath(
            messagesNode,
            "$.[?(@.id=='" + id + "')].languages." + defaultLanguageCode
        );
        if (defaultLanguageMessageNode != null) {
          attachmentUrl = getJsonText(defaultLanguageMessageNode, "attachmentUrl");
        }
      }
      
      if (!Strings.isNullOrEmpty(attachmentUrl)) {
        message += "\n" + attachmentUrl;
      }
    }
    
    return message;
  }
  
  private JsonNode readJsonPath(JsonNode jsonNode, String jsonPath) {
    JsonNode foundNode = null;
    try {
      JSONArray jsonArray = JsonPath.read(jsonNode.toString(), jsonPath);
      String jsonArrayString = jsonArray.toJSONString();
      foundNode = new ObjectMapper().readTree(jsonArrayString);
    } catch (Exception e) {
      log.warn("Failed to read json path from json node! {}", e.getMessage());
    }
    // Please note, that the return value of jsonPath is an array, 
    // So getting first item in the array to get to 'real' result
    return foundNode != null ? foundNode.get(0) : null;
  }

  private void updateJob(Job job, Instant now) {
    if (Objects.isNull(job.payload.findValue("firstTimestamp"))) {
      ((ObjectNode) job.payload).put("firstTimestamp", now.toEpochMilli());
    }
    ((ObjectNode) job.payload).put("lastTimestamp", now.toEpochMilli());

    if (job.sequenceOrder == JobSequenceOrder.NEXT) {
      Integer lastMessage = getJsonInt(job.payload, "lastMessage");
      if (lastMessage == null) {
        ((ObjectNode) job.payload).put("lastMessage", 1);
      } else {
        ((ObjectNode) job.payload).put("lastMessage", lastMessage + 1);
      }
    }
  }

  private void updateJobProgress(JsonNode jobProgressNode, Integer lastMessage) {
    ((ObjectNode) jobProgressNode).put("lastMessage", lastMessage);
    if (Objects.isNull(jobProgressNode.findValue("sentMessages"))) {
      ((ObjectNode) jobProgressNode).putArray("sentMessages");
    }
    ((ArrayNode) jobProgressNode.findValue("sentMessages")).add(lastMessage);
  }

  private Optional<Instant> getNextFireTime(Job job, Instant lastSent) {
    Instant nextFireTime = job.scheduleStart;
    do {
      nextFireTime = increaseDate(nextFireTime, job.scheduleFrequency, job.scheduleInterval);
    } while (nextFireTime.isBefore(lastSent));

    return Optional.of(nextFireTime);
  }

  private Instant increaseDate(Instant previous, JobFrequency frequency, Integer interval) {
    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    calendar.setTime(Date.from(previous));
    switch (frequency) {
      case DAILY -> calendar.add(Calendar.DATE, interval);
      case WEEKLY -> calendar.add(Calendar.WEEK_OF_YEAR, interval);
      case MONTHLY -> calendar.add(Calendar.MONTH, interval);
      default -> throw new RuntimeException("unexpected frequency: " + frequency);
    };
    return calendar.getTime().toInstant();
  }
}
