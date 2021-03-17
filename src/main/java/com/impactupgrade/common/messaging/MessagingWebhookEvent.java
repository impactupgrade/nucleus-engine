package com.impactupgrade.common.messaging;

public class MessagingWebhookEvent {

  protected String phone;
  protected String firstName;
  protected String lastName;
  protected String email;
  protected String listId;

  // context set within processing steps OR pulled from event metadata
  protected String crmContactId;

  public String getPhone() {
    // Hubspot doesn't seem to support country codes when phone numbers are used to search. Strip it off.
    return phone.replace("+1", "");
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  public String getEmail() {
    // They'll send "no", etc. for email if they don't want to opt-in. Simply look for @, to be flexible.
    if (email != null && !email.contains("@")) {
      return null;
    }
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getListId() {
    return listId;
  }

  public void setListId(String listId) {
    this.listId = listId;
  }

  public String getCrmContactId() {
    return crmContactId;
  }

  public void setCrmContactId(String crmContactId) {
    this.crmContactId = crmContactId;
  }
}
