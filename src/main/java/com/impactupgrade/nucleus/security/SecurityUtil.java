/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.security;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;

/**
 * An API key must be sent by the Nucleus UI and other non-public-webhook clients, included as a header.
 */
public class SecurityUtil {

  public static void verifyApiKey(Environment env) throws SecurityException {
    String apiKey = env.getHeaders().get("Nucleus-Api-Key");
    if (Strings.isNullOrEmpty(apiKey)) {
      apiKey = env.getQueryParams().get("Nucleus-Api-Key");
    }
    if (!env.getConfig().apiKey.equalsIgnoreCase(apiKey)) {
      env.logJobWarn("unable to verify api key");
      throw new SecurityException();
    }
  }
}
