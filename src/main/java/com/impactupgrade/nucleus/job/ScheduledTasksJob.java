package com.impactupgrade.nucleus.job;

import com.impactupgrade.nucleus.model.FutureTask;
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
        List<FutureTask> futureTasks = scheduledTasksService.getTasksToProcess();

        if (CollectionUtils.isNotEmpty(futureTasks)) {
            log.info("Found {} tasks to process.", futureTasks.size());

            for (FutureTask ft : futureTasks) {
                log.info("Found configuration: {}", ft.configuration);
                // TODO: process and delete
            }

            log.info("Tasks processed.");
        } else {
            log.info("Don't have any tasks to process. Returning...");
        }
    }

}
