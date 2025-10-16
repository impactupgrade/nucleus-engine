/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.filter;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public interface ObjectFilter<T> {

    String getFieldValue(T t, String fieldName);

    boolean filter(T t);

    default boolean filter(T t, List<EnvironmentConfig.Expression> expressions) {
        if (expressions.isEmpty()) {
            return false;
        }

        return expressions.stream()
                .allMatch(expression -> evaluate(t, expression));
    }

    default boolean evaluate(T t, EnvironmentConfig.Expression expression) {
        String fieldValue = getFieldValue(t, expression.key);
        return switch (expression.operator) {
          case "==" -> expression.value.equalsIgnoreCase(fieldValue);
          case "!=" -> !expression.value.equalsIgnoreCase(fieldValue);
          case "=~" -> !Strings.isNullOrEmpty(fieldValue) && fieldValue.toLowerCase(Locale.ROOT).contains(expression.value.toLowerCase(Locale.ROOT));
          case "!~" -> Strings.isNullOrEmpty(fieldValue) || !fieldValue.toLowerCase(Locale.ROOT).contains(expression.value.toLowerCase(Locale.ROOT));
          default -> false;
        };
    }

}
