/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.fasterxml.jackson.databind.JsonNode;
import com.impactupgrade.nucleus.entity.Job;

import java.time.Instant;

public interface JobExecutor {

  void execute(Job job, Instant now) throws Exception;

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

  default Boolean getJsonBoolean(JsonNode jsonNode, String fieldName) {
    JsonNode value = getJsonNode(jsonNode, fieldName);
    if (value == null) {
      return null;
    }
    return value.asBoolean();
  }

  default Long getJsonLong(JsonNode jsonNode, String fieldName) {
    JsonNode value = getJsonNode(jsonNode, fieldName);
    if (value == null) {
      return null;
    }
    return value.asLong();
  }

  default JsonNode getJsonNode(JsonNode jsonNode, String fieldName) {
    if (jsonNode == null) {
      return null;
    }
    return jsonNode.findValue(fieldName);
  }
}
