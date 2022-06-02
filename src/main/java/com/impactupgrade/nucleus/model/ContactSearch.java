package com.impactupgrade.nucleus.model;

public class ContactSearch extends AbstractSearch {
  public String email;
  public String phone;
  public String ownerId;
  public String keywords;

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
}
