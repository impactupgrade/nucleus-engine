package com.impactupgrade.nucleus.filter;

import com.impactupgrade.nucleus.environment.EnvironmentConfig;

import java.util.List;
import java.util.Objects;

public interface ObjectFilter<T> {

    String getFieldValue(T t, String fieldName);

    boolean filter(T t);

    default boolean filter(T t, List<EnvironmentConfig.Expression> expressions) {
        return expressions.stream()
                .allMatch(expression -> evaluate(t, expression));
    }

    default boolean evaluate(T t, EnvironmentConfig.Expression expression) {
        String fieldValue = getFieldValue(t, expression.key);
        return switch (expression.operator) {
            case "==" -> Objects.equals(fieldValue, expression.value);
            case "!=" -> !Objects.equals(fieldValue, expression.value);
            default -> false;
        };
    }

}
