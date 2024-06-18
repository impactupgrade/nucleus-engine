/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ContactSearch extends AbstractSearch {
  public Set<String> accountIds = new HashSet<>();
  public Set<String> emails = new HashSet<>();
  public List<String[]> firstAndLastNames = new ArrayList<>();
  public Set<String> ownerIds = new HashSet<>();
  public Set<String> phones = new HashSet<>();
}
