package com.impactupgrade.nucleus.job;

import com.impactupgrade.nucleus.model.TaskSchedule;
import com.impactupgrade.nucleus.service.logic.ScheduledTasksService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

import java.util.List;

public class ScheduledTasksJob implements Job {

    private static final Logger log = LogManager.getLogger(ScheduledTasksJob.class);

    @Override
    public void execute(JobExecutionContext jobExecutionContext) {
        log.info("Running ScheduledTasks job...");

        JobDataMap jobDataMap = jobExecutionContext.getJobDetail().getJobDataMap();
        ScheduledTasksService scheduledTasksService = (ScheduledTasksService) jobDataMap.get("scheduledTasksService");

        List<TaskSchedule> taskSchedules = scheduledTasksService.getCurrentTaskSchedules();

        if (CollectionUtils.isNotEmpty(taskSchedules)) {
            log.info("Found {} tasks schedules to process.", taskSchedules.size());

            for (TaskSchedule ft : taskSchedules) {
                log.info("Schedule payload: {}", ft.payload);
                // TODO: process and delete

                scheduledTasksService.processTaskSchedule(ft);

            }

            log.info("All tasks processed.");
        } else {
            log.info("Don't have any tasks to process. Returning...");
        }
    }

}
