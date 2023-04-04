package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;

public interface LoggingService {

  void info(JobType jobType, String username, String jobName, String originatingPlatform, JobStatus jobStatus, String message);

  default void info(String message) {
    info(null, null, null, null, null, message);
  }

  void warn(String message);

  void error(String message);
}