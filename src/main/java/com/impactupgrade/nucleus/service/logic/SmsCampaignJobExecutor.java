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
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
  public void execute(Job job) throws Exception {
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
        Integer nextMessage = 1;
        JobProgress jobProgress = progressesByContacts.get(contactId);

        if (jobProgress == null) {
          log.info("Contact id {} does not have any progress so far...", contactId);

          jobProgress = new JobProgress();
          jobProgress.targetId = contactId;
          jobProgress.payload = objectMapper.createObjectNode();
          updateProgress(jobProgress.payload, nextMessage);
          jobProgress.job = job;
          jobProgressDao.create(jobProgress);
        } else {
          //        log.info("Checking existing progress for contact id {}...", contactId);
          //        String lastTimestamp = getJsonText(jobProgress.payload, "lastTimestamp");
          //        if (Strings.isNullOrEmpty(lastTimestamp)) {
          //          log.error("Failed to get last sent timestamp for job progress id {}! ", jobProgress.id);
          //          continue;
          //        }
          //
          //        Optional<Date> nextFireTime = getNextFireTime(lastTimestamp, jobSchedule);
          //        if (Objects.isNull(nextFireTime)) {
          //          log.error("Failed to get next fire time for job id {}!", job.id);
          //          continue;
          //        }
          //
          //        if (nextFireTime.isEmpty() // One time send
          //            // or too soon for a new run
          //            || new Date().before(nextFireTime.get())) {
          //          log.info("Message already sent at {}. Next fire time is {}. Skipping...", getLastSentAt(jobProgress.payload), nextFireTime.get());
          //          continue;
          //        }

          Integer lastMessage = getJsonInt(jobProgress.payload, "lastMessage");
          if (lastMessage == null) {
            log.error("Failed to get last sent message from job progress id {}! ", jobProgress.id);
            continue;
          }
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
        if (Strings.isNullOrEmpty(message)) {
          log.error("Failed to get message for id {} and language {}!", nextMessage, languageCode);
          continue;
        }

        String contactPhoneNumber = crmContact.mobilePhone;
        if (Strings.isNullOrEmpty(contactPhoneNumber)) {
          log.info("Contact id {} had no mobile phone number.", contactId);
          continue;
        }

        log.info("Sending message id {} to contact id {} using phone number {}...", nextMessage, contactId, contactPhoneNumber);
        twilioClient.sendMessage(contactPhoneNumber, message);

        updateProgress(jobProgress.payload, nextMessage);
        jobProgressDao.update(jobProgress);
      } catch (Exception e) {
        log.error("scheduled job failed for contact {}", contactId, e);
      }
    }

    log.info("Job processed!");

    if (job.frequency == JobFrequency.ONETIME) {
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

  private void updateProgress(JsonNode jobProgressNode, Integer lastMessage) {
    ((ObjectNode) jobProgressNode).put("lastMessage", lastMessage);
    // TODO: Use secs or millis consistently to match whatever the DB uses for timestamps in JobSchedule.
    ((ObjectNode) jobProgressNode).put("lastTimestamp", Instant.now().getEpochSecond() * 1000);
    if (Objects.isNull(jobProgressNode.findValue("sentMessages"))) {
      ((ObjectNode) jobProgressNode).putArray("sentMessages");
    }
    ((ArrayNode) jobProgressNode.findValue("sentMessages")).add(lastMessage);
  }

//  private Optional<Date> getNextFireTime(Date lastSentAt, JobSchedule jobSchedule) {
//    Calendar startDate = jobSchedule.start;
//    String frequency = jsonNode.findValue("frequency").asText();
//    Integer interval = jsonNode.findValue("interval").asInt();
//
//    Date nextFireTime = startDate;
//    do {
//      nextFireTime = increaseDate(nextFireTime, frequency, interval);
//    } while (nextFireTime.before(lastSentAt));
//
//    return Optional.of(nextFireTime);
//  }
//
//  private Date increaseDate(Date inputDate, String frequency, Integer interval) {
//    LocalDateTime previousEecutionTime = inputDate.toInstant()
//        .atZone(ZoneId.of(TIME_ZONE))
//        .toLocalDateTime();
//
//    LocalDateTime nextExecutionDate = switch (frequency) {
//      case "minute" -> previousEecutionTime.plusMinutes(interval);
//      case "day" -> previousEecutionTime.plusDays(interval);
//      case "week" -> previousEecutionTime.plusWeeks(interval);
//      case "month" -> previousEecutionTime.plusMonths(interval);
//      default -> previousEecutionTime;
//    };
//
//    // TODO: switch to LocalDateTime everywhere?
//    return Date.from(nextExecutionDate.atZone(ZoneId.of(TIME_ZONE)).toInstant());
//  }
}
