package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.rollbar.notifier.Rollbar;
import com.rollbar.notifier.config.Config;
import com.rollbar.notifier.config.ConfigBuilder;
import com.rollbar.notifier.sender.SyncSender;

import java.util.Map;

public class RollbarLoggingService implements JobLoggingService {
  
  protected Environment env;
  protected String jobTraceId;
  
  protected Rollbar rollbar;

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

    Config config = ConfigBuilder
        .withAccessToken(env.getConfig().rollbar.secretKey)
        .environment(env.getConfig().getProfile())
        .codeVersion("4.0.0-SNAPSHOT") // TODO: not sure if this will matter for us
        .sender(new SyncSender.Builder().build())
        .build();

    this.rollbar = new Rollbar(config);
  }

  @Override
  public void startLog(JobType jobType, String username, String jobName, String originatingPlatform) {
    // TODO: No value in Rollbar having start/end logs?

//    String message = "Started job '" + jobTraceId + "'";
//    Map<String, Object> customParams = Map.of(
//        "nucleusApiKey", env.getConfig().apiKey,
//        "jobTraceId", jobTraceId,
//        "jobName", jobName,
//        "jobType", jobType != null ? jobType.name() : null,
//        "startedBy", username,
//        "originatingPlatform", originatingPlatform,
//        "startedAt", Instant.now(),
//        "scheduleTz", "UTC"
//    );
//
//    rollbar.info(message, customParams);
  }

  @Override
  public void info(String message, Object... params) {
    // TODO: No value in Rollbar having info logs?

//    rollbar.info(format(message, params));
  }

  @Override
  public void warn(String message, Object... params) {
    rollbar.warning(format(message, params));
  }

  @Override
  public void error(String message, Object... params) {
    Throwable t = null;
    for (Object param : params) {
      if (param instanceof Throwable) {
        t = (Throwable) param;
      }
    }

    Map<String, Object> customParams = Map.of(
        "nucleusApiKey", env.getConfig().apiKey,
        "jobTraceId", jobTraceId
    );

    rollbar.error(t, customParams, format(message, params));
  }

  @Override
  public void endLog(JobStatus jobStatus) {
    // TODO: No value in Rollbar having start/end logs?

//    String message = "Ended job '" + jobTraceId + "' with status '" + jobStatus + "'";
//    Map<String, Object> customParams = Map.of(
//        "nucleusApiKey", env.getConfig().apiKey,
//        "jobTraceId", jobTraceId,
//        "jobStatus", jobStatus,
//        "endedAt", Instant.now());
//
//    rollbar.info(message, customParams);
  }
}
