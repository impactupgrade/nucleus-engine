package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.rollbar.notifier.Rollbar;
import com.rollbar.notifier.config.Config;
import com.rollbar.notifier.config.ConfigBuilder;
import com.rollbar.notifier.sender.SyncSender;

public class RollbarLoggingService implements JobLoggingService {
  
  private static final String ACCESS_TOKEN = "ac92db5515c141d785fef9b0cdd6dc46";

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
        .withAccessToken(ACCESS_TOKEN)
        .environment(env.getConfig().rollbar.env)
        .codeVersion(env.getConfig().rollbar.codeVersion)
        .sender(new SyncSender.Builder().build())
        .build();

    this.rollbar = new Rollbar(config);
  }

  @Override
  public void startLog(JobType jobType, String username, String jobName, String originatingPlatform) {
    // TODO: No value in Rollbar having start/end logs?

//    String message = "Started job '" + jobTraceId + "'";
//    Map customParams = Map.of(
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
    rollbar.error(format(message, params));
  }

  @Override
  public void endLog(JobStatus jobStatus) {
    // TODO: No value in Rollbar having start/end logs?

//    String message = "Ended job '" + jobTraceId + "' with status '" + jobStatus + "'";
//    Map customParams = Map.of(
//        "nucleusApiKey", env.getConfig().apiKey,
//        "jobTraceId", jobTraceId,
//        "jobStatus", jobStatus,
//        "endedAt", Instant.now());
//
//    rollbar.info(message, customParams);
  }
}
