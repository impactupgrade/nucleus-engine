package com.impactupgrade.nucleus.entity;

public enum PaymentGateway {
  STRIPE("Stripe");

  private final String name;

  PaymentGateway(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
