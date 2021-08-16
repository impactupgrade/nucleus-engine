/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.security;

import com.impactupgrade.nucleus.environment.Environment;

/**
 * An API key must be sent by the Nucleus UI and other non-public-webhook clients, included as a header.
 */
public class SecurityUtil {

  public static void verifyApiKey(Environment env) throws SecurityException {
    String apikey = env.getHeaders().get("Nucleus-Api-Key");
    if (!env.getConfig().apiKey.equalsIgnoreCase(apikey)) {
      throw new SecurityException();
    }
  }
}
