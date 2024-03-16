package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class ContactSearch extends AbstractSearch {
  public String email;
  public Boolean hasEmail;
  public String phone;
  public Boolean hasPhone;
  public String firstName;
  public String lastName;
  public Set<String> keywords = new HashSet<>();
  public String accountId;
  public String ownerId;
  public boolean basicSearch = false;

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

  public void addKeywords(String keywords) {
    if (Strings.isNullOrEmpty(keywords)) {
      return;
    }
    String[] keywordSplit = keywords.trim().split("\\s+");
    this.keywords.addAll(Arrays.stream(keywordSplit).collect(Collectors.toSet()));
  }
}
