package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.StackLocatorUtil;

public class ConsoleLoggingService implements LoggingService {

  protected Environment env;

  public ConsoleLoggingService(Environment env) {
    this.env = env;
  }

  @Override
  public void startLog(JobType jobType, String username, String jobName, String originatingPlatform, JobStatus jobStatus, String message) {
    info(message);
  }

  @Override
  public void endLog(String message) {
    info(message);
  }

  @Override
  public void info(String message) {
    getLogger().info(message);
  }

  @Override
  public void warn(String message) {
    getLogger().warn(message);
  }

  @Override
  public void error(String message) {
    getLogger().error(message);
  }

  private Logger getLogger() {
    return LogManager.getLogger(StackLocatorUtil.getCallerClass(4));
  }
}
