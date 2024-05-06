/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.filter;

import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.stripe.model.StripeObject;
import org.json.JSONObject;

import java.util.List;

public class StripeObjectFilter implements ObjectFilter<StripeObject> {

    private final List<EnvironmentConfig.Expression> expressions;

    public StripeObjectFilter(List<EnvironmentConfig.Expression> expressions) {
        this.expressions = expressions;
    }

    @Override
    public String getFieldValue(StripeObject stripeObject, String fieldName) {
        return getJsonObject(new JSONObject(stripeObject), fieldName);
    }

    @Override
    public boolean filter(StripeObject stripeObject) {
        return filter(stripeObject, expressions);
    }

    private String getJsonObject(JSONObject jsonObject, String path) {
        if (path.contains(".")) {
            String key = path.substring(0, path.indexOf("."));
            jsonObject = jsonObject.getJSONObject(key);
            String subPath = path.substring(path.indexOf(".") + 1);
            return getJsonObject(jsonObject, subPath);
        }
        if (jsonObject.has(path)) {
            return jsonObject.get(path).toString();
        } else {
            return null;
        }
    }

}
