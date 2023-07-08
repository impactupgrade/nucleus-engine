package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;

import java.util.List;

public interface JobLoggingService {

  void startLog(JobType jobType, String username, String jobName, String originatingPlatform);
  void endLog(JobStatus jobStatus);

  void info(String message, Object... params);
  void warn(String message, Object... params);
  void error(String message, Object... params);

  default Job getJob(String jobTraceId) {
    return null;
  }
  default List<Job> getJobs(JobType jobType) {
    return null;
  }
}