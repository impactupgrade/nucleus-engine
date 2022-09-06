package com.impactupgrade.nucleus.model;

public class CrmCustomField {

    public String objectName;
    public String groupName;

    public String name;
    public String label;
    public Type type; // TODO: enum?
    public int length;
    public int precision;
    public int scale;

    public CrmCustomField(String objectName, String name, String label, Type type, int length) {
        this.objectName = objectName;
        this.name = name;
        this.label = label;
        this.type = type;
        this.length = length;
    }

    public CrmCustomField(String objectName, String name, String label, Type type, int precision, int scale) {
        this.objectName = objectName;
        this.name = name;
        this.label = label;
        this.type = type;
        this.precision = precision;
        this.scale = scale;
    }

    public enum Type {
        TEXT,
        NUMBER,
        CURRENCY,
        DATE    // TODO: decide which types we need
    }

}
