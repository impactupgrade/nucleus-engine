/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Strings;
import org.apache.commons.collections4.map.CaseInsensitiveMap;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public abstract class CrmRecord implements Serializable {

  public String id;
  public String recordTypeId;
  public String recordTypeName;

  @JsonIgnore
  protected Map<String, String> rawData = new CaseInsensitiveMap<>(); // Keep this protected -- ensure it always stays a CaseInsensitiveMap.

  @JsonIgnore
  public Object crmRawObject;
  @JsonIgnore
  public Map<String, Object> crmRawFieldsToSet = new HashMap<>();
  @JsonIgnore
  public String crmUrl;

  public CrmRecord() {

  }

  // A few cases where we only care about existence and require only the id.
  public CrmRecord(String id) {
    this.id = id;
  }

  public CrmRecord(String id, Object crmRawObject, String crmUrl) {
    this.id = id;
    this.crmRawObject = crmRawObject;
    this.crmUrl = crmUrl;
  }

  public CrmRecord(
      String id,
      String recordTypeId,
      String recordTypeName,
      Object crmRawObject,
      String crmUrl
  ) {
    this(id, crmRawObject, crmUrl);
    this.recordTypeId = recordTypeId;
    this.recordTypeName = recordTypeName;
  }

  public void addRawData(String key, String value) {
    rawData.put(key, value);
  }

  public Map<String, String> getAllRawData() {
    return rawData;
  }

  public String getRawData(String key) {
    return getRawData(Set.of(key));
  }

  public String getRawData(Collection<String> keys) {
    String value = null;

    for (String key : keys) {
      if (rawData.containsKey(key) && !Strings.isNullOrEmpty(rawData.get(key))) {
        value = rawData.get(key);
        break;
      }
    }

    if (value != null) {
      // IMPORTANT: The keys and values are sometimes copy/pasted by a human and we've had issues with whitespace.
      // Strip it! But note that sometimes it's something like a non-breaking space char (pasted from a doc?),
      // so convert that to a standard space first.
      value = value.replaceAll("[\\h+]", " ");
      value = value.trim();
    }

    return value;
  }
}
