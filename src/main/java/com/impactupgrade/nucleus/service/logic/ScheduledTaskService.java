package com.impactupgrade.nucleus.service.logic;

import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.AggregateCriteria;
import com.impactupgrade.nucleus.model.Criteria;
import com.impactupgrade.nucleus.model.JsonPathCriteria;
import com.impactupgrade.nucleus.model.SmsCampaignTaskExecutor;
import com.impactupgrade.nucleus.model.Task;
import com.impactupgrade.nucleus.model.TaskSchedule;
import com.impactupgrade.nucleus.util.HibernateUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.SessionFactory;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;

import static com.impactupgrade.nucleus.model.TaskType.SMS_CAMPAIGN;

public class ScheduledTaskService {

    public static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";
    public static final String TIME_ZONE = "UTC";

    private static final Logger log = LogManager.getLogger(ScheduledTaskService.class);

    private final HibernateDao<Long, Task> taskDao;
    private final HibernateDao<Long, TaskSchedule> taskScheduleDao;

    public ScheduledTaskService(SessionFactory sessionFactory) {
        this.taskDao = new HibernateDao<>(Task.class, sessionFactory);
        this.taskScheduleDao = new HibernateDao<>(TaskSchedule.class, sessionFactory);
    }

    public void processTaskSchedules(Environment environment) {
        String query = HibernateUtil.buildNativeQuery("task_schedule", buildTaskScheduleCriteria());
        List<TaskSchedule> taskSchedules = taskScheduleDao.getQueryResultList(query, true);

        if (CollectionUtils.isEmpty(taskSchedules)) {
            log.info("Could not find any active task schedules. Returning...");
        }
        for (TaskSchedule taskSchedule : taskSchedules) {
            if (Objects.isNull(taskSchedule.task)) {
                // Should be unreachable
                log.warn("Task Schedule {} refers to un-existing task! Skipping...", taskSchedule.id);
                continue;
            } else {
                processTaskSchedule(taskSchedule, environment);
            }
        }
    }

    public void processTaskSchedule(TaskSchedule taskSchedule, Environment environment) {
        Task task = taskSchedule.task;
        log.info("Processing task {}...", task.id);
        if (SMS_CAMPAIGN == task.taskType) {
            // Process sms campaign task type
            new SmsCampaignTaskExecutor(taskDao, environment).execute(task, taskSchedule);
        } else {
            log.warn("Task type {} is not yet supported!", task.taskType);
        }
    }

    // Utils
    private Criteria buildTaskScheduleCriteria() {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(DATE_FORMAT);
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone(TIME_ZONE));
        String dateString = simpleDateFormat.format(new Date());
        AggregateCriteria activeScheduleCriteria = new AggregateCriteria(
                Arrays.asList(
                        new JsonPathCriteria("payload", "start", "<=", dateString),
                        new JsonPathCriteria("payload", "stop", ">=", dateString)
                ), "and"
        );

        return activeScheduleCriteria;
    }

}
