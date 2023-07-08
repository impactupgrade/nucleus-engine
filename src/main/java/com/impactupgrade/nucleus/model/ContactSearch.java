package com.impactupgrade.nucleus.model;

public class ContactSearch extends AbstractSearch {
  public String email;
  public Boolean hasEmail;
  public String phone;
  public Boolean hasPhone;
  public String accountId;
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

  public static ContactSearch byKeywords(String keywords) {
    ContactSearch contactSearch = new ContactSearch();
    contactSearch.keywords = keywords;
    return contactSearch;
  }
}
