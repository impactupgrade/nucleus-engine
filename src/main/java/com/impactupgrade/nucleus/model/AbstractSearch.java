/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;

public class AbstractSearch {
  public Integer pageSize;
  public String pageToken;

  // Some CRMs use standard offset pagination, so the offset value will ultimately be set as the nextPageToken.
  public Integer getPageOffset() {
    if (Strings.isNullOrEmpty(pageToken)) {
      return 0;
    }
    return Integer.parseInt(pageToken);
  }
}
