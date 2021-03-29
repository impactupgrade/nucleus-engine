package com.impactupgrade.nucleus.model;


import com.impactupgrade.nucleus.environment.Environment;

public class ManageDonationEvent {
  protected final Environment.RequestEnvironment requestEnv;

  protected String donationId;
  protected Double amount;
  protected String subscriptionId;

  public ManageDonationEvent(Environment.RequestEnvironment requestEnv) {
    this.requestEnv = requestEnv;
  }

  // ACCESSORS

  public Environment.RequestEnvironment getRequestEnv() { return this.requestEnv; }

  public String getDonationId() { return this.donationId; }

  public void setDonationId(String donationId) { this.donationId = donationId; }

  public Double getAmount() { return this.amount; }

  public void setAmount(Double amount) { this.amount = amount; }

  public String getSubscriptionId() { return this.subscriptionId; }

  public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

  public String toString() {
    return "ManageDonationEvent {" +
        "\n donationId: " + this.donationId + "," +
        "\n amount: " + this.amount + "," +
        "\n subscriptionId: " + this.subscriptionId +
        "\n }";
  }
}
