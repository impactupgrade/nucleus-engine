package com.impactupgrade.nucleus.service.logic;

import com.fasterxml.jackson.databind.JsonNode;
import com.impactupgrade.nucleus.entity.Job;

public interface JobExecutor {

  void execute(Job job) throws Exception;

  default String getJsonText(JsonNode jsonNode, String fieldName) {
    JsonNode value = getJsonNode(jsonNode, fieldName);
    if (value == null) {
      return null;
    }
    return value.asText();
  }

  default Integer getJsonInt(JsonNode jsonNode, String fieldName) {
    JsonNode value = getJsonNode(jsonNode, fieldName);
    if (value == null) {
      return null;
    }
    return value.asInt();
  }

  default JsonNode getJsonNode(JsonNode jsonNode, String fieldName) {
    if (jsonNode == null) {
      return null;
    }
    return jsonNode.findValue(fieldName);
  }
}
