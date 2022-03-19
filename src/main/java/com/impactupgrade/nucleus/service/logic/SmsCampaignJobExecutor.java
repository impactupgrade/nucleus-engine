package com.impactupgrade.nucleus.service.logic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.TwilioClient;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.entity.JobFrequency;
import com.impactupgrade.nucleus.entity.JobProgress;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.SessionFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class SmsCampaignJobExecutor implements JobExecutor {

  private static final Logger log = LogManager.getLogger(SmsCampaignJobExecutor.class);

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HibernateDao<Long, Job> jobDao;
  private final HibernateDao<Long, JobProgress> jobProgressDao;
  private final CrmService crmService;
  private final TwilioClient twilioClient;

  public SmsCampaignJobExecutor(Environment env, SessionFactory sessionFactory) {
    this.jobDao = new HibernateDao<>(Job.class, sessionFactory);
    this.jobProgressDao = new HibernateDao<>(JobProgress.class, sessionFactory);
    this.crmService = env.primaryCrmService();
    this.twilioClient = env.twilioClient();
  }

  @Override
  public void execute(Job job, Instant now) throws Exception {
    String contactListId = getJsonText(job.payload, "crm_list");
    if (Strings.isNullOrEmpty(contactListId)) {
      log.error("Failed to get contact list id for job id {}! Skipping...", job.id);
      return;
    }

    List<CrmContact> crmContacts = crmService.getContactsFromList(contactListId);

    if (CollectionUtils.isEmpty(crmContacts)) {
      log.info("No contacts returned for job id {}! Skipping...", job.id);
      return;
    }

    JsonNode messagesNode = getJsonNode(job.payload, "messages");
    if (messagesNode == null) {
      log.error("Failed to get messages node for job id {}! Skipping...", job.id);
      return;
    }
    if (messagesNode.isEmpty()) {
      // TODO: Any reason why this wouldn't be an error?
      log.error("Empty messages node for job id {}! Skipping...", job.id);
      return;
    }

    Map<String, JobProgress> progressesByContacts = job.jobProgresses.stream()
        .collect(Collectors.toMap(tp -> tp.targetId, tp -> tp));

    for (CrmContact crmContact : crmContacts) {
      String contactId = crmContact.id;

      try {
        // TODO: Need to differentiate between JobSequenceOrder (BEGINNING vs. NEXT)!
        Integer nextMessage = 1;
        JobProgress jobProgress = progressesByContacts.get(contactId);

        if (jobProgress == null) {
          log.info("Contact id {} does not have any progress so far...", contactId);

          jobProgress = new JobProgress();
          jobProgress.targetId = contactId;
          jobProgress.payload = objectMapper.createObjectNode();
          jobProgress.job = job;
          jobProgressDao.create(jobProgress);
        } else {
          Instant lastTimestamp = Instant.ofEpochMilli(getJsonLong(jobProgress.payload, "lastTimestamp"));
          Optional<Instant> nextFireTime = getNextFireTime(job, lastTimestamp);

          // One time send
          if (nextFireTime.isEmpty()
              // or too soon for a new run
              || now.isBefore(nextFireTime.get())) {
            continue;
          }

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
        }
        String message = getMessage(messagesNode, nextMessage, languageCode);

        String contactPhoneNumber = crmContact.mobilePhone;
        if (Strings.isNullOrEmpty(contactPhoneNumber)) {
          log.info("Contact id {} had no mobile phone number.", contactId);
          continue;
        }

        log.info("Sending message id {} to contact id {} using phone number {}...", nextMessage, contactId, contactPhoneNumber);
        twilioClient.sendMessage(contactPhoneNumber, message);

        updateProgress(jobProgress.payload, nextMessage, now);
        jobProgressDao.update(jobProgress);
      } catch (Exception e) {
        log.error("scheduled job failed for contact {}", contactId, e);
      }
    }

    log.info("Job processed!");

    if (job.scheduleFrequency == JobFrequency.ONETIME) {
      job.status = JobStatus.DONE;
      jobDao.update(job);
    }
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

  private void updateProgress(JsonNode jobProgressNode, Integer lastMessage, Instant now) {
    ((ObjectNode) jobProgressNode).put("lastMessage", lastMessage);
    ((ObjectNode) jobProgressNode).put("lastTimestamp", now.getEpochSecond() * 1000);
    if (Objects.isNull(jobProgressNode.findValue("sentMessages"))) {
      ((ObjectNode) jobProgressNode).putArray("sentMessages");
    }
    ((ArrayNode) jobProgressNode.findValue("sentMessages")).add(lastMessage);
  }

  // TODO: This doesn't work in the case when someone joins in the middle of the interval.
  //  Ex: start day = 0, interval = 2, contact joins on day 3. Currently, they'll receive the 1st message on day 3,
  //  the 2nd message on day 4, 3rd message on day 6, etc. Note the 1 day interval between the 1st and 2nd message.
  //  All that's due to this sliding up based on the ***job start date*** instead of the contact's lastMessage date.
  //  Could be a tricky fix. Rethink this whole setup?
  private Optional<Instant> getNextFireTime(Job job, Instant lastSent) {
    Instant nextFireTime = job.scheduleStart;
    do {
      nextFireTime = increaseDate(nextFireTime, job.scheduleFrequency, job.scheduleInterval);
    } while (nextFireTime.isBefore(lastSent));

    return Optional.of(nextFireTime);
  }

  private Instant increaseDate(Instant previous, JobFrequency frequency, Integer interval) {
    return switch (frequency) {
      case DAILY -> previous.plus(interval, ChronoUnit.DAYS);
      case WEEKLY -> previous.plus(interval * 7, ChronoUnit.DAYS);
      // TODO: How to handle 28 vs. 30 vs. 31?
      case MONTHLY -> previous.plus(interval * 31, ChronoUnit.DAYS);
      // TODO: Should never happen? Dangerous to include this? Could cause repetition...
      default -> previous;
    };
  }
}
