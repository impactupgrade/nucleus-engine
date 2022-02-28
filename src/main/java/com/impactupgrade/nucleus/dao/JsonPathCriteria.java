package com.impactupgrade.nucleus.dao;

import java.util.Objects;

public class JsonPathCriteria implements Criteria {

  public String columnName;
  public String jsonPath;
  public String operator;
  public Object value;

  public JsonPathCriteria(String columnName, String jsonPath, String operator, Object value) {
    this.columnName = columnName;
    this.jsonPath = jsonPath;
    this.operator = operator;
    this.value = value;
  }

  @Override
  public String toSqlString() {
    jsonPath = jsonPath.replaceAll("\\.", "'->'");
    value = Objects.nonNull(value) ? "'" + value + "'" : "null";
    String condition = columnName + " -> '" + jsonPath + "' " + operator + value;
    condition = replaceLastOccurence(condition, "->", "->>");
    return condition;
  }

  private String replaceLastOccurence(String input, String toReplace, String replacement) {
    int start = input.lastIndexOf(toReplace);
    return input.substring(0, start) + replacement + input.substring(start + toReplace.length());
  }
}
