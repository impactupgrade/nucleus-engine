package com.impactupgrade.nucleus.util;

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
  // TODO: Passing in a logger was intended to make use of the caller's classname in logs, but that doesn't
  // appear to be happening...
  public static void verbose(Logger log, String s, Object... params) {
    if (VERBOSE) log.info(s, params);
  }
}
