package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;

public interface LoggingService {

  void startLog(JobType jobType, String username, String jobName, String originatingPlatform, JobStatus jobStatus, String message);

  void endLog(String message);

  void info(String message);

  void warn(String message);

  void error(String message);
}