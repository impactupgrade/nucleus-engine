package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;

import java.util.List;

public interface JobLoggingService {

  void startLog(JobType jobType, String username, String jobName, String originatingPlatform, JobStatus jobStatus, String message);
  void endLog(String message);

  void info(String message);
  void warn(String message);
  void error(String message);

  default Job getJob(String jobTraceId) {
    return null;
  }
  default List<Job> getJobs(JobType jobType) {
    return null;
  }
}