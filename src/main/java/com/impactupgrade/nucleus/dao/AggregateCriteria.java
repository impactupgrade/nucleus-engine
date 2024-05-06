/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.dao;

import org.apache.commons.collections.CollectionUtils;

import java.util.List;
import java.util.stream.Collectors;

public class AggregateCriteria implements Criteria {

  public List<Criteria> criteriaList;
  public String operator;

  public AggregateCriteria(List<Criteria> criteriaList, String operator) {
    this.criteriaList = criteriaList;
    this.operator = operator;
  }

  public String toSqlString() {
    return CollectionUtils.isNotEmpty(criteriaList) ?
        "(" + criteriaList.stream().map(Criteria::toSqlString).collect(Collectors.joining(" " + operator + " ")) + ")"
        : "";
  }

}
