package com.impactupgrade.nucleus.util;

import com.newrelic.api.agent.NewRelic;

import java.util.Map;

public class MetricUtil {

  public static void event(String type, Map<String, Object> attributes) {
    NewRelic.getAgent().getInsights().recordCustomEvent(type, attributes);
  }
}
