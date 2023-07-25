package com.impactupgrade.nucleus.service.logic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
    JobPayload jobPayload = getJobPayload(job);
    if (jobPayload == null) {
      // don't have payload to process - should be unreachable
      return;
    }
    // First, is it time to fire off this job?
    if (jobPayload.lastTimestamp == null) {
      if (now.isBefore(job.scheduleStart)) {
        // too soon for a new run
        return;
      }
    } else {
      Instant lastTimestamp = Instant.ofEpochMilli(jobPayload.lastTimestamp);
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

    env.logJobInfo("job {} is ready for the next message", job.id);

    String contactListId = jobPayload.crmListId;
    if (Strings.isNullOrEmpty(contactListId)) {
      env.logJobWarn("Failed to get contact list id for job id {}! Skipping...", job.id);
      return;
    }

    env.logJobInfo("Retrieving contacts using contactListId {}", contactListId);

    List<CrmContact> crmContacts = crmService.getContactsFromList(contactListId);
    if (CollectionUtils.isEmpty(crmContacts)) {
      env.logJobInfo("No contacts returned for job id {}! Skipping...");
      return;
    }

    Map<String, JobProgress> progressesByContacts = job.jobProgresses.stream()
        .filter(jp -> !Strings.isNullOrEmpty(jp.targetId))
        .collect(Collectors.toMap(
            jp -> jp.targetId,
            jp -> jp,
            (jp1, jp2) -> {
              env.logJobInfo("ignoring duplicate: {}", jp2.targetId);
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
        if (jobProgress == null && !Strings.isNullOrEmpty(crmContact.id)) {
          env.logJobInfo("Failed to get job progress using target id {}. Trying to find job progress using contact id {}...", targetId, crmContact.id);
          jobProgress = progressesByContacts.get(crmContact.id);
        }

        if (jobProgress == null) {
          env.logJobInfo("Contact {} does not have any progress so far...", targetId);

          jobProgress = new JobProgress();
          jobProgress.targetId = targetId;
          jobProgress.payload = objectMapper.createObjectNode();
          jobProgress.job = job;
          jobProgressDao.create(jobProgress);

          if (job.sequenceOrder == JobSequenceOrder.BEGINNING) {
            nextMessage = 1;
          } else {
            Integer lastMessage = jobPayload.lastMessage;
            if (lastMessage == null) {
              nextMessage = 1;
            } else {
              nextMessage = lastMessage + 1;
            }
          }
        } else {
          Integer lastMessage = getJsonInt(jobProgress.payload, "lastMessage");
          env.logJobInfo("Last sent message id for contact {} is {}", targetId, lastMessage);
          nextMessage = lastMessage + 1;
          env.logJobInfo("Next message id to send: {}", nextMessage);
        }

        if (jobPayload.sequenceMessages != null && nextMessage > jobPayload.sequenceMessages.size()) {
          env.logJobInfo("All messages sent for contact {}!", targetId);
          continue;
        }

        // TODO: getMessage assumes the code ("EN") use, not the language name ("English"). The SMS campaign JSON
        //  technically includes a "languages" array with both. So if the CRM uses the full name, may need to convert
        //  here using the JSON mappings.
        String defaultLanguageCode = getDefaultLanguage(jobPayload.languages);
        
        String languageCode = crmContact.language;
        if (Strings.isNullOrEmpty(languageCode)) {
          env.logJobInfo("Failed to get contact language for contact {}; assuming {}", targetId, defaultLanguageCode);
          languageCode = defaultLanguageCode;
        }
        languageCode = languageCode.toUpperCase(Locale.ROOT);
       
        Message message = getMessage(jobPayload.sequenceMessages, nextMessage, languageCode, defaultLanguageCode);

        // If the message is empty, it's likely due to the campaign not being configured for the given language. Skip
        // attempting to send the message, but still log "progress" so that this step isn't reattempted over and over
        // for a single contact.
        if (!Strings.isNullOrEmpty(message.messageBody)) {
          String sender = jobPayload.campaignPhone;
          messagingService.sendMessage(message.messageBody, message.attachmentUrl, crmContact, sender);
        }

        updateJobProgress(jobProgress.payload, nextMessage);

        // Switch to new target id (phone number) instead of contact id
        if (!StringUtils.equalsIgnoreCase(jobProgress.targetId, targetId)) {
          env.logJobInfo("Updating job progress target id from {} to {}...", crmContact.id, targetId);
          jobProgress.targetId = targetId;
        }

        jobProgressDao.update(jobProgress);
      } catch (Exception e) {
        env.logJobError("scheduled job failed for contact {}", targetId, e);
      }
    }

    if (job.scheduleFrequency == JobFrequency.ONETIME) {
      job.status = JobStatus.DONE;
    } else {
      updateJob(job, now);
    }
    jobDao.update(job);

    env.endJobLog(JobStatus.DONE);
  }

  private JobPayload getJobPayload(Job job) {
    if (job == null || job.payload == null) {
      return null;
    }
    JobPayload jobPayload = null;
    try {
      jobPayload = new ObjectMapper().readValue(job.payload.toString(), new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      env.logJobWarn("Failed to get job payload from json node! {}", e.getMessage());
    }
    return jobPayload;
  }

  private String getDefaultLanguage(List<Language> languages) {
    String defaultLanguage = null;

    if (CollectionUtils.isNotEmpty(languages)) {
      defaultLanguage = languages.stream()
          .filter(l -> l.isDefault)
          .findFirst()
          .map(l -> l.code)
          .orElse(null);;
    }

    if (Strings.isNullOrEmpty(defaultLanguage)) {
      env.logJobWarn("Using default language '{}'...", defaultLanguage);
      defaultLanguage = "EN";
    }
    
    return defaultLanguage;
  }
  
  private Message getMessage(List<SequenceMessage> sequenceMessages, Integer id, String languageCode, String defaultLanguageCode) {
    Message message = null;
    SequenceMessage sequenceMessage = sequenceMessages.stream()
        .filter(sm -> id == sm.id)
        .findFirst().orElse(null);
    
    if (sequenceMessage != null && sequenceMessage.messagesByLanguages != null) {
      Message languageCodeMessage = sequenceMessage.messagesByLanguages.get(languageCode);
      
      if (languageCodeMessage != null) {
        message = languageCodeMessage;

        if ("primary_attachment".equalsIgnoreCase(languageCodeMessage.useAttachment)) {
          Message defaultLanguageCodeMessage = sequenceMessage.messagesByLanguages.get(defaultLanguageCode);

          if (defaultLanguageCodeMessage != null) {
            message.attachmentUrl = defaultLanguageCodeMessage.attachmentUrl;
          }
        } else if ("none".equalsIgnoreCase(languageCodeMessage.useAttachment)) {
          message.attachmentUrl = null;
        }
      }
    }
    return message;
  }

  private void updateJob(Job job, Instant now) {
    JobPayload jobPayload = getJobPayload(job);
    if (jobPayload.firstTimestamp == null) {
      ((ObjectNode) job.payload).put("firstTimestamp", now.toEpochMilli());
    }
    ((ObjectNode) job.payload).put("lastTimestamp", now.toEpochMilli());

    if (job.sequenceOrder == JobSequenceOrder.NEXT) {
      Integer lastMessage = jobPayload.lastMessage;
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

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class JobPayload {
    public String name;
    @JsonProperty("campaign_phone")
    public String campaignPhone;
    @JsonProperty("crm_list")
    public String crmListId;
    public List<Language> languages;
    @JsonProperty("messages")
    public List<SequenceMessage> sequenceMessages;

    public Long firstTimestamp;
    public Long lastTimestamp;
    public Integer lastMessage;
  }
  
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Language {
    public String language;
    public String code;
    @JsonProperty("default")
    public boolean isDefault;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class SequenceMessage {
    public Integer id;
    @JsonProperty("languages")
    public Map<String, Message> messagesByLanguages;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Message {
    public String useAttachment;
    public String attachmentUrl;
    @JsonProperty("message")
    public String messageBody;
  }
}
