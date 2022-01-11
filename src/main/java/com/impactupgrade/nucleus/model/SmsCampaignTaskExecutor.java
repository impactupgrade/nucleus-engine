package com.impactupgrade.nucleus.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.TwilioClient;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.service.logic.ScheduledTaskService.DATE_FORMAT;
import static com.impactupgrade.nucleus.service.logic.ScheduledTaskService.TIME_ZONE;

public class SmsCampaignTaskExecutor implements TaskExecutor {

    private static final Logger log = LogManager.getLogger(SmsCampaignTaskExecutor.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HibernateDao<Long, Task> taskDao;
    private final CrmService crmService;
    private final TwilioClient twilioClient;

    public SmsCampaignTaskExecutor(HibernateDao<Long, Task> taskDao, Environment environment) {
        this.taskDao = taskDao;
        this.crmService = environment.primaryCrmService();
        this.twilioClient = environment.twilioClient();
    }

    @Override
    public void execute(Task task, TaskSchedule taskSchedule) {
        String contactListId = getCrmContactListId(task.payload);
        if (Strings.isNullOrEmpty(contactListId)) {
            log.error("Failed to get contact list id for task id {}! Returning...", task.id);
            return;
        }

        List<CrmContact> crmContacts;
        try {
            crmContacts = crmService.getContactsFromList(contactListId);
        } catch (Exception e) {
            log.error("Failed to get contacts for contact list id {}! Returning...", contactListId);
            return;
        }

        if (CollectionUtils.isEmpty(crmContacts)) {
            // No one to send to!
            log.warn("Could not get any contact ids for task id {}! Returning...", task.id);
            return;
        }

        JsonNode messagesNode = getMessagesNode(task.payload);
        if (Objects.isNull(messagesNode)) {
            log.error("Failed to get messages for task id {}! Returning...", task.id);
            throw new IllegalArgumentException();
        }
        if (messagesNode.isEmpty()) {
            // Nothing to send!
            log.warn("Could not get any messages for task id {}! Returning...", task.id);
            return;
        }

        List<TaskProgress> taskProgresses = task.taskProgresses;
        Map<String, TaskProgress> progressesByContacts =
                CollectionUtils.isNotEmpty(taskProgresses) ?
                        taskProgresses.stream()
                                .collect(Collectors.toMap(tp -> tp.contactId, tp -> tp))
                        : Collections.emptyMap();

        for (CrmContact crmContact : crmContacts) {
            String contactId = crmContact.id;
            Integer nextMessage = 1;
            TaskProgress taskProgress = progressesByContacts.get(contactId);

            if (Objects.isNull(taskProgress)) {
                log.info("Contact id {} does not have any progress so far...", contactId);

                // Create new task progress
                taskProgress = new TaskProgress();
                taskProgress.contactId = contactId;
                taskProgress.payload = objectMapper.createObjectNode();
                taskProgress.task = task;
                task.taskProgresses.add(taskProgress);

            } else {
                log.info("Checking existing progress for contact id {}...", contactId);
                Date lastSentAt = getLastSentAt(taskProgress.payload);
                if (Objects.isNull(lastSentAt)) {
                    log.error("Failed to get last sent timestamp for task progress id {}! ", taskProgress.id);
                    continue;
                }

                Optional<Date> nextFireTime = getNextFireTime(lastSentAt, taskSchedule);
                if (Objects.isNull(nextFireTime)) {
                    log.error("Failed to get next fire time for task id {}!", task.id);
                    continue;
                }

                if (nextFireTime.isEmpty() // One time send
                        // or too soon for a new run
                        || new Date().before(nextFireTime.get())) {
                    log.info("Message already sent at {}. Next fire time is {}. Skipping...", getLastSentAt(taskProgress.payload), nextFireTime.get());
                    continue;
                }

                Integer lastSentMessage = getLastSentMessage(taskProgress.payload);
                if (Objects.isNull(lastSentMessage)) {
                    log.error("Failed to get last sent message from task progress id {}! ", taskProgress.id);
                    continue;
                }
                log.info("Last sent message id for contact id {} is {}", contactId, lastSentMessage);
                nextMessage = lastSentMessage + 1;
                log.info("Next message id to send: {}", nextMessage);
            }

            if (nextMessage > messagesNode.size()) {
                log.info("All messages sent for contact id {}!", contactId);
                continue;
            }

            String contactLanguage = crmContact.contactLanguage;
            if (Objects.isNull(contactLanguage)) {
                log.error("Failed to get contact language for contact id {}!", contactId);
                continue;
            }
            String message = getMessage(messagesNode, nextMessage, contactLanguage);
            if (Objects.isNull(message)) {
                log.error("Failed to get message for id {} and language {}!", nextMessage, contactLanguage);
                continue;
            }

            String contactPhoneNumber = crmContact.mobilePhone;
            log.info("Sending message id {} to contact id {} using phone number {}...", nextMessage, contactId, contactPhoneNumber);
            twilioClient.sendMessage(contactPhoneNumber, message);
            log.info("Message sent!");

            setLastSentInfo(taskProgress.payload, nextMessage);
            taskDao.update(task);
        }
        log.info("Task processed!");
    }

    // JsonNode utils
    private String getCrmContactListId(JsonNode jsonNode) {
        return Objects.nonNull(jsonNode) || Objects.nonNull(jsonNode.findValue("crm_list")) ?
                jsonNode.findValue("crm_list").asText() : null;
    }

    private JsonNode getMessagesNode(JsonNode jsonNode) {
        return Objects.nonNull(jsonNode) && Objects.nonNull(jsonNode.findValue("messages")) ?
                jsonNode.get("messages") : null;
    }

    private String getMessage(JsonNode messagesNode, Integer id, String language) {
        if (Objects.isNull(messagesNode) || messagesNode.isEmpty()) {
            return null;
        }
        String message = null;
        if (messagesNode.isArray()) {
            for (JsonNode messageNode : messagesNode) {
                // If id match found
                if (Objects.nonNull(messageNode.findValue("id")) && messageNode.findValue("id").intValue() == id) {
                    JsonNode languagesNode = messageNode.findValue("languages");
                    // and language match found
                    if (Objects.nonNull(languagesNode) && Objects.nonNull(languagesNode.findValue("message"))) {
                        message = languagesNode.findValue(language).findValue("message").asText();
                    }
                }
            }
        }
        return message;
    }

    private Integer getLastSentMessage(JsonNode jsonNode) {
        return Objects.nonNull(jsonNode) && Objects.nonNull(jsonNode.findValue("lastSentMessage")) ?
                jsonNode.findValue("lastSentMessage").intValue() : null;
    }

    private Date getLastSentAt(JsonNode jsonNode) {
        if (Objects.isNull(jsonNode) || Objects.isNull(jsonNode.findValue("lastSentAt"))) {
            return null;
        }
        return parseDate(jsonNode.findValue("lastSentAt").asText());
    }

    private void setLastSentInfo(JsonNode jsonNode, Integer lastSentMessage) {
        if (Objects.nonNull(jsonNode) && Objects.nonNull(lastSentMessage)) {
            ((ObjectNode) jsonNode).put("lastSentMessage", lastSentMessage);
            ((ObjectNode) jsonNode).put("lastSentAt", getSimpleDateFormat().format(new Date()));
            if (Objects.isNull(jsonNode.findValue("sentMessages"))) {
                ((ObjectNode) jsonNode).putArray("sentMessages");
            }
            ((ArrayNode) jsonNode.findValue("sentMessages")).add(lastSentMessage);
        }
    }

    // Date utils
    private Optional<Date> getNextFireTime(Date lastSentAt, TaskSchedule taskSchedule) {
        if (Objects.isNull(taskSchedule)
                || Objects.isNull(taskSchedule.payload)
                || Objects.isNull(taskSchedule.payload.findValue("start"))) {
            return null;
        }
        if (Objects.isNull(taskSchedule.payload.findValue("frequency"))
                || Objects.isNull(taskSchedule.payload.findValue("interval"))) {
            return Optional.empty();
        }

        JsonNode jsonNode = taskSchedule.payload;
        Date startDate = parseDate(jsonNode.findValue("start").asText());
        String frequency = jsonNode.findValue("frequency").asText();
        Integer interval = jsonNode.findValue("interval").asInt();

        Date nextFireTime = startDate;
        do {
            nextFireTime = increaseDate(nextFireTime, frequency, interval);
        } while (nextFireTime.before(lastSentAt));

        return Optional.of(nextFireTime);
    }

    private Date increaseDate(Date inputDate, String frequency, Integer interval) {
        LocalDateTime previousEecutionTime = inputDate.toInstant()
                .atZone(ZoneId.of(TIME_ZONE))
                .toLocalDateTime();

        LocalDateTime nextExecutionDate = switch (frequency) {
            case "minute" -> previousEecutionTime.plusMinutes(interval);
            case "day" -> previousEecutionTime.plusDays(interval);
            case "week" -> previousEecutionTime.plusWeeks(interval);
            case "month" -> previousEecutionTime.plusMonths(interval);
            default -> previousEecutionTime;
        };

        // TODO: switch to LocalDateTime everywhere?
        return Date.from(nextExecutionDate.atZone(ZoneId.of(TIME_ZONE)).toInstant());
    }

    private Date parseDate(String dateString) {
        Date date = null;
        try {
            date = getSimpleDateFormat().parse(dateString);
        } catch (ParseException parseException) {
            log.error("Failed to parse date from string {} using format {}! {}", dateString, DATE_FORMAT, parseException.getMessage());
        }
        return date;
    }

    private SimpleDateFormat getSimpleDateFormat() {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(DATE_FORMAT);
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone(TIME_ZONE));
        return simpleDateFormat;
    }

}
