/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;

public class RollbarLoggingService implements JobLoggingService {
  
  protected Environment env;
  protected String jobTraceId;
  
//  protected Rollbar rollbar;

  @Override
  public String name() {
    return "rollbar";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().rollbar != null;
  }

  @Override
  public void init(Environment env) {
    this.env = env;
    this.jobTraceId = env.getJobTraceId();

//    Config config = ConfigBuilder
//        .withAccessToken(env.getConfig().rollbar.secretKey)
//        .environment(env.getConfig().getProfile())
//        .codeVersion("4.0.0-SNAPSHOT") // TODO: not sure if this will matter for us
//        .sender(new SyncSender.Builder().build())
//        .build();
//
//    this.rollbar = new Rollbar(config);
  }

  @Override
  public void startLog(JobType jobType, String username, String jobName, String originatingPlatform) {
  }

  @Override
  public void info(String message, Object... params) {
  }

  @Override
  public void warn(String message, Object... params) {
//    rollbar.warning(format(message, params));
  }

  @Override
  public void error(String message, Object... params) {
//    Throwable t = null;
//    for (Object param : params) {
//      if (param instanceof Throwable) {
//        t = (Throwable) param;
//      }
//    }
//
//    Map<String, Object> customParams = Map.of(
//        "nucleusApiKey", env.getConfig().apiKey,
//        "jobTraceId", jobTraceId
//    );
//
//    rollbar.error(t, customParams, format(message, params));
  }

  @Override
  public void endLog(JobStatus jobStatus) {
  }
}
