package com.impactupgrade.nucleus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Strings;
import org.apache.commons.collections4.map.CaseInsensitiveMap;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class CrmRecord implements Serializable {

  public String id;

  @JsonIgnore
  protected Map<String, String> metadata = new CaseInsensitiveMap<>();

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

  public void addMetadata(String key, String value) {
    metadata.put(key, value);
  }

  public Map<String, String> getAllMetadata() {
    return metadata;
  }

  public String getMetadataValue(String metadataKey) {
    return getMetadataValue(Set.of(metadataKey));
  }

  public String getMetadataValue(Collection<String> metadataKeys) {
    String metadataValue = null;

    for (String metadataKey : metadataKeys) {
      if (metadata.containsKey(metadataKey) && !Strings.isNullOrEmpty(metadata.get(metadataKey))) {
        metadataValue = metadata.get(metadataKey);
        break;
      }
    }

    if (metadataValue != null) {
      // IMPORTANT: The keys and values are sometimes copy/pasted by a human and we've had issues with whitespace.
      // Strip it! But note that sometimes it's something like a non-breaking space char (pasted from a doc?),
      // so convert that to a standard space first.
      metadataValue = metadataValue.replaceAll("[\\h+]", " ");
      metadataValue = metadataValue.trim();
    }

    return metadataValue;
  }
}
