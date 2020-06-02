package com.impactupgrade.common.util;

import org.apache.logging.log4j.Logger;

public class LoggingUtil {

  private static final boolean VERBOSE = System.getenv("VERBOSE") != null && Boolean.parseBoolean(System.getenv("VERBOSE"));

  /**
   * If in VERBOSE mode, log it. Note that we require a Logger to be passed, rather than using a logger
   * tied to LoggingUtil itself. For traceability's sake, we want to see where the log line came from!
   *
   * @param log
   * @param s
   * @param params
   */
  public static void verbose(Logger log, String s, Object... params) {
    if (VERBOSE) log.info(s, params);
  }
}
