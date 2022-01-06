package com.impactupgrade.nucleus.model;

import org.apache.commons.collections.CollectionUtils;

import java.util.List;
import java.util.stream.Collectors;

public class Criteria {

    public List<Criteria> criterias;
    public String operator;

    public Criteria() {}

    public Criteria(List<Criteria> criterias, String operator) {
        this.criterias = criterias;
        this.operator = operator;
    }

    public String toSqlString() {
        return CollectionUtils.isNotEmpty(criterias) ?
                "(" + criterias.stream().map(Criteria::toSqlString).collect(Collectors.joining(" " + operator + " ")) + ")" : "";
    }

}
