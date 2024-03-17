package com.impactupgrade.nucleus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Strings;
import org.apache.commons.collections4.map.CaseInsensitiveMap;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class CrmRecord implements Serializable {

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

  // Using FP, allow this object to retrieve fields from its rawObject. Calls to the constructor provide a
  // CRM-specific function.
  @JsonIgnore
  public Function<String, Object> fieldFetcher;

  public CrmRecord() {

  }

  // A few cases where we only care about existence and require only the id.
  public CrmRecord(String id) {
    this.id = id;
  }

  public CrmRecord(String id, Object crmRawObject, String crmUrl, Function<String, Object> fieldFetcher) {
    this(id);
    this.crmRawObject = crmRawObject;
    this.crmUrl = crmUrl;
    this.fieldFetcher = fieldFetcher;
  }

  public CrmRecord(
      String id,
      String recordTypeId,
      String recordTypeName,
      Object crmRawObject,
      String crmUrl,
      Function<String, Object> fieldFetcher
  ) {
    this(id, crmRawObject, crmUrl, fieldFetcher);
    this.recordTypeId = recordTypeId;
    this.recordTypeName = recordTypeName;
  }

  public void addRawData(String key, String value) {
    rawData.put(key, value);
  }

  public Map<String, String> getAllRawData() {
    return rawData;
  }

  public String getRawData(String metadataKey) {
    return getRawData(Set.of(metadataKey));
  }

  public String getRawData(Collection<String> metadataKeys) {
    String value = null;

    for (String metadataKey : metadataKeys) {
      if (rawData.containsKey(metadataKey) && !Strings.isNullOrEmpty(rawData.get(metadataKey))) {
        value = rawData.get(metadataKey);
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
