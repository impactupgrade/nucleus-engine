package com.impactupgrade.nucleus.job;

import com.impactupgrade.nucleus.service.logic.ScheduledTasksService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

import java.util.Date;

public class ScheduledTasksJob implements Job {

    private static final Logger log = LogManager.getLogger(ScheduledTasksJob.class);

    @Override
    public void execute(JobExecutionContext jobExecutionContext) {
        log.info("Running ScheduledTasks job...");
        JobDataMap jobDataMap = jobExecutionContext.getJobDetail().getJobDataMap();
        ScheduledTasksService scheduledTasksService = (ScheduledTasksService) jobDataMap.get("scheduledTasksService");
        scheduledTasksService.processTaskSchedules(jobExecutionContext.getTrigger().getPreviousFireTime(), new Date());
    }

}
