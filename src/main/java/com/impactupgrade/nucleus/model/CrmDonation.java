/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

public class CrmDonation {

  private String id;
  private boolean posted;

  public CrmDonation(String id, boolean posted) {
    this.id = id;
    this.posted = posted;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public boolean isPosted() {
    return posted;
  }

  public void setPosted(boolean posted) {
    this.posted = posted;
  }
}
