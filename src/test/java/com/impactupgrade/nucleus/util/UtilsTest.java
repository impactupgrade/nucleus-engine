package com.impactupgrade.nucleus.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UtilsTest {

  @Test
  public void parsePhoneNumber_validNumbers() {
    assertEquals(List.of("260", "349", "5732"), Utils.parsePhoneNumber("+12603495732"));
    assertEquals(List.of("260", "349", "5732"), Utils.parsePhoneNumber("260-349-5732"));
    assertEquals(List.of("260", "349", "5732"), Utils.parsePhoneNumber("(260) 349-5732"));
    assertEquals(List.of("260", "349", "5732"), Utils.parsePhoneNumber("260.349.5732"));

    assertEquals(List.of("977", "471", "695"), Utils.parsePhoneNumber("+380977471695"));
    assertEquals(List.of("977", "471", "695"), Utils.parsePhoneNumber("977471695"));
    assertEquals(List.of("977", "471", "695"), Utils.parsePhoneNumber("(97)74-71-695"));
    assertEquals(List.of("977", "471", "695"), Utils.parsePhoneNumber("97-74-71-695"));
  }

  @Test
  public void parsePhoneNumber_emptyOrNull() {
    assertEquals(List.of(), Utils.parsePhoneNumber(""));
    assertEquals(List.of(), Utils.parsePhoneNumber(null));
  }

  @Test
  public void parsePhoneNumber_internationalNumberTooShort() {
    assertEquals(List.of(), Utils.parsePhoneNumber("+3809747"));
  }

  @Test
  public void parsePhoneNumber_internationalCodeNotValid() {
    assertEquals(List.of(), Utils.parsePhoneNumber("+42212345"));
  }
}
