/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import java.util.Set;

public class ContactSearch extends AbstractSearch {
  public String email;
  public Boolean hasEmail;
  public String phone;
  public Boolean hasPhone;
  public String firstName;
  public String lastName;
  public String accountId;

  // helpers

  public static ContactSearch byEmail(String email) {
    ContactSearch contactSearch = new ContactSearch();
    contactSearch.email = email;
    return contactSearch;
  }

  public static ContactSearch byPhone(String phone) {
    ContactSearch contactSearch = new ContactSearch();
    contactSearch.phone = phone;
    return contactSearch;
  }

  public static ContactSearch byName(String firstName, String lastName) {
    ContactSearch contactSearch = new ContactSearch();
    contactSearch.firstName = firstName;
    contactSearch.lastName = lastName;
    return contactSearch;
  }

  public static ContactSearch byKeywords(String keywords) {
    ContactSearch contactSearch = new ContactSearch();
    String[] keywordSplit = keywords.trim().split("\\s+");
    contactSearch.keywords = Set.of(keywordSplit);
    return contactSearch;
  }
}
