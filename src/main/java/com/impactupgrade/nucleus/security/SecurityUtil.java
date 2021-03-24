package com.impactupgrade.nucleus.security;

import javax.servlet.http.HttpServletRequest;

/**
 * An API key must be sent by the Hub UI and other non-public-webhook clients, included as a header.
 */
public class SecurityUtil {

  private static final String APIKEY = System.getenv("APIKEY");

  public static void verifyApiKey(HttpServletRequest request) throws SecurityException {
    String apikey = request.getHeader("APIKEY");
    if (!APIKEY.equalsIgnoreCase(apikey)) {
      throw new SecurityException();
    }
  }
}
