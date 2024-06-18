/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import java.util.HashSet;
import java.util.Set;

public class AccountSearch extends AbstractSearch {
  public Set<String> emails = new HashSet<>();
  public Set<String> ownerIds = new HashSet<>();
}
