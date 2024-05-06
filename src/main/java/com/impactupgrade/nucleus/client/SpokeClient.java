/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;

import javax.ws.rs.core.GenericType;
import java.util.ArrayList;
import java.util.List;

import static com.impactupgrade.nucleus.util.HttpClient.get;
import static com.impactupgrade.nucleus.util.HttpClient.put;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

// TODO: To eventually become a spoke-phone-java-client open source lib?
public class SpokeClient extends OAuthClient {

  protected static String AUTH_ENDPOINT = "https://auth.spokephone.com/oauth/token";
  protected static String API_ENDPOINT_BASE = "https://integration.spokephone.com/";

  public SpokeClient(Environment env) {
    super("spoke", env);
  }

  @Override
  protected OAuthContext oAuthContext() {
    return new ClientCredentialsOAuthContext(env.getConfig().spoke, AUTH_ENDPOINT, true);
  }

  public List<Phonebook> getPhonebooks() {
    return get(API_ENDPOINT_BASE + "phonebooks", headers(), new GenericType<>() {});
  }

  public Phonebook createPhonebook(String name, String description, String countryIso) {
    Phonebook phonebook = new Phonebook();
    phonebook.name = name;
    phonebook.description = description;
    phonebook.countryIso = countryIso;
    return put(API_ENDPOINT_BASE + "phonebooks", phonebook, APPLICATION_JSON, headers(), Phonebook.class);
  }

  public Contact upsertContact(CrmContact crmContact, String crmName, String phonebookId) {
    ContactRequest contactRequest = new ContactRequest();
    contactRequest.contact = toSpokeContact(crmContact, crmName);
    return put(API_ENDPOINT_BASE + "phonebooks/" + phonebookId + "/contacts", contactRequest, APPLICATION_JSON, headers(), Contact.class);
  }

  protected Contact toSpokeContact(CrmContact crmContact, String crmName) {
    Contact contact = new Contact();
    contact.id = crmContact.id;
    contact.firstName = crmContact.firstName;
    contact.lastName = crmContact.lastName;

    if (!Strings.isNullOrEmpty(crmContact.mobilePhone)) {
      ContactPhone phone = new ContactPhone();
      phone.label = "Mobile";
      phone.value = crmContact.mobilePhone;
      contact.phoneNumbers.add(phone);
    }
    if (!Strings.isNullOrEmpty(crmContact.homePhone)) {
      ContactPhone phone = new ContactPhone();
      phone.label = "Home";
      phone.value = crmContact.homePhone;
      contact.phoneNumbers.add(phone);
    }
    if (!Strings.isNullOrEmpty(crmContact.workPhone)) {
      ContactPhone phone = new ContactPhone();
      phone.label = "Work";
      phone.value = crmContact.workPhone;
      contact.phoneNumbers.add(phone);
    }

    // TODO: all the extra CRM context

    return contact;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Phonebook {
    public String id;
    public String name;
    public String description;
    public String countryIso;
    public List<Contact> contacts = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Contact {
    public String id;
    public String firstName;
    public String lastName;
    public List<ContactPhone> phoneNumbers = new ArrayList<>();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactPhone {
    public String label;
    public String value;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ContactRequest {
    public Contact contact;
    public String countryIso;
  }
}
