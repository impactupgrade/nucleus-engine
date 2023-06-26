package com.impactupgrade.nucleus.model;

public class ContactSearch extends AbstractSearch {
  public String email;
  public Boolean hasEmail;
  public String phone;
  public Boolean hasPhone;
  public String firstName;
  public String lastName;
  public String name;
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

  public static ContactSearch byName(String firstName, String lastName) {
    ContactSearch contactSearch = new ContactSearch();
    contactSearch.firstName = firstName;
    contactSearch.lastName = lastName;
    return contactSearch;
  }

  public static ContactSearch byName(String name) {
    ContactSearch contactSearch = new ContactSearch();
    contactSearch.name = name;
    return contactSearch;
  }

  public static ContactSearch byAccountId(String accountId) {
    ContactSearch contactSearch = new ContactSearch();
    contactSearch.accountId = accountId;
    return contactSearch;
  }
}
