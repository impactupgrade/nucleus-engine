package com.impactupgrade.nucleus.service.logic;

import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.model.Criteria;
import com.impactupgrade.nucleus.model.JsonPathCriteria;
import com.impactupgrade.nucleus.model.Task;
import com.impactupgrade.nucleus.model.TaskProgress;
import com.impactupgrade.nucleus.model.TaskSchedule;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.SessionFactory;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public class ScheduledTasksService {

    private static final Logger log = LogManager.getLogger(ScheduledTasksService.class);

    private static final String DATE_FORMAT = "yyyy-MM-dd";
    private final SimpleDateFormat simpleDateFormat = new SimpleDateFormat(DATE_FORMAT);

    private final HibernateDao<Long, Task> taskDao;
    private final HibernateDao<Long, TaskSchedule> taskScheduleDao;
    private final HibernateDao<Long, TaskProgress> taskProgressDao;
    private final TaskService taskService;

    // TODO: find a better way of initializing the service
    public ScheduledTasksService(SessionFactory sessionFactory) {
        this.taskDao = new HibernateDao<>(Task.class, sessionFactory);
        this.taskScheduleDao = new HibernateDao<>(TaskSchedule.class, sessionFactory);
        this.taskProgressDao = new HibernateDao<>(TaskProgress.class, sessionFactory);
        this.taskService = new TaskService(taskScheduleDao, taskProgressDao);
    }

    public List<TaskSchedule> getCurrentTaskSchedules(Date fromDate, Date toDate) {
        return taskService.findTaskSchedules(buildScheduleCriteria(fromDate, toDate));
    }

    public void processTaskSchedules(Date fromDate, Date toDate) {
        List<TaskSchedule> taskSchedules = getCurrentTaskSchedules(fromDate, toDate);

        if (CollectionUtils.isNotEmpty(taskSchedules)) {
            for (TaskSchedule taskSchedule : taskSchedules) {
                Optional<Task> taskOptional = taskDao.getById(taskSchedule.taskId);

                if (taskOptional.isEmpty()) {
                    // Should be unreachable
                    log.warn("Task Schedule {} refers to unexisting task {}! Skipping...", taskSchedule.id, taskSchedule.taskId);
                    break;
                } else {
                    log.info("Processing task {}...", taskOptional.get().id);
                    // Process the task
                    processTask(taskOptional.get());
                    log.info("Task processed!");
                }
            }
        } else {
            log.info("Could not find any active schedules. Returning...");
        }

    }

    // Utils
    private Criteria buildScheduleCriteria(Date fromDate, Date toDate) {
        Calendar calendar = Calendar.getInstance();

        calendar.setTime(fromDate);
        int fromMinute = calendar.get(Calendar.MINUTE);

        calendar.setTime(toDate);
        String date = simpleDateFormat.format(calendar.getTime());
        int month = calendar.get(Calendar.MONTH);
        int weekOfMonth = calendar.get(Calendar.WEEK_OF_MONTH);
        int daysOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int toMinute = calendar.get(Calendar.MINUTE);

        Criteria isActiveCriteria = new Criteria(
                Arrays.asList(
                        new JsonPathCriteria("payload", "start", "<=", date),
                        new JsonPathCriteria("payload", "stop", ">=", date)
                ), "and"
        );

        Criteria monthCriteria = new Criteria(
                Arrays.asList(
                        new JsonPathCriteria("payload", "frequency.months", "like", "%" + getMonthForInt(month) + "%"),
                        new JsonPathCriteria("payload", "frequency.months", " is ", null)
                ), "or"
        );

        Criteria weekOfMonthCriteria = new Criteria(
                Arrays.asList(
                        new JsonPathCriteria("payload", "frequency.weekOfMonth", "like", "%" + weekOfMonth + "%"),
                        new JsonPathCriteria("payload", "frequency.weekOfMonth", " is ", null)
                ), "or"
        );

        Criteria daysOfWeekCriteria = new Criteria(
                Arrays.asList(
                        new JsonPathCriteria("payload", "frequency.daysOfWeek", "like", "%" + daysOfWeek + "%"),
                        new JsonPathCriteria("payload", "frequency.daysOfWeek", " is ", null)
                ), "or"
        );

        Criteria hourCriteria = new JsonPathCriteria("payload", "frequency.time.hour", "=", hour);

        Criteria minuteCriteria = new Criteria(
                Arrays.asList(
                        new JsonPathCriteria("payload", "frequency.time.min", ">=", fromMinute),
                        new JsonPathCriteria("payload", "frequency.time.min", "<=", toMinute)
                ), "and"
        );

        return new Criteria(
                Arrays.asList(
                        isActiveCriteria,
                        hourCriteria, minuteCriteria,
                        daysOfWeekCriteria,
                        weekOfMonthCriteria,
                        monthCriteria
                ), "and"
        );
    }

    private String getMonthForInt(int num) {
        String month = "wrong";
        DateFormatSymbols dfs = new DateFormatSymbols();
        String[] months = dfs.getMonths();
        if (num >= 0 && num <= 11) {
            month = months[num];
        }
        return month;
    }

    private void processTask(Task task) {
        Optional<TaskProgress> taskProgressOptional = taskProgressDao.getQueryResult(
                "select from TaskProgress where taskId = " + task.id);
        if (taskProgressOptional.isPresent()) {
            // do smth with progress
            log.info("Task progress is {]", taskProgressOptional.get());
        } else {
            // no progress - create one
            log.info("Task does not have any progress so far...");
        }

        // TODO:
        //TaskProgress taskProgress = new TaskProgress();
        //taskProgressDao.create(taskProgress);
    }

}
