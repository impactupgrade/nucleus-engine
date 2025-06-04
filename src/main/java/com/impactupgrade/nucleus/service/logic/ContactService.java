/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.ContactFormData;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.service.segment.CrmService;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class ContactService {

  protected final Environment env;
  protected final CrmService crmService;

  public ContactService(Environment env) {
    this.env = env;
    crmService = env.donationsCrmService();
  }

  public void processDonor(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    processDonor(paymentGatewayEvent, true);
  }

  public void processDonor(PaymentGatewayEvent paymentGatewayEvent, boolean createIfMissing) throws Exception {
    fetchAndSetDonorData(paymentGatewayEvent);
    if (createIfMissing
        && Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().id)
        && Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().id)) {
      createDonor(paymentGatewayEvent);
    }
  }

  protected void fetchAndSetDonorData(PaymentGatewayEvent paymentGatewayEvent) throws Exception  {
    Optional<CrmAccount> existingAccount = Optional.empty();
    Optional<CrmContact> existingContact = Optional.empty();

    if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().id)) {
      existingAccount = crmService.getAccountById(paymentGatewayEvent.getCrmAccount().id);
      if (existingAccount.isPresent()) {
        env.logJobInfo("found CRM account {}", existingAccount.get().id);
      } else {
        env.logJobInfo("event included CRM account {}, but the account didn't exist; trying through the contact...",
            paymentGatewayEvent.getCrmAccount().id);
      }
    }

    if (!Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().id)) {
      existingContact = crmService.getContactById(paymentGatewayEvent.getCrmContact().id);
      if (existingContact.isPresent()) {
        env.logJobInfo("found CRM contact {}", existingContact.get().id);

        if (existingAccount.isEmpty() && !Strings.isNullOrEmpty(existingContact.get().account.id)) {
          existingAccount = crmService.getAccountById(existingContact.get().account.id);
          if (existingAccount.isPresent()) {
            env.logJobInfo("found CRM account {}", existingContact.get().account.id);
          }
        }
      } else {
        env.logJobInfo("event included CRM contact {}, but the contact didn't exist; trying to find the contact by other means...",
            paymentGatewayEvent.getCrmContact().id);
        // IMPORTANT: If this was the case, clear out the existingAccount and use the one discovered by the proceeding contact search!
        existingAccount = Optional.empty();
      }
    }

    // IMPORTANT: Skip this step if an existingAccount was found! A Stripe customer has sf_account defined but not
    // sf_contact, the email address might still be a match here. We assume that sf_account without the presence of
    // sf_contact is a business gift!
    if (existingAccount.isEmpty() && existingContact.isEmpty()) {
      existingContact = findExistingContacts(paymentGatewayEvent.getCrmContact()).stream().findFirst();

      // As a last resort, attempt to look up existing donations using the donor's customer or subscription. If
      // donations are found, retrieve the contact/account from the latest. This prevents duplicate contacts
      // when donations come in with nothing more than a first/last name.
      if (existingContact.isEmpty() && !Strings.isNullOrEmpty(paymentGatewayEvent.getCrmRecurringDonation().subscriptionId)) {
        Optional<CrmRecurringDonation> crmRecurringDonation = crmService.getRecurringDonationBySubscriptionId(
            paymentGatewayEvent.getCrmRecurringDonation().subscriptionId);
        // Recurring Donations are typically assigned to an Account OR a Contact (and Enhanced Recurring Donations
        // requires one or the other, but disallows both). Typically, for a household gift, you'd see the Contact used,
        // but some orgs instead use the Account.
        if (crmRecurringDonation.isPresent()) {
          if (!Strings.isNullOrEmpty(crmRecurringDonation.get().contact.id)) {
            existingContact = crmService.getContactById(crmRecurringDonation.get().contact.id);
          } else if (!Strings.isNullOrEmpty(crmRecurringDonation.get().account.id)) {
            existingAccount = crmService.getAccountById(crmRecurringDonation.get().account.id);
          }
        }
      }

      if (existingContact.isEmpty() && !Strings.isNullOrEmpty(paymentGatewayEvent.getCrmDonation().customerId)) {
        List<CrmDonation> crmDonations = crmService.getDonationsByCustomerId(
            paymentGatewayEvent.getCrmDonation().customerId);
        if (!crmDonations.isEmpty()) {
          existingContact = crmService.getContactById(crmDonations.get(0).contact.id);
        }
      }

      if (existingContact.isPresent() && !Strings.isNullOrEmpty(existingContact.get().account.id)) {
        existingAccount = crmService.getAccountById(existingContact.get().account.id);
        if (existingAccount.isPresent()) {
          env.logJobInfo("found CRM account {}", existingContact.get().account.id);
        }
      }
    }

    if (existingAccount.isPresent() || existingContact.isPresent()){
      backfillMissingData(paymentGatewayEvent, existingAccount, existingContact);

      existingAccount.ifPresent(a -> paymentGatewayEvent.setCrmAccountId(a.id));
      existingContact.ifPresent(c -> paymentGatewayEvent.setCrmContactId(c.id));
    }
  }

  public List<CrmContact> findExistingContacts(CrmContact crmContact) throws Exception {
    List<CrmContact> existingContacts = List.of();

    if (!Strings.isNullOrEmpty(crmContact.id)) {
      existingContacts = crmService.getContactById(crmContact.id).map(List::of).orElse(List.of());
    }
    if (existingContacts.isEmpty() && !Strings.isNullOrEmpty(crmContact.email)) {
      existingContacts = crmService.searchContacts(ContactSearch.byEmail(crmContact.email))
          .getResultsFromAllFirstPages();
    }
    if (existingContacts.isEmpty() && !Strings.isNullOrEmpty(crmContact.phoneNumberForSMS())) {
      existingContacts = crmService.searchContacts(ContactSearch.byPhone(crmContact.phoneNumberForSMS()))
          .getResultsFromAllFirstPages();
    }
    if (existingContacts.isEmpty()
        && !Strings.isNullOrEmpty(crmContact.firstName) && !Strings.isNullOrEmpty(crmContact.lastName)) {
      ContactSearch contactSearch = new ContactSearch();
      contactSearch.firstName = crmContact.firstName;
      contactSearch.lastName = crmContact.lastName;
      // Only return results if an address was also available!
      if (!Strings.isNullOrEmpty(crmContact.mailingAddress.street)) {
        contactSearch.keywords = Set.of(crmContact.mailingAddress.street);
        existingContacts = crmService.searchContacts(contactSearch).getResultsFromAllFirstPages();
      }
      if (existingContacts.isEmpty() && !Strings.isNullOrEmpty(crmContact.account.mailingAddress.street)) {
        contactSearch.keywords = Set.of(crmContact.account.mailingAddress.street);
        existingContacts = crmService.searchContacts(contactSearch).getResultsFromAllFirstPages();
      }
      if (existingContacts.isEmpty() && !Strings.isNullOrEmpty(crmContact.account.billingAddress.street)) {
        contactSearch.keywords = Set.of(crmContact.account.billingAddress.street);
        existingContacts = crmService.searchContacts(contactSearch).getResultsFromAllFirstPages();
      }
    }

    if (!existingContacts.isEmpty()) {
      env.logJobInfo("found CRM contacts {}", existingContacts.stream().map(c -> c.id)
          .collect(Collectors.joining(", ")));
    }

    return existingContacts;
  }

  protected void createDonor(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
    env.logJobInfo("unable to find CRM records; creating a new account and contact");

    // create new Household Account
    String accountId = crmService.insertAccount(paymentGatewayEvent.getCrmAccount());
    paymentGatewayEvent.setCrmAccountId(accountId);

    try {
      // create new Contact
      String contactId = crmService.insertContact(paymentGatewayEvent.getCrmContact());
      // Don't need to set the full Contact here, since the event already has all the details.
      paymentGatewayEvent.setCrmContactId(contactId);
    } catch (Exception e) {
      // Nearly always, this happens due to an issue that will never self-resolve, like an invalid email address
      // with HubSpot's validation rules. Prevent duplicate, orphaned accounts.
      env.logJobWarn("CRM failed to create the contact, so halting the process and cleaning up the account we just created. Error: {}", e.getMessage());
      if (!Strings.isNullOrEmpty(accountId)) {
        crmService.deleteAccount(accountId);
        // also unset the ID, letting downstream know that it should also halt
        paymentGatewayEvent.setCrmAccountId(null);
      }
    }
  }

  protected void backfillMissingData(PaymentGatewayEvent paymentGatewayEvent,
      Optional<CrmAccount> existingAccount, Optional<CrmContact> existingContact) throws Exception {
    if (existingAccount.isPresent()) {
      if (Strings.isNullOrEmpty(existingAccount.get().billingAddress.street) && !Strings.isNullOrEmpty(paymentGatewayEvent.getCrmAccount().billingAddress.street)) {
        env.logJobInfo("existing CRM account does not have a street, but the new payment did -- overwrite the whole address");
        existingAccount.get().billingAddress = paymentGatewayEvent.getCrmAccount().billingAddress;
        crmService.updateAccount(existingAccount.get());
      }
    }

    if (existingContact.isPresent()) {
      if (Strings.isNullOrEmpty(existingContact.get().mailingAddress.street) && !Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().mailingAddress.street)) {
        env.logJobInfo("existing CRM contact does not have a street, but the new payment did -- overwriting the whole address");
        existingContact.get().mailingAddress = paymentGatewayEvent.getCrmContact().mailingAddress;
        crmService.updateContact(existingContact.get());
      }

      if ((Strings.isNullOrEmpty(existingContact.get().firstName) || "Anonymous".equalsIgnoreCase(existingContact.get().firstName)) && !Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().firstName)) {
        env.logJobInfo("existing CRM contact does not have a firstName, but the new payment did -- overwriting it");
        existingContact.get().firstName = paymentGatewayEvent.getCrmContact().firstName;
        crmService.updateContact(existingContact.get());
      }
      if ((Strings.isNullOrEmpty(existingContact.get().lastName) || "Anonymous".equalsIgnoreCase(existingContact.get().lastName)) && !Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().lastName)) {
        env.logJobInfo("existing CRM contact does not have a lastName, but the new payment did -- overwriting it");
        existingContact.get().lastName = paymentGatewayEvent.getCrmContact().lastName;
        crmService.updateContact(existingContact.get());
      }

      if (Strings.isNullOrEmpty(existingContact.get().mobilePhone) && !Strings.isNullOrEmpty(paymentGatewayEvent.getCrmContact().mobilePhone)) {
        env.logJobInfo("existing CRM contact does not have a mobilePhone, but the new payment did -- overwriting it");
        existingContact.get().mobilePhone = paymentGatewayEvent.getCrmContact().mobilePhone;
        crmService.updateContact(existingContact.get());
      }
    }
  }

  public void processContactForm(ContactFormData formData) throws Exception {
    CrmContact formCrmContact = formData.toCrmContact();

    Optional<CrmContact> crmContact = crmService.searchContacts(ContactSearch.byEmail(formCrmContact.email)).getSingleResult();
    if (crmContact.isEmpty()) {
      env.logJobInfo("unable to find CRM contact using email {}; creating new account and contact", formCrmContact.email);
      // create new contact
      env.logJobInfo("inserting contact {}", formCrmContact.toString());
      crmService.insertContact(formCrmContact);
    } else {
      env.logJobInfo("found existing CRM account {} and contact {} using email {}", crmContact.get().account.id, crmContact.get().id, formCrmContact.email);
    }
  }
}
