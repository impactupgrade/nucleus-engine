package com.impactupgrade.nucleus.util;

import com.newrelic.api.agent.NewRelic;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

public class MetricUtil {

  private static final Logger log = LogManager.getLogger(MetricUtil.class);

  public static void event(String type, Map<String, Object> attributes) {
    // Never allow these to bubble up and screw with real processing!
    try {
      NewRelic.getAgent().getInsights().recordCustomEvent(type, attributes);
    } catch (Exception e) {
      log.info("failed to record NR event", e);
    }
  }
}
