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
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.apache.commons.collections.CollectionUtils;
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

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HibernateDao<Long, Job> jobDao;
  private final HibernateDao<Long, JobProgress> jobProgressDao;
  private final CrmService crmService;
  private final MessagingService messagingService;

  public SmsCampaignJobExecutor(Environment env) {
    this.jobDao = new HibernateDao<>(Job.class);
    this.jobProgressDao = new HibernateDao<>(JobProgress.class);
    this.crmService = env.primaryCrmService();
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

    log.info("job {} is ready for the next message", job.id);

    String contactListId = getJsonText(job.payload, "crm_list");
    if (Strings.isNullOrEmpty(contactListId)) {
      log.warn("Failed to get contact list id for job id {}! Skipping...", job.id);
      return;
    }

    List<CrmContact> crmContacts = crmService.getContactsFromList(contactListId);
    if (CollectionUtils.isEmpty(crmContacts)) {
      log.info("No contacts returned for job id {}! Skipping...", job.id);
      return;
    }

    JsonNode messagesNode = getJsonNode(job.payload, "messages");

    Map<String, JobProgress> progressesByContacts = job.jobProgresses.stream()
        .collect(Collectors.toMap(tp -> tp.targetId, tp -> tp));

    for (CrmContact crmContact : crmContacts) {
      String contactId = crmContact.id;

      try {
        int nextMessage;
        JobProgress jobProgress = progressesByContacts.get(contactId);

        if (jobProgress == null) {
          log.info("Contact id {} does not have any progress so far...", contactId);

          jobProgress = new JobProgress();
          jobProgress.targetId = contactId;
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
          log.info("Last sent message id for contact id {} is {}", contactId, lastMessage);
          nextMessage = lastMessage + 1;
          log.info("Next message id to send: {}", nextMessage);
        }

        if (nextMessage > messagesNode.size()) {
          log.info("All messages sent for contact id {}!", contactId);
          continue;
        }

        // TODO: getMessage assumes the code ("EN") use, not the language name ("English"). The SMS campaign JSON
        //  technically includes a "languages" array with both. So if the CRM uses the full name, may need to convert
        //  here using the JSON mappings.
        String languageCode = crmContact.contactLanguage;
        if (Strings.isNullOrEmpty(languageCode)) {
          log.info("Failed to get contact language for contact id {}; assuming EN", contactId);
          languageCode = "EN";
        } else {
          languageCode = languageCode.toUpperCase(Locale.ROOT);
        }
        String message = getMessage(messagesNode, nextMessage, languageCode);

        String sender = getJsonText(job.payload, "campaign_phone");

        messagingService.sendMessage(message, crmContact, sender);

        updateJobProgress(jobProgress.payload, nextMessage);
        jobProgressDao.update(jobProgress);
      } catch (Exception e) {
        log.error("scheduled job failed for contact {}", contactId, e);
      }
    }

    log.info("Job processed!");

    if (job.scheduleFrequency == JobFrequency.ONETIME) {
      job.status = JobStatus.DONE;
    } else {
      updateJob(job, now);
    }
    jobDao.update(job);
  }

  // TODO: Let's introduce jsonpath? Or a limited set of Jackson bindings?
  private String getMessage(JsonNode messagesNode, Integer id, String languageCode) {
    if (messagesNode.isArray()) {
      for (JsonNode messageNode : messagesNode) {
        if (id.equals(getJsonInt(messageNode, "id"))) {
          JsonNode languagesNode = messageNode.findValue("languages");
          if (languagesNode != null) {
            JsonNode languageNode = getJsonNode(languagesNode, languageCode);
            if (languageNode != null) {
              return getJsonText(languageNode, "message");
            }
          }
        }
      }
    }

    return null;
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
