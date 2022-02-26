package com.impactupgrade.nucleus.model;

public interface JobExecutor {

    void execute(Job job, JobSchedule jobSchedule);
}
