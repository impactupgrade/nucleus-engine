/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

public record CrmDonation(String id, String name, Double amount, String paymentGatewayName, Status status) {

  public enum Status {
    PENDING, SUCCESSFUL, FAILED
  }
}
