/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractSearch {
  public List<String> ids = new ArrayList<>();
  public List<String> keywords = new ArrayList<>();
  public List<String> names = new ArrayList<>();

  public Map<String, List<String>> customFields = new HashMap<>();

  public boolean basicSearch = false;

  public Integer pageSize;
  public String pageToken;

  public void keywordString(String keywordString) {
    if (!Strings.isNullOrEmpty(keywordString)) {
      String[] keywordSplit = keywordString.trim().split("\\s+");
      keywords.addAll(List.of(keywordSplit));
    }
  }

  // Some CRMs use standard offset pagination, so the offset value will ultimately be set as the nextPageToken.
  public Integer getPageOffset() {
    if (Strings.isNullOrEmpty(pageToken)) {
      return 0;
    }
    return Integer.parseInt(pageToken);
  }
}
