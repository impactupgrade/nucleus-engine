package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.entity.JobType;

import java.util.List;

public interface JobLoggingService {

  void startLog(JobType jobType, String username, String jobName, String originatingPlatform, String message);
  void endLog(String message);

  void info(String message);
  void warn(String message);
  void warn(String message, Throwable t);
  void error(String message, boolean end);
  void error(String message, Throwable t, boolean end);

  default Job getJob(String jobTraceId) {
    return null;
  }
  default List<Job> getJobs(JobType jobType) {
    return null;
  }
}