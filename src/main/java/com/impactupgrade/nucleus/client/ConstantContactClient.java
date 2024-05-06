/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.util.HttpClient;
import org.apache.commons.collections.CollectionUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

public class ConstantContactClient extends OAuthClient {

  private static final String API_BASE_URL = "https://api.cc.email/v3";
  private static final String AUTH_URL = "https://authz.constantcontact.com/oauth2/default/v1/token";

  protected final EnvironmentConfig.CommunicationPlatform communicationPlatform;

  public ConstantContactClient(EnvironmentConfig.CommunicationPlatform communicationPlatform, Environment env) {
    super("constantContact", env);
    this.communicationPlatform = communicationPlatform;
  }

  @Override
  protected JSONObject getClientConfigJson(JSONObject envJson) {
    JSONArray constantContactArray = envJson.getJSONArray(name);
    return constantContactArray.getJSONObject(0); // which one to use?
  }

  @Override
  protected OAuthContext oAuthContext() {
    String auth = communicationPlatform.clientId + ":" + communicationPlatform.clientSecret;
    String encodedAuth = Base64.getEncoder().encodeToString((auth).getBytes(StandardCharsets.UTF_8));

    OAuthContext oAuthContext = new ClientCredentialsOAuthContext(communicationPlatform, AUTH_URL, true);
    oAuthContext.refreshTokensAdditionalHeaders = Map.of("Authorization", "Basic " + encodedAuth);
    return oAuthContext;
  }

  // Creates a new contact or updates an existing contact (email_address to determine create/update)
  // Only updates the contact properties included in the request body
  // Updates append new contact lists or custom fields to the existing list_memberships or custom_fields arrays
  public Contact upsertContact(CrmContact crmContact, String listId) {
    Contact contact = toContact(crmContact, listId);
    HttpClient.post(API_BASE_URL + "/contacts/sign_up_form", contact, APPLICATION_JSON, headers(), String.class);
    return contact;
  }

  // Adding existing contacts (requires contact ids) to lists (Async)
  public List<Contact> addContactIdsToList(List<CrmContact> crmContacts, String listId) {
    List<Contact> contacts = crmContacts.stream()
        .map(crmContact -> toContact(crmContact, null))
        .collect(Collectors.toList());
    AddContactsToListRequest addContactsToListRequest = toRequest(contacts, List.of(listId));
    HttpClient.post(API_BASE_URL + "/activities/add_list_memberships", addContactsToListRequest, APPLICATION_JSON, headers(), String.class);
    return contacts;
  }

  protected HttpClient.HeaderBuilder headers() {
    if (oAuthContext == null) {
      oAuthContext = oAuthContext();
    }
    return HttpClient.HeaderBuilder.builder().authBearerToken(oAuthContext.accessToken());
  }

  private Contact toContact(CrmContact crmContact, String listId) {
    if (crmContact == null) {
      return null;
    }
    Contact contact = new Contact();
    contact.firstname = crmContact.firstName;
    contact.lastname = crmContact.lastName;
    contact.emailAddress = crmContact.email;
    contact.phoneNumber = crmContact.phoneNumberForSMS();
    contact.streetAddress = toAddress(crmContact.mailingAddress);

    if (crmContact.account != null) {
      contact.companyName = crmContact.account.name;
    }

    contact.listMemberships = Collections.emptyList();
    return contact;
  }

  private Address toAddress(CrmAddress crmAddress) {
    if (crmAddress == null) {
      return null;
    }
    Address address = new Address();
    address.street = crmAddress.street;
    address.city = crmAddress.city;
    address.state = crmAddress.state;
    address.postalCode = crmAddress.postalCode;
    address.country = crmAddress.country;
    return address;
  }

  private AddContactsToListRequest toRequest(List<Contact> contacts, List<String> listIds) {
    AddContactsToListRequest request = new AddContactsToListRequest();

    if (CollectionUtils.isNotEmpty(contacts)) {
      ContactIds contactIds = new ContactIds();
      contactIds.contactIds = contacts.stream()
          .map(contact -> contact.id)
          .collect(Collectors.toList());
      request.source = contactIds;
    }

    request.listIds = listIds;
    return request;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static final class Contact {
    @JsonProperty("contact_id")
    public String id;
    @JsonProperty("first_name")
    public String firstname;
    @JsonProperty("last_name")
    public String lastname;
    @JsonProperty("email_address")
    public String emailAddress;
    @JsonProperty("company_name")
    public String companyName;
    @JsonProperty("phone_number")
    public String phoneNumber;
    @JsonProperty("street_address")
    public Address streetAddress;
    @JsonProperty("list_memberships")
    public List<String> listMemberships;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static final class Address {
    @JsonProperty("kind")
    public String kind; // home, work or other
    public String street;
    public String city;
    public String state;
    @JsonProperty("postal_code")
    public String postalCode;
    public String country;
  }

  private static final class AddContactsToListRequest {
    public Source source; // one of: list_ids, all_active_contacts, contact_ids, segment_id
    @JsonProperty("list_ids")
    public List<String> listIds;
  }

  private static class Source {}

  private static class ContactIds extends Source {
    @JsonProperty("contact_ids")
    public List<String> contactIds;
  }
}
