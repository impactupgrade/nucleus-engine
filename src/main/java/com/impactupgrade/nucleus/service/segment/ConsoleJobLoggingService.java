package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.StackLocatorUtil;

public class ConsoleJobLoggingService implements JobLoggingService {

  protected Environment env;

  public ConsoleJobLoggingService(Environment env) {
    this.env = env;
  }

  @Override
  public void startLog(JobType jobType, String username, String jobName, String originatingPlatform, String message) {
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
  public void warn(String message, Throwable t) {
    getLogger().warn(message, t);
  }

  @Override
  public void error(String message, boolean end) {
    getLogger().error(message);
  }

  @Override
  public void error(String message, Throwable t, boolean end) {
    getLogger().error(message, t);
  }

  private Logger getLogger() {
    return LogManager.getLogger(StackLocatorUtil.getCallerClass(5));
  }
}
