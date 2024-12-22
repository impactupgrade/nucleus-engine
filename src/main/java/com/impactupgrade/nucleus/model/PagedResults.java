/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class PagedResults<T> {
  private final List<ResultSet<T>> resultSets = new ArrayList<>();

  public PagedResults() {}

  public PagedResults(ResultSet<T> resultSet) {
    resultSets.add(resultSet);
  }

  public void addResultSet(ResultSet<T> resultSet) {
    resultSets.add(resultSet);
  }

  public List<ResultSet<T>> getResultSets() {
    return resultSets;
  }

  public List<T> getResults() {
    return resultSets.stream().flatMap(rs -> rs.getRecords().stream()).toList();
  }

  public Optional<T> getSingleResult() {
    return resultSets.stream().flatMap(rs -> rs.getRecords().stream()).findFirst();
  }

  public static class ResultSet<T> {
    private final List<T> records;
    // Could be a numeric offset or string cursor -- each usage must know which to expect
    private final String nextPageToken;

    public ResultSet(List<T> records, String nextPageToken) {
      this.records = records;
      this.nextPageToken = nextPageToken;
    }

    public ResultSet() {
      records = new ArrayList<>();
      nextPageToken = null;
    }

    public List<T> getRecords() {
      return records;
    }

    public Optional<T> getSingleResult() {
      if (records.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(records.get(0));
    }

    public String getNextPageToken() {
      return nextPageToken;
    }

    public static <T> ResultSet<T> resultSetFromCurrentOffset(List<T> results, String nextPageToken) {
      return new ResultSet<>(results, nextPageToken);
    }
  }

  public static <T> PagedResults<T> pagedResultsFromCurrentOffset(T result, AbstractSearch currentSearch) {
    List<T> results = result == null ? Collections.emptyList() : List.of(result);
    return pagedResultsFromCurrentOffset(results, currentSearch);
  }

  public static <T> PagedResults<T> pagedResultsFromCurrentOffset(Optional<T> result, AbstractSearch currentSearch) {
    List<T> results = result.stream().toList();
    return pagedResultsFromCurrentOffset(results, currentSearch);
  }

  public static <T> PagedResults<T> pagedResultsFromCurrentOffset(List<T> results, AbstractSearch currentSearch) {
    String nextPageToken = null;
    if (currentSearch.pageSize != null) {
      Integer currentOffset = currentSearch.getPageOffset();
      if (currentOffset != null) {
        nextPageToken = (currentSearch.pageSize + currentOffset) + "";
      } else {
        nextPageToken = currentSearch.pageSize + "";
      }
    }

    ResultSet<T> resultSet = new ResultSet<>(results, nextPageToken);
    PagedResults<T> pagedResults = new PagedResults<>();
    pagedResults.addResultSet(resultSet);
    return pagedResults;
  }

  public static <T> PagedResults<T> unpagedResults(List<T> results) {
    ResultSet<T> resultSet = new ResultSet<>(results, null);
    PagedResults<T> pagedResults = new PagedResults<>();
    pagedResults.addResultSet(resultSet);
    return pagedResults;
  }
}