package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.DynamicsCrmClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmAddress;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.PagedResults;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class DynamicsCrmService implements BasicCrmService {

  private DynamicsCrmClient dynamicsCrmClient;
  protected Environment env;

  @Override
  public String name() {
    return "dynamics-crm";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return !Strings.isNullOrEmpty(env.getConfig().dynamicsPlatform.secretKey);
  }

  @Override
  public void init(Environment env) {
    this.dynamicsCrmClient = new DynamicsCrmClient(env);
  }

  // Contact
  @Override
  public Optional<CrmContact> getContactById(String id) throws Exception {
    DynamicsCrmClient.Contact contact = dynamicsCrmClient.getContactById(id);
    return Optional.ofNullable(toCrmContact(contact));
  }

  @Override
  public Optional<CrmContact> getFilteredContactById(String id, String filter) throws Exception {
    return getContactById(id); //TODO: filter?
  }

  @Override
  public Optional<CrmContact> getFilteredContactByEmail(String email, String filter) throws Exception {
    DynamicsCrmClient.Contact contact = dynamicsCrmClient.getContactByEmail(email); //TODO: filter?
    return Optional.ofNullable(toCrmContact(contact));
  }

  @Override
  public PagedResults<CrmContact> searchContacts(ContactSearch contactSearch) throws Exception {
    // TODO: by name and by keywords
    DynamicsCrmClient.Contact contact;
    if (!Strings.isNullOrEmpty(contactSearch.email)) {
      contact = dynamicsCrmClient.getContactByEmail(contactSearch.email);
      return PagedResults.getPagedResultsFromCurrentOffset(List.of(toCrmContact(contact)), contactSearch);
    } else if (!Strings.isNullOrEmpty(contactSearch.phone)) {
      contact = dynamicsCrmClient.getContactByPhoneNumber(contactSearch.phone);
      return PagedResults.getPagedResultsFromCurrentOffset(List.of(toCrmContact(contact)), contactSearch);
    } else {
      return new PagedResults<>();
    }
  }

  @Override
  public String insertContact(CrmContact crmContact) throws Exception {
    DynamicsCrmClient.Contact insertedContact = dynamicsCrmClient.insertContact(toContact(crmContact));
    return insertedContact.id;
  }

  @Override
  public void updateContact(CrmContact crmContact) throws Exception {
    dynamicsCrmClient.updateContact(toContact(crmContact));
  }

  @Override
  public List<CrmDonation> getDonationsByTransactionIds(List<String> transactionIds) throws Exception {
    if (Strings.isNullOrEmpty(env.getConfig().dynamicsPlatform.fieldDefinitions.paymentGatewayTransactionId)) {
      return Collections.emptyList();
    }
    List<DynamicsCrmClient.Opportunity> opportunities = dynamicsCrmClient.getOpportunities(transactionIds);
    return opportunities.stream()
        .map(this::toCrmDonation)
        .collect(Collectors.toList());
  }

  @Override
  public String insertDonation(CrmDonation crmDonation) throws Exception {
    DynamicsCrmClient.Opportunity insertedOpportunity = dynamicsCrmClient.insertOpportunity(toOpportunity(crmDonation));
    return insertedOpportunity.id;
  }

  @Override
  public void updateDonation(CrmDonation crmDonation) throws Exception {
    dynamicsCrmClient.updateOpportunity(toOpportunity(crmDonation));
  }

  @Override
  public void refundDonation(CrmDonation crmDonation) throws Exception {
    //TODO: howto?
  }

  private CrmDonation toCrmDonation(DynamicsCrmClient.Opportunity opportunity) {
    if (opportunity == null) {
      return null;
    }
    CrmDonation crmDonation = new CrmDonation();
    crmDonation.id = opportunity.id;
    crmDonation.name = opportunity.name;
    crmDonation.description = opportunity.description;
    crmDonation.amount = opportunity.totalAmount;
    //TODO: set from custom fields
    //crmDonation.transactionId = opportunity.?
    //crmDonation.customerId = opportunity.?

    crmDonation.contact = new CrmContact();
    crmDonation.contact.id = opportunity.contactId;

    crmDonation.account = new CrmAccount();
    crmDonation.account.id = opportunity.accountId;

    //TODO: rest of the mappings
    return crmDonation;
  }

  private static DynamicsCrmClient.Opportunity toOpportunity(CrmDonation crmDonation) {
    DynamicsCrmClient.Opportunity opportunity = new DynamicsCrmClient.Opportunity();
    opportunity.id = crmDonation.id;
    opportunity.name = crmDonation.name;
    opportunity.description = crmDonation.description;
    opportunity.totalAmount = crmDonation.amount;

    // TODO: address the following issue to assign opp to contact
    // CRM do not support direct update of Entity Reference properties, Use Navigation properties instead.
    // Looks like opp should be created first and then should be updated (patched) to get assigned to contact
    opportunity.contactId = crmDonation.contact != null ? crmDonation.contact.id : null;
    opportunity.accountId = crmDonation.account != null ? crmDonation.account.id : null;

    //TODO: rest of the mappings
    //TODO: set custom fields! (should set fields according to custom fields' logical names)
    return opportunity;
  }

  @Override
  public List<CrmContact> getEmailContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return null;
  }

  @Override
  public List<CrmContact> getSmsContacts(Calendar updatedSince, EnvironmentConfig.CommunicationList communicationList) throws Exception {
    return null;
  }

  @Override
  public List<CrmUser> getUsers() throws Exception {
    return null;
  }

  @Override
  public double getDonationsTotal(String filter) throws Exception {
    return 0;
  }

  @Override
  public Map<String, String> getContactLists() throws Exception {
    return null;
  }

  @Override
  public Map<String, String> getFieldOptions(String object) throws Exception {
    return null;
  }

  @Override
  public EnvironmentConfig.CRMFieldDefinitions getFieldDefinitions() {
    return null;
  }

  // Account
  @Override
  public Optional<CrmAccount> getAccountById(String id) {
    DynamicsCrmClient.Account account = dynamicsCrmClient.getAccountById(id);
    return Optional.ofNullable(toCrmAccount(account));
  }

  @Override
  public List<CrmAccount> getAccountsByIds(List<String> ids) throws Exception {
    List<DynamicsCrmClient.Account> accounts = dynamicsCrmClient.getAccountsByIds(ids);
    return accounts.stream()
        .map(account -> toCrmAccount(account))
        .collect(Collectors.toList());
  }

  //TODO: convert crmAccount to Account
  @Override
  public String insertAccount(CrmAccount crmAccount) throws Exception {
    DynamicsCrmClient.Account insertedAccount = dynamicsCrmClient.insertAccount(new DynamicsCrmClient.Account());
    return insertedAccount.accountId;
  }

  // Utils
  private static CrmContact toCrmContact(DynamicsCrmClient.Contact contact) {
    if (contact == null) {
      return null;
    }
    CrmContact crmContact = new CrmContact();
    crmContact.id = contact.id.toString();
    crmContact.email = contact.emailaddress1;
    crmContact.firstName = contact.firstname;
    crmContact.lastName = contact.lastname;
    crmContact.mobilePhone = contact.mobilePhone;
    crmContact.homePhone = contact.telephone1; // ?
    //crmContact.workPhone = contact.telephone1; // ?
    crmContact.mailingAddress = toCrmAddress(contact);
    if (contact.ownerId != null) {
      crmContact.ownerId = contact.ownerId.toString();
    }
    return crmContact;
  }

  private static DynamicsCrmClient.Contact toContact(CrmContact crmContact) {
    if (crmContact == null) {
      return null;
    }
    DynamicsCrmClient.Contact contact = new DynamicsCrmClient.Contact();
    contact.id = crmContact.id;
    contact.emailaddress1 = crmContact.email;
    contact.firstname = crmContact.firstName;
    contact.lastname = crmContact.lastName;
    contact.mobilePhone = crmContact.mobilePhone;
    contact.telephone1 = crmContact.homePhone;

    if (crmContact.mailingAddress != null) {
      contact.address1Street = crmContact.mailingAddress.street;
      contact.address1Postalcode = crmContact.mailingAddress.postalCode;
      contact.address1City = crmContact.mailingAddress.city;
      contact.address1StateOrProvince = crmContact.mailingAddress.state;
      contact.address1Country = crmContact.mailingAddress.country;
    }
    contact.ownerId = crmContact.ownerId;

    return contact;
  }

  private static CrmAddress toCrmAddress(DynamicsCrmClient.Contact contact) {
    if (contact == null) {
      return null;
    }
    CrmAddress crmAddress = new CrmAddress();
    crmAddress.street = contact.address1Street; // ?
    crmAddress.postalCode = contact.address1Postalcode;
    crmAddress.city = contact.address1City;
    crmAddress.state = contact.address1StateOrProvince;
    crmAddress.country = contact.address1Country;
    return crmAddress;
  }

  private static CrmAccount toCrmAccount(DynamicsCrmClient.Account account) {
    if (account == null) {
      return null;
    }
    CrmAccount crmAccount = new CrmAccount();
    crmAccount.description = account.description;
    crmAccount.name = account.name;
    crmAccount.ownerId = account.ownerId;
    crmAccount.phone = account.telephone1;
    crmAccount.website = account.websiteUrl;

    crmAccount.billingAddress = getBillingAddress(account);
    crmAccount.mailingAddress = getMailingAddress(account);

    return crmAccount;
  }

  private static CrmAddress getBillingAddress(DynamicsCrmClient.Account account) {
    if (account == null) {
      return null;
    }
    CrmAddress crmAddress = new CrmAddress();
    // Assuming address 1 is billing address
    crmAddress.street = account.address1Street;
    crmAddress.postalCode = account.address1Postalcode;
    crmAddress.city = account.address1City;
    crmAddress.state = account.address1StateOrProvince;
    crmAddress.country = account.address1Country;

    if (account.address2Type == 1) {
      // If address 2 has type 2 (Bill To) - override
      crmAddress.street = account.address2Street;
      crmAddress.postalCode = account.address2Postalcode;
      crmAddress.city = account.address2City;
      crmAddress.state = account.address2StateOrProvince;
      crmAddress.country = account.address2Country;
    }

    return crmAddress;
  }

  private static CrmAddress getMailingAddress(DynamicsCrmClient.Account account) {
    if (account == null) {
      return null;
    }
    CrmAddress crmAddress = new CrmAddress();
    // Assuming address 1 is mailing address
    crmAddress.street = account.address1Street;
    crmAddress.postalCode = account.address1Postalcode;
    crmAddress.city = account.address1City;
    crmAddress.state = account.address1StateOrProvince;
    crmAddress.country = account.address1Country;

    if (account.address2Type == 2) {
      // If address 2 has type 2 (Ship To) - override
      crmAddress.street = account.address2Street;
      crmAddress.postalCode = account.address2Postalcode;
      crmAddress.city = account.address2City;
      crmAddress.state = account.address2StateOrProvince;
      crmAddress.country = account.address2Country;
    }

    return crmAddress;
  }
}
