package com.impactupgrade.nucleus.service.logic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.model.Criteria;
import com.impactupgrade.nucleus.model.JsonPathCriteria;
import com.impactupgrade.nucleus.model.Task;
import com.impactupgrade.nucleus.model.TaskProgress;
import com.impactupgrade.nucleus.model.TaskSchedule;
import com.impactupgrade.nucleus.util.HibernateUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.SessionFactory;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.stream.Collectors;

import static com.impactupgrade.nucleus.model.TaskType.SMS_CAMPAIGN;

public class TaskService {

    private static final Logger log = LogManager.getLogger(TaskService.class);
    private static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'GMT'Z";

    private final SimpleDateFormat simpleDateFormat = new SimpleDateFormat(DATE_FORMAT);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HibernateDao<Long, Task> taskDao;
    private final HibernateDao<Long, TaskSchedule> taskScheduleDao;
    private final HibernateDao<Long, TaskProgress> taskProgressDao;

    public TaskService(SessionFactory sessionFactory) {
        this.taskDao = new HibernateDao<>(Task.class, sessionFactory);
        this.taskScheduleDao = new HibernateDao<>(TaskSchedule.class, sessionFactory);
        this.taskProgressDao = new HibernateDao<>(TaskProgress.class, sessionFactory);
        this.simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    public List<TaskSchedule> findTaskSchedules(Criteria criteria) {
        String query = HibernateUtil.buildNativeQuery("public", "task_schedule", criteria);
        return taskScheduleDao.getQueryResultList(query, true);
    }

    public void processTaskSchedules() {
        List<TaskSchedule> taskSchedules = findTaskSchedules(buildTaskScheduleCriteria());
        if (CollectionUtils.isNotEmpty(taskSchedules)) {
            for (TaskSchedule taskSchedule : taskSchedules) {
                Optional<Task> taskOptional = taskDao.getById(taskSchedule.taskId);
                if (taskOptional.isEmpty()) {
                    // Should be unreachable
                    log.warn("Task Schedule {} refers to un-existing task {}! Skipping...", taskSchedule.id, taskSchedule.taskId);
                    continue;
                } else {
                    Optional<Long> scheduleIntervalMsOptional = getScheduleIntervalMs(taskSchedule);
                    if (Objects.isNull(scheduleIntervalMsOptional)) {
                        // Should be unreachable
                        throw new IllegalArgumentException("Failed to get schedule interval for task schedule {}! " + taskSchedule);
                    }
                    processTask(taskOptional.get(), scheduleIntervalMsOptional);
                }
            }
        } else {
            log.info("Could not find any active task schedules. Returning...");
        }
    }

    public void processTask(Task task, Optional<Long> scheduleIntervalMsOptional) {
        log.info("Processing task {}...", task.id);
        // Process sms campaign task type
        if (SMS_CAMPAIGN == task.taskType) {
            String contactListId = getCrmContactListId(task.payload);
            if (Strings.isNullOrEmpty(contactListId)) {
                // Should be unreachable
                throw new IllegalArgumentException("Failed to get contact list id for task! " + task);
            }

            List<String> contactIds = getCrmContactIds(contactListId);
            if (CollectionUtils.isEmpty(contactIds)) {
                // No one to send to!
                log.warn("Could not get contact ids for task {}! Returning...", task.id);
                return;
            }

            Integer messageCount = getMessageCount(task.payload);
            if (Objects.isNull(messageCount)) {
                // Should be unreachable
                throw new IllegalArgumentException("Failed to get message count for task! " + task);
            }
            if (messageCount == 0) {
                // Nothing to send!
                log.warn("Could not get any messages for task {}! Returning...", task.id);
                return;
            }

            List<TaskProgress> taskProgresses = taskProgressDao.getQueryResultList(
                    "from TaskProgress tp where tp.taskId = " + task.id);
            Map<String, TaskProgress> progressesByContacts =
                    CollectionUtils.isNotEmpty(taskProgresses) ?
                            taskProgresses.stream()
                                    .collect(Collectors.toMap(tp -> tp.contactId, tp -> tp))
                            : Collections.emptyMap();

            for (String contactId : contactIds) {

                Integer nextMessage = 1;
                TaskProgress taskProgress = progressesByContacts.get(contactId);

                if (Objects.isNull(taskProgress)) {
                    log.info("Contact id {} does not have any progress so far...", contactId);
                } else {
                    log.info("Checking existing progress for contact id {}...", contactId);

                    Long lastSentAt = getLastSentAt(taskProgress.payload);
                    if (Objects.isNull(lastSentAt)) {
                        // Should be unreachable
                        throw new IllegalArgumentException("Failed to get last sent timestamp for task progress! " + taskProgress);
                    }
                    if (scheduleIntervalMsOptional.isPresent()) {
                        if (System.currentTimeMillis() - lastSentAt < scheduleIntervalMsOptional.get()) {
                            log.warn("Message already sent at {}. Skipping...", new Date(lastSentAt));
                            continue;
                        }
                    }

                    Integer lastSentMessage = getLastSentMessage(taskProgress.payload);
                    if (Objects.isNull(lastSentMessage)) {
                        // Should be unreachable
                        throw new IllegalArgumentException("Failed to get last sent message from task progress! " + taskProgress);
                    }
                    log.info("Last sent message for contact id '{}' is {}", contactId, lastSentMessage);
                    nextMessage = lastSentMessage + 1;
                    log.info("Next message to send is: {}", nextMessage);
                }

                if (nextMessage > messageCount) {
                    log.info("All messages sent for contact id '{}'!", contactId);
                    continue;
                }

                log.info("Sending message {} to contact id '{}'...", nextMessage, contactId);
                // TODO: send a message
                log.info("Message sent!");

                if (Objects.isNull(taskProgress)) {
                    // Create new task progress
                    taskProgress = new TaskProgress();
                    taskProgress.taskId = task.id;
                    taskProgress.contactId = contactId;
                    taskProgress.payload = objectMapper.createObjectNode();
                    setLastSentInfo(taskProgress.payload, nextMessage);
                    taskProgressDao.create(taskProgress);
                } else {
                    // Update existing task progress
                    setLastSentInfo(taskProgress.payload, nextMessage);
                    taskProgressDao.update(taskProgress);
                }
            }
            log.info("Task processed!");

        } else {
            log.warn("Task type {} is not yet supported!", task.taskType);
        }

    }

    // Utils
    private Optional<Long> getScheduleIntervalMs(TaskSchedule taskSchedule) {
        if (Objects.isNull(taskSchedule) || Objects.isNull(taskSchedule.payload)) {
            return null;
        }
        if (Objects.isNull(taskSchedule.payload.findValue("frequency"))
                || Objects.isNull(taskSchedule.payload.findValue("interval"))) {
            return Optional.empty();
        }

        JsonNode jsonNode = taskSchedule.payload;
        String frequency = jsonNode.findValue("frequency").asText();
        Integer interval = jsonNode.findValue("interval").asInt();

        Long msInOneMinute = 60 * 1000l;
        Long msInOneDay = 24 * 60 * msInOneMinute;

        Long scheduledIntervalMs = switch (frequency) {
            case "minute" -> interval * msInOneMinute;
            case "day" -> interval * msInOneDay;
            case "week" -> interval * msInOneDay * 7;
            case "month" -> interval * msInOneDay * 7 * 30; // 28/31 days?
            default -> msInOneDay;
        };

        return Optional.of(scheduledIntervalMs);
    }

    private String getCrmContactListId(JsonNode jsonNode) {
        return Objects.nonNull(jsonNode) || Objects.nonNull(jsonNode.findValue("crm_list")) ?
                jsonNode.findValue("crm_list").asText() : null;
    }

    private List<String> getCrmContactIds(String contactListId) {
        // TODO: call CRM service?
        return List.of("contact1", "contact2");
    }

    private Integer getMessageCount(JsonNode jsonNode) {
        return Objects.nonNull(jsonNode) && Objects.nonNull(jsonNode.findValue("messages")) ?
                jsonNode.get("messages").size() : null;
    }

    private Integer getLastSentMessage(JsonNode jsonNode) {
        return Objects.nonNull(jsonNode) && Objects.nonNull(jsonNode.findValue("lastSentMessage")) ?
                jsonNode.findValue("lastSentMessage").intValue() : null;
    }

    private Long getLastSentAt(JsonNode jsonNode) {
        if (Objects.isNull(jsonNode) || Objects.isNull(jsonNode.findValue("lastSentAt"))) {
            return null;
        }
        Long lastSentAt = null;
        String dateString = jsonNode.findValue("lastSentAt").asText();
        try {
            Date lastSentTimestamp = simpleDateFormat.parse(dateString);
            lastSentAt = lastSentTimestamp.getTime();
        } catch (ParseException parseException) {
            log.warn("Failed to parse date from string {} using format {}!", dateString, DATE_FORMAT);
        }
        return lastSentAt;
    }

    private void setLastSentInfo(JsonNode jsonNode, Integer lastSentMessage) {
        if (Objects.nonNull(jsonNode) && Objects.nonNull(lastSentMessage)) {
            ((ObjectNode) jsonNode).put("lastSentMessage", lastSentMessage);
            ((ObjectNode) jsonNode).put("lastSentAt", simpleDateFormat.format(new Date()));

            if (Objects.isNull(jsonNode.findValue("sentMessages"))) {
                ((ObjectNode) jsonNode).putArray("sentMessages");
            }
            ((ArrayNode) jsonNode.findValue("sentMessages")).add(lastSentMessage);
        }
    }

    // Search utils
    private Criteria buildTaskScheduleCriteria() {
        String dateString = simpleDateFormat.format(new Date());
        Criteria activeSchedulesCriteria = new Criteria(
                Arrays.asList(
                        new JsonPathCriteria("payload", "start", "<=", dateString),
                        new JsonPathCriteria("payload", "stop", ">=", dateString)
                ), "and"
        );

        return activeSchedulesCriteria;
    }

}
