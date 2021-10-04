/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;

import java.util.Calendar;
import java.util.Map;
import java.util.stream.Collectors;

public record PaymentGatewayTransaction (
    Calendar date,
    double gross,
    double net,
    // even though fees = gross - net, keep this split off in case we need to do something different for refunds
    // (ie, gross will be zeroed out, but fees are kept).
    double fees,
    String name,
    String email,
    String phone,
    String address,
    String source,
    String url,
    Map<String, String> rawMetadata
) {

  @Override
  public String email() {
    if (Strings.isNullOrEmpty(email)) {
      return "no email";
    }
    return email;
  }

  @Override
  public String phone() {
    if (Strings.isNullOrEmpty(phone)) {
      return "no phone";
    }
    return phone;
  }

  @Override
  public String address() {
    if (Strings.isNullOrEmpty(address)) {
      return "no address";
    }
    return address;
  }

  public String rawMetadataString() {
    if (rawMetadata == null) {
      return "no metadata";
    }

    return rawMetadata.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(" "));
  }
}
