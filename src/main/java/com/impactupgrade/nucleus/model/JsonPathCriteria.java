package com.impactupgrade.nucleus.model;

public class JsonPathCriteria {

    public String jsonPath;
    public String operator;
    public Object value;

    public JsonPathCriteria(String jsonPath, String operator, Object value) {
        this.jsonPath = jsonPath;
        this.operator = operator;
        this.value = value;
    }

}
