package com.impactupgrade.nucleus.service.logic;

import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.model.Task;
import com.impactupgrade.nucleus.model.TaskProgress;
import com.impactupgrade.nucleus.model.TaskProgressCriteria;
import com.impactupgrade.nucleus.model.TaskSchedule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.SessionFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ScheduledTasksService {

    private static final Logger log = LogManager.getLogger(ScheduledTasksService.class);

    private final SessionFactory sessionFactory;
    private final HibernateDao<Long, Task> taskDao;
    private final HibernateDao<Long, TaskSchedule> taskScheduleDao;
    private final HibernateDao<Long, TaskProgress> taskProgressDao;
    private final TaskService taskService;

    public ScheduledTasksService(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
        this.taskDao = new HibernateDao<>(Task.class, sessionFactory);
        this.taskScheduleDao = new HibernateDao<>(TaskSchedule.class, sessionFactory);
        this.taskProgressDao = new HibernateDao<>(TaskProgress.class, sessionFactory);
        this.taskService = new TaskService(taskDao, taskScheduleDao, taskProgressDao);
    }

    public List<TaskSchedule> getCurrentTaskSchedules() {
        List<TaskSchedule> taskSchedules =
                //taskService.findTaskSchedules(criteria);
                taskScheduleDao.getQueryResultList("from TaskSchedule ts where current_timestamp between ts.startTime and ts.endTime");

        return taskSchedules;
    }

    public void processTaskSchedule(TaskSchedule taskSchedule) {
        Long taskId = taskSchedule.taskId;

        TaskProgressCriteria taskProgressCriteria = new TaskProgressCriteria();
        taskProgressCriteria.taskIds = Arrays.asList(taskId);

        List<TaskProgress> taskProgresses =
                //taskService.findTaskProgresses(taskProgressCriteria);
                Collections.emptyList();

        if (taskProgresses.size() > 1) {
            // Should be unreachable
            throw new IllegalStateException("More than 1 progress found for task '" + taskId + "'");
        }

        Map<String, String> payload = taskProgresses.get(0).payload;

        log.info("Current progress: {}", payload);

        //Optional<Task> taskOptional = taskDao.getById(taskId);

    }

}
