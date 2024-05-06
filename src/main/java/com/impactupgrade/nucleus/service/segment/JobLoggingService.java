/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;

import java.util.List;

public interface JobLoggingService extends SegmentService {

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

  default String format(String message, Object... params) {
    // Keeping the {} placeholder format, so we're compatible with log4j.
    for (int i = 0; i < params.length; i++) {
      Object param = params[i];
      if (param == null) param = "";

      if (i == params.length - 1 && param instanceof Throwable) {
        message = message + " :: " + ((Throwable) param).getMessage();
      } else {
        message = message.replace("{}", param.toString());
      }
    }
    return message;
  }
}