/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import java.util.Set;

public class AccountSearch extends AbstractSearch {

  // helpers

  public static AccountSearch byEmail(String email) {
    AccountSearch accountSearch = new AccountSearch();
    accountSearch.email = email;
    return accountSearch;
  }

  public static AccountSearch byKeywords(String keywords) {
    AccountSearch accountSearch = new AccountSearch();
    String[] keywordSplit = keywords.trim().split("\\s+");
    accountSearch.keywords = Set.of(keywordSplit);
    return accountSearch;
  }
}
