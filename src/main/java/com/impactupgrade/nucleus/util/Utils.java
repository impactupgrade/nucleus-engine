package com.impactupgrade.nucleus.util;

public class Utils {

  public static String[] fullNameToFirstLast(String fullName) {
    String[] split = fullName.split("\s+");
    String firstName = split[0];
    String lastName = null;
    // Some donors are using a single-word business name in the individual name field, so this won't exist.
    if (split.length > 1) {
      // But we might also have some multi-word last names. So, catch them all. Rather than dealing with an array
      // slice, simply remove the first name, then trim leading whitespace.
      lastName = fullName.replace(firstName, "").trim();
    }
    return new String[]{firstName, lastName};
  }

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
