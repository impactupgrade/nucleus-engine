/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

public class AccountSearch extends AbstractSearch {
  public String ownerId;
  public String keywords;
  public boolean basicSearch = false;

  // helpers

  public static AccountSearch byKeywords(String keywords) {
    AccountSearch accountSearch = new AccountSearch();
    accountSearch.keywords = keywords;
    return accountSearch;
  }
}
