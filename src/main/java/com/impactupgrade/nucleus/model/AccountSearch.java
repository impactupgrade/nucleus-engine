/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import java.util.ArrayList;
import java.util.List;

public class AccountSearch extends AbstractSearch {
  public List<String> emails = new ArrayList<>();
  public List<String> ownerIds = new ArrayList<>();
}
