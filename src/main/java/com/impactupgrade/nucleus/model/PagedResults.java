package com.impactupgrade.nucleus.model;

import java.util.List;

public class PagedResults<T> {
  private final List<T> results;
  private final Integer pageSize;
  // Could be a numeric offset or string cursor -- each usage must know which to expect
  private final String nextPageToken;

  public PagedResults(List<T> results, Integer pageSize, String nextPageToken) {
    this.results = results;
    this.pageSize = pageSize;
    this.nextPageToken = nextPageToken;
  }

  public List<T> getResults() {
    return results;
  }

  public Integer getPageSize() {
    return pageSize;
  }

  public boolean hasMorePages() {
    return results.size() == pageSize;
  }

  public String getNextPageToken() {
    return nextPageToken;
  }
}
