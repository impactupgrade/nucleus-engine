package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.StackLocatorUtil;

public class ConsoleJobLoggingService implements JobLoggingService {

  @Override
  public String name() {
    return "console";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return true;
  }

  @Override
  public void init(Environment env) {
  }

  @Override
  public void startLog(JobType jobType, String username, String jobName, String originatingPlatform) {
  }

  @Override
  public void endLog(JobStatus jobStatus) {
  }

  @Override
  public void info(String message, Object... params) {
    getLogger().info(message, params);
  }

  @Override
  public void warn(String message, Object... params) {
    getLogger().warn(message, params);
  }

  @Override
  public void error(String message, Object... params) {
    getLogger().error(message, params);
  }

  private Logger getLogger() {
    return LogManager.getLogger(StackLocatorUtil.getCallerClass(6));
  }
}
