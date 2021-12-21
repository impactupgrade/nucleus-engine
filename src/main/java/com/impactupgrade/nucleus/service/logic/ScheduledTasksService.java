package com.impactupgrade.nucleus.service.logic;

import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.model.JsonPathCriteria;
import com.impactupgrade.nucleus.model.TaskProgress;
import com.impactupgrade.nucleus.model.TaskSchedule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.SessionFactory;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class ScheduledTasksService {

    private static final Logger log = LogManager.getLogger(ScheduledTasksService.class);

    private final HibernateDao<Long, TaskSchedule> taskScheduleDao;
    private final HibernateDao<Long, TaskProgress> taskProgressDao;
    private final TaskService taskService;

    public ScheduledTasksService(SessionFactory sessionFactory) {
        this.taskScheduleDao = new HibernateDao<>(TaskSchedule.class, sessionFactory);
        this.taskProgressDao = new HibernateDao<>(TaskProgress.class, sessionFactory);
        this.taskService = new TaskService(taskScheduleDao, taskProgressDao);
    }

    public List<TaskSchedule> getCurrentTaskSchedules() {
        List<JsonPathCriteria> criterias = Arrays.asList(
                new JsonPathCriteria("start", "<=", new Date()),
                new JsonPathCriteria("stop", ">=", new Date())
        );


        List<TaskSchedule> taskSchedules =
                //taskService.findTaskSchedules(criteria);
                //taskScheduleDao.getQueryResultList("from TaskSchedule ts where current_timestamp between ts.startTime and ts.endTime");
                taskService.findTaskSchedules(criterias);

        return taskSchedules;
    }

    public void processTaskSchedule(TaskSchedule taskSchedule) {
        Long taskId = taskSchedule.taskId;


        List<JsonPathCriteria> criterias = Arrays.asList(
                new JsonPathCriteria("start", ">=", new Date() + "")
        );


        List<TaskProgress> taskProgresses =
                taskService.findTaskProgresses(criterias);
        //Collections.emptyList();


        //Map<String, String> payload = taskProgresses.get(0).payload;
        //JsonNode payload = taskProgresses.get(0).payload;

        log.info("Current progress: {}", taskProgresses);

        //Optional<Task> taskOptional = taskDao.getById(taskId);

    }

}
