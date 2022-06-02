package com.impactupgrade.nucleus.model;

public class AbstractSearch {
  public Integer pageSize;
  public String pageToken;

  // Some CRMs use standard offset pagination, so the offset value will ultimately be set as the nextPageToken.
  public Integer getPageOffset() {
    return Integer.parseInt(pageToken);
  }
}
