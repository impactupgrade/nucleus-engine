package com.impactupgrade.nucleus.model;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

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

  public Optional<T> getSingleResult() {
    if (results.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(results.get(0));
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

  public static <T> PagedResults<T> getPagedResultsFromCurrentOffset(T result, AbstractSearch currentSearch) {
    List<T> results = result == null ? Collections.emptyList() : List.of(result);
    return getPagedResultsFromCurrentOffset(results, currentSearch);
  }

  public static <T> PagedResults<T> getPagedResultsFromCurrentOffset(Optional<T> result, AbstractSearch currentSearch) {
    List<T> results = result.stream().toList();
    return getPagedResultsFromCurrentOffset(results, currentSearch);
  }

  public static <T> PagedResults<T> getPagedResultsFromCurrentOffset(List<T> results, AbstractSearch currentSearch) {
    String nextPageToken = null;
    if (currentSearch.pageSize != null) {
      Integer currentOffset = currentSearch.getPageOffset();
      if (currentOffset != null) {
        nextPageToken = (currentSearch.pageSize + currentOffset) + "";
      } else {
        nextPageToken = currentSearch.pageSize + "";
      }
    }

    return new PagedResults<>(results, currentSearch.pageSize, nextPageToken);
  }
}
