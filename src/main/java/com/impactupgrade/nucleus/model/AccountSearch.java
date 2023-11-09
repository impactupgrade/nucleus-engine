package com.impactupgrade.nucleus.model;

public class AccountSearch extends AbstractSearch {
  public String ownerId;
  public String keywords;
  public boolean basicSearch = false;

  // helpers

  public static AccountSearch byKeywords(String keywords) {
    AccountSearch contactSearch = new AccountSearch();
    contactSearch.keywords = keywords;
    return contactSearch;
  }
}
