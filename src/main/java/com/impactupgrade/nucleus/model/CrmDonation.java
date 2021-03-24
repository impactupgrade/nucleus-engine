package com.impactupgrade.nucleus.model;

public class CrmDonation {

  private String id;
  private boolean successful;

  public CrmDonation(String id, boolean successful) {
    this.id = id;
    this.successful = successful;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public boolean isSuccessful() {
    return successful;
  }

  public void setSuccessful(boolean successful) {
    this.successful = successful;
  }
}
