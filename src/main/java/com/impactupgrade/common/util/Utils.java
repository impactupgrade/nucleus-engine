package com.impactupgrade.common.util;

public class Utils {

  public static boolean checkboxToBool(String checkboxValue) {
    return "yes".equalsIgnoreCase(checkboxValue)
        || "on".equalsIgnoreCase(checkboxValue)
        || "true".equalsIgnoreCase(checkboxValue);
  }

  public static String emptyStringToNull(String s) {
    // For update methods, don't allow empty string values to overwrite something already in SF.
    if (s == null || s.isEmpty()) {
      return null;
    }
    return s;
  }
}
