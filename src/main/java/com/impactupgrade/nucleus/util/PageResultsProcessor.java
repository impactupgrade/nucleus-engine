package com.impactupgrade.nucleus.util;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.model.PagedResults;

import java.util.function.Consumer;

public class PageResultsProcessor<T> {

  private final CheckedFunction<String, PagedResults.ResultSet<T>> getter;
  private final Consumer<PagedResults.ResultSet<T>> consumer;

  public PageResultsProcessor(CheckedFunction<String, PagedResults.ResultSet<T>> getter,
                              Consumer<PagedResults.ResultSet<T>> consumer) {
    this.getter = getter;
    this.consumer = consumer;
  }

  public void process(PagedResults.ResultSet<T> resultSet) throws Exception {
    consumer.accept(resultSet);
    if (!Strings.isNullOrEmpty(resultSet.getNextPageToken())) {
      PagedResults.ResultSet<T> nextResultSet = getter.apply(resultSet.getNextPageToken());
      process(nextResultSet);
    }
  }

  @FunctionalInterface
  public interface CheckedFunction<T, R> {
    R apply(T t) throws Exception;
  }
}
