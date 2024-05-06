/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;
import org.apache.commons.collections4.map.CaseInsensitiveMap;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CrmCustomField {

    public String objectName;
    public String layoutName;
    public String groupName;

    public String name;
    public String label;
    public String type; // force inputs to provide the vendor-specific type -- too many options to wrap with an enum
    public String subtype;
    public Integer length;
    public Integer precision;
    public Integer scale;
    public List<String> values;

    public static List<CrmCustomField> fromGeneric(List<Map<String, String>> data) {
        return data.stream().map(CrmCustomField::fromGeneric).collect(Collectors.toList());
    }

    public static CrmCustomField fromGeneric(Map<String, String> _data) {
        // Be case-insensitive, for sources that aren't always consistent.
        CaseInsensitiveMap<String, String> data = new CaseInsensitiveMap<>(_data);

        CrmCustomField crmCustomField = new CrmCustomField();
        crmCustomField.objectName = data.get("Object");
        crmCustomField.layoutName = data.get("Layout");
        crmCustomField.groupName = data.get("Group");

        crmCustomField.label = data.get("Label");
        crmCustomField.name = data.get("Name");
        if (Strings.isNullOrEmpty(crmCustomField.name)) {
            crmCustomField.name = data.get("Label");
        }
        crmCustomField.type = data.get("Type");
        crmCustomField.subtype = data.get("Subtype");
        crmCustomField.length = Strings.isNullOrEmpty(data.get("Length")) ? null : Integer.parseInt(data.get("Length"));
        crmCustomField.precision = Strings.isNullOrEmpty(data.get("Precision")) ? null : Integer.parseInt(data.get("Precision"));
        crmCustomField.scale = Strings.isNullOrEmpty(data.get("Scale")) ? null : Integer.parseInt(data.get("Scale"));
        crmCustomField.values = Strings.isNullOrEmpty(data.get("Values")) ? null : Arrays.asList(data.get("Values").split(";"));

        return crmCustomField;
    }
}
