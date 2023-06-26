/*
 * Copyright (c) 2023 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmCampaign;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.model.CrmRecord;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.util.Utils;
import com.sforce.ws.ConnectionException;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BulkImportService {

  private static final Logger log = LogManager.getLogger(BulkImportService.class);

  private final Environment env;
  private final CrmService crmService;

  public BulkImportService(Environment env) {
    this.env = env;
    crmService = env.primaryCrmService();
  }

  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
    // MODES:
    // - Core Records: Accounts + Contacts + Recurring Donations + Opportunities
    // - Campaigns
    // - TODO: Other types of records?

    if (importEvents.isEmpty()) {
      log.warn("no importEvents to import; exiting...");
      return;
    }

    boolean campaignMode = importEvents.stream().flatMap(e -> e.raw.entrySet().stream())
        .anyMatch(entry -> entry.getKey().startsWith("Account") && !Strings.isNullOrEmpty(entry.getValue()));
    if (campaignMode) {
      processBulkImportCampaignRecords(importEvents);
      return;
    }

    processBulkImportCoreRecords(importEvents);
  }

  protected void processBulkImportCoreRecords(List<CrmImportEvent> importEvents) throws Exception {
    // This entire method uses bulk queries and bulk inserts wherever possible!
    // We make multiple passes, focusing on one object at a time in order to use the bulk API.

    String[] accountCustomFields = getAccountCustomFields(importEvents);
    String[] contactCustomFields = getContactCustomFields(importEvents);
    String[] recurringDonationCustomFields = getRecurringDonationCustomFields(importEvents);
    String[] opportunityCustomFields = getOpportunityCustomFields(importEvents);

    List<String> accountIds = importEvents.stream().map(e -> e.accountId)
        .filter(accountId -> !Strings.isNullOrEmpty(accountId)).distinct().toList();
    Map<String, CrmAccount> existingAccountsById = new HashMap<>();
    if (accountIds.size() > 0) {
      crmService.getAccountsByIds(accountIds, accountCustomFields).forEach(c -> existingAccountsById.put(c.id, c));
    }

    Optional<String> accountExternalRefKey = importEvents.get(0).raw.keySet().stream()
        .filter(k -> k.startsWith("Account ExtRef ")).distinct().findFirst();
    Optional<String> accountExternalRefFieldName = accountExternalRefKey.map(k -> k.replace("Account ExtRef ", ""));
    Map<String, CrmAccount> existingAccountsByExRef = new HashMap<>();
    if (accountExternalRefKey.isPresent()) {
      List<String> accountExRefIds = importEvents.stream().map(e -> e.raw.get(accountExternalRefKey.get())).filter(s -> !Strings.isNullOrEmpty(s)).toList();
      if (accountExRefIds.size() > 0) {
        // The imported sheet data comes in as all strings, so use toString here too to convert numberic extref values.
        crmService.getAccountsByCustomField(accountExternalRefFieldName.get(), accountExRefIds, accountCustomFields)
            .forEach(c -> existingAccountsByExRef.put(c.getRawData(accountExternalRefFieldName.get()), c));
      }
    }

    List<String> contactIds = importEvents.stream().map(e -> e.contactId)
        .filter(contactId -> !Strings.isNullOrEmpty(contactId)).distinct().toList();
    Map<String, CrmContact> existingContactsById = new HashMap<>();
    if (contactIds.size() > 0) {
      crmService.getContactsByIds(contactIds, contactCustomFields).forEach(c -> existingContactsById.put(c.id, c));
    }

    List<String> contactEmails = importEvents.stream().map(e -> e.contactEmail)
        .filter(email -> !Strings.isNullOrEmpty(email)).distinct().toList();
    Multimap<String, CrmContact> existingContactsByEmail = ArrayListMultimap.create();
    if (contactEmails.size() > 0) {
      // Normalize the case!
      crmService.getContactsByEmails(contactEmails, contactCustomFields)
          .forEach(c -> existingContactsByEmail.put(c.email.toLowerCase(Locale.ROOT), c));
    }

    // TODO: break into first/last names, then use crmService.search?
    List<String> contactNames = importEvents.stream().map(e -> e.contactFirstName + " " + e.contactLastName)
        .filter(name -> !Strings.isNullOrEmpty(name)).distinct().toList();
    Multimap<String, CrmContact> existingContactsByName = ArrayListMultimap.create();
    if (contactNames.size() > 0) {
      // Normalize the case!
      crmService.getContactsByNames(contactNames, contactCustomFields)
          .forEach(c -> existingContactsByName.put(c.getFullName().toLowerCase(Locale.ROOT), c));
    }

    Optional<String> contactExternalRefKey = importEvents.get(0).raw.keySet().stream()
        .filter(k -> k.startsWith("Contact ExtRef ")).findFirst();
    Optional<String> contactExternalRefFieldName = contactExternalRefKey.map(k -> k.replace("Contact ExtRef ", ""));
    Map<String, CrmContact> existingContactsByExRef = new HashMap<>();
    if (contactExternalRefKey.isPresent()) {
      List<String> contactExRefIds = importEvents.stream().map(e -> e.raw.get(contactExternalRefKey.get()))
          .filter(s -> !Strings.isNullOrEmpty(s)).distinct().toList();
      if (contactExRefIds.size() > 0) {
        // The imported sheet data comes in as all strings, so use toString here too to convert numberic extref values.
        crmService.getContactsByCustomFields(contactExternalRefFieldName.get(), contactExRefIds, contactCustomFields)
            .forEach(c -> existingContactsByExRef.put(c.getRawData(contactExternalRefFieldName.get()), c));
      }
    }

    List<String> campaignNames = importEvents.stream().flatMap(e -> Stream.of(e.contactCampaignName, e.opportunityCampaignName))
        .filter(name -> !Strings.isNullOrEmpty(name)).distinct().collect(Collectors.toList());
    Map<String, String> campaignNameToId = Collections.emptyMap();
    if (campaignNames.size() > 0) {
      // Normalize the case!
      campaignNameToId = crmService.getCampaignsByNames(campaignNames).stream()
          .collect(Collectors.toMap(c -> c.name().toLowerCase(Locale.ROOT), c -> c.name()));
    }

    List<String> recurringDonationIds = importEvents.stream().map(e -> e.recurringDonationId)
        .filter(recurringDonationId -> !Strings.isNullOrEmpty(recurringDonationId)).distinct().toList();
    Map<String, CrmRecurringDonation> existingRecurringDonationById = new HashMap<>();
    if (recurringDonationIds.size() > 0) {
      crmService.getRecurringDonationsByIds(recurringDonationIds, recurringDonationCustomFields).forEach(c -> existingRecurringDonationById.put(c.id, c));
    }

    List<String> opportunityIds = importEvents.stream().map(e -> e.opportunityId)
        .filter(opportunityId -> !Strings.isNullOrEmpty(opportunityId)).distinct().toList();
    Map<String, CrmOpportunity> existingOpportunitiesById = new HashMap<>();
    if (opportunityIds.size() > 0) {
      crmService.getOpportunitiesByIds(opportunityIds, opportunityCustomFields).forEach(c -> existingOpportunitiesById.put(c.id, c));
    }

    Optional<String> opportunityExternalRefKey = importEvents.get(0).raw.keySet().stream()
        .filter(k -> k.startsWith("Opportunity ExtRef ")).findFirst();
    Map<String, CrmOpportunity> existingOpportunitiesByExRefId = new HashMap<>();
    if (opportunityExternalRefKey.isPresent()) {
      List<String> opportunityExRefIds = importEvents.stream().map(e -> e.raw.get(opportunityExternalRefKey.get()))
          .filter(s -> !Strings.isNullOrEmpty(s)).distinct().toList();
      if (opportunityExRefIds.size() > 0) {
        String fieldName = opportunityExternalRefKey.get().replace("Opportunity ExtRef ", "");
        crmService.getOpportunitiesByCustomFields(fieldName, opportunityExRefIds, opportunityCustomFields)
            .forEach(c -> existingOpportunitiesByExRefId.put(c.getRawData(fieldName), c));
      }
    }

    // we use by-id maps for batch inserts/updates, since the sheet can contain duplicate contacts/accounts
    // (especially when importing opportunities)
    List<String> batchUpdateAccounts = new ArrayList<>();
    List<String> batchInsertContacts = new ArrayList<>();
    List<String> batchUpdateContacts = new ArrayList<>();
    List<String> batchUpdateOpportunities = new ArrayList<>();
    List<String> batchUpdateRecurringDonations = new ArrayList<>();

    // Don't update Accounts unless we have to.
    boolean accountUpdates = importEvents.stream().flatMap(e -> e.raw.entrySet().stream())
        .anyMatch(entry -> entry.getKey().startsWith("Account") && !Strings.isNullOrEmpty(entry.getValue()));

    // If we're doing Opportunity/RD inserts or Campaign updates, we unfortunately can't use batch inserts/updates of accounts/contacts.
    // TODO: We probably *can*, but the code will be rather complex to manage the variety of batch actions paired with CSV rows.
    boolean oppMode = importEvents.get(0).opportunityDate != null || importEvents.get(0).opportunityId != null;
    boolean rdMode = importEvents.get(0).recurringDonationAmount != null || importEvents.get(0).recurringDonationId != null;
    boolean nonBatchMode = oppMode || rdMode || importEvents.get(0).contactCampaignId != null || importEvents.get(0).contactCampaignName != null;

    List<String> nonBatchAccountIds = new ArrayList<>();
    List<String> nonBatchContactIds = new ArrayList<>();
    while(nonBatchAccountIds.size() < importEvents.size()) nonBatchAccountIds.add("");
    while(nonBatchContactIds.size() < importEvents.size()) nonBatchContactIds.add("");

    // IMPORTANT IMPORTANT IMPORTANT
    // Process importEvents in two passes, first processing the ones that will result in an update to a contact, then
    // later process the contacts that will result in an insert. Using CLHS as an example:
    // Spouse A is in SFDC due to the Raiser's Edge migration.
    // Spouse B is missing.
    // Spouse A and Spouse B are both included in the FACTS migration.
    // We need Spouse A to be processed first, setting the FACTS Family ID on the *existing* account.
    // Then, Spouse B will land under that same account, since the Family ID is now available for lookup.
    // If it were the other way around, there's a good chance that Spouse B would land in their own isolated Account,
    // unless there was a direct address match.

    for (int _i = 0; _i < importEvents.size() * 2; _i++) {
      // TODO: Not sure if this setup is clever or hacky...
      boolean secondPass = _i >= importEvents.size();
      if (_i == importEvents.size()) {
        crmService.batchFlush();
      }
      int i = _i % importEvents.size();

      CrmImportEvent importEvent = importEvents.get(i);

      if (secondPass && !importEvent.secondPass) {
        continue;
      }

      log.info("import processing contacts/account on row {} of {}", i + 2, importEvents.size() + 1);

      // If the accountId or account extref is explicitly given, run the account update. Otherwise, let the contact queries determine it.
      // TODO: If these conditions are met, it looks like the contact queries will update them too?
      CrmAccount account = null;
      if (!Strings.isNullOrEmpty(importEvent.accountId)) {
        CrmAccount existingAccount = existingAccountsById.get(importEvent.accountId);

        if (existingAccount != null) {
          account = new CrmAccount();
          account.id = existingAccount.id;

          setBulkImportAccountFields(account, existingAccount, importEvent);
          if (!batchUpdateAccounts.contains(existingAccount.id)) {
            batchUpdateAccounts.add(existingAccount.id);
            crmService.batchUpdate(account);
          }
        }
      } else if (accountExternalRefKey.isPresent()) {
        CrmAccount existingAccount = existingAccountsByExRef.get(importEvent.raw.get(accountExternalRefKey.get()));

        if (existingAccount != null) {
          account = new CrmAccount();
          account.id = existingAccount.id;

          setBulkImportAccountFields(account, existingAccount, importEvent);
          if (!batchUpdateAccounts.contains(existingAccount.id)) {
            batchUpdateAccounts.add(existingAccount.id);
            crmService.batchUpdate(account);
          }
        } else {
          // IMPORTANT: We're making an assumption here that if an ExtRef is provided, the expectation is that the
          // account already exists (which wasn't true, above) OR that it should be created. REGARDLESS of the contact's
          // current account, if any. We're opting to create the new account, update the contact's account ID, and
          // possibly abandon its old account (if it exists).
          // TODO: This was mainly due to CLHS' original FACTS migration that created isolated households or, worse,
          //  combined grandparents into the student's household. Raiser's Edge has the correct relationships and
          //  households, so we're using this to override the past.
//          account = insertBulkImportAccount(importEvent.contactLastName + " Household", importEvent);
        }
      }

      CrmContact contact = null;

      // A few situations have come up where there were not cleanly-split first vs. last name columns, but instead a
      // "salutation" (firstname) and then a full name. Allow users to provide the former as the firstname
      // and the latter as the "lastname", but clean it up. This must happen before the first names are split up, below!
      if (!Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(importEvent.contactLastName)
          && importEvent.contactLastName.contains(importEvent.contactFirstName)) {
        importEvent.contactLastName = importEvent.contactLastName.replace(importEvent.contactFirstName, "").trim();
      }

      // Some sources combine multiple contacts into a single comma-separated list of first names. The above
      // would deal with that if there were an ID or Email match. But here, before we insert, we'll split it up
      // into multiple contacts. Allow the first listed contact to be the primary.
      List<String> secondaryFirstNames = new ArrayList<>();
      if (!Strings.isNullOrEmpty(importEvent.contactFirstName)) {
        String[] split = importEvent.contactFirstName
            .replaceAll(",*\\s+and\\s+", ",").replaceAll(",*\\s+&\\s+", ",")
            .split(",+");
        importEvent.contactFirstName = split[0];
        secondaryFirstNames = Arrays.stream(split).skip(1).toList();
      }

      // Deep breath. It gets weird.

      // If we're in the second pass, we already know we need to insert the contact.
      if (secondPass) {
        if (accountExternalRefKey.isPresent() && existingAccountsByExRef.containsKey(importEvent.raw.get(accountExternalRefKey.get()))) {
          // If we have an external ref, try that first.
          account = existingAccountsByExRef.get(importEvent.raw.get(accountExternalRefKey.get()));
        }
        if (account == null) {
          account = insertBulkImportAccount(importEvent.contactLastName + " Household", importEvent,
              accountExternalRefFieldName, existingAccountsByExRef);
        }

        contact = insertBulkImportContact(importEvent, account.id, batchInsertContacts,
            existingContactsByEmail, contactExternalRefFieldName, existingContactsByExRef, nonBatchMode);
      }
      // If the explicit Contact ID was given and the contact actually exists, update.
      else if (!Strings.isNullOrEmpty(importEvent.contactId) && existingContactsById.containsKey(importEvent.contactId)) {
        CrmContact existingContact = existingContactsById.get(importEvent.contactId);

        String accountId = existingContact.account.id;
        if (account == null) {
          if (!Strings.isNullOrEmpty(accountId)) {
            CrmAccount existingAccount = existingContact.account;
            account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts, accountUpdates);
          }
        }

        // use accountId, not account.id -- if the contact was recently imported by this current process,
        // the contact.account child relationship will not yet exist in the existingContactsById map
        contact = updateBulkImportContact(existingContact, accountId, importEvent, batchUpdateContacts);
      }
      // Similarly, if we have an external ref ID, check that next.
      else if (contactExternalRefKey.isPresent() && existingContactsByExRef.containsKey(importEvent.raw.get(contactExternalRefKey.get()))) {
        CrmContact existingContact = existingContactsByExRef.get(importEvent.raw.get(contactExternalRefKey.get()));

        String accountId = existingContact.account.id;
        if (account == null) {
          if (!Strings.isNullOrEmpty(accountId)) {
            CrmAccount existingAccount = (CrmAccount) existingContact.account;
            account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts, accountUpdates);
          }
        }

        // use accountId, not account.id -- if the contact was recently imported by this current process,
        // the contact.account child relationship will not yet exist in the existingContactsByExRefId map
        contact = updateBulkImportContact(existingContact, accountId, importEvent, batchUpdateContacts);
      }
      // Else if a contact already exists with the given email address, update.
      else if (!Strings.isNullOrEmpty(importEvent.contactEmail) && existingContactsByEmail.containsKey(importEvent.contactEmail.toLowerCase(Locale.ROOT))) {
        // If the email address has duplicates, use the oldest.
        CrmContact existingContact = existingContactsByEmail.get(importEvent.contactEmail.toLowerCase(Locale.ROOT)).stream()
            .min(Comparator.comparing(c -> (c.getField("CreatedDate")))).get();

        String accountId = existingContact.account.id;
        if (account == null) {
          if (!Strings.isNullOrEmpty(accountId)) {
            CrmAccount existingAccount = (CrmAccount) existingContact.account;
            account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts, accountUpdates);
          }
        }

        // use accountId, not account.id -- if the contact was recently imported by this current process,
        // the contact.account child relationship will not yet exist in the existingContactsByEmail map
        contact = updateBulkImportContact(existingContact, accountId, importEvent, batchUpdateContacts);
      }
      // A little weird looking, but if importing with constituent full names, often it could be either a household
      // name (and email would likely exist) or a business name (almost never email). In either case, hard for us to know
      // which to choose, so by default simply upsert the Account and ignore the contact altogether.
      // Similarly, if we only have a last name or an account name, treat it the same.
      else if (
          Strings.isNullOrEmpty(importEvent.contactEmail) && (
              !Strings.isNullOrEmpty(importEvent.contactFullName)
                  || !Strings.isNullOrEmpty(importEvent.accountName)
                  || (Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(importEvent.contactLastName))
          )
      ) {
        String fullname;
        if (!Strings.isNullOrEmpty(importEvent.contactFullName)) {
          fullname = importEvent.contactFullName;
        } else if (!Strings.isNullOrEmpty(importEvent.accountName)) {
          fullname = importEvent.accountName;
        } else {
          fullname = importEvent.contactLastName;
        }

        CrmAccount existingAccount;
        if (accountExternalRefKey.isPresent() && existingAccountsByExRef.containsKey(importEvent.raw.get(accountExternalRefKey.get()))) {
          // If we have an external ref, try that first.
          existingAccount = existingAccountsByExRef.get(importEvent.raw.get(accountExternalRefKey.get()));
        } else {
          // Otherwise, by name.
          // TODO: Fetch all and hold in memory?
          existingAccount = crmService.getAccountsByName(fullname, accountCustomFields).stream().findFirst().orElse(null);
        }

        if (existingAccount == null) {
          account = insertBulkImportAccount(fullname, importEvent, accountExternalRefFieldName, existingAccountsByExRef);
        } else {
          account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts, true);
        }
      }
      // If we have a first and last name, try searching for an existing contact by name.
      // Only do this if we can match against street address or mobile number as well. Simply by-name is too risky.
      // Better to allow duplicates than to overwrite records.
      // If 1 match, update. If 0 matches, insert. If 2 or more matches, skip completely out of caution.
      else if (!Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(importEvent.contactLastName)
          && existingContactsByName.containsKey(importEvent.contactFirstName.toLowerCase(Locale.ROOT) + " " + importEvent.contactLastName.toLowerCase(Locale.ROOT))
          && (!Strings.isNullOrEmpty(importEvent.contactMailingStreet) || !Strings.isNullOrEmpty(importEvent.accountBillingStreet) || !Strings.isNullOrEmpty(importEvent.contactMobilePhone))) {
        List<CrmContact> existingContacts = existingContactsByName.get(importEvent.contactFirstName.toLowerCase(Locale.ROOT) + " " + importEvent.contactLastName.toLowerCase()).stream()
            .filter(c -> {
              // make the address checks a little more resilient by removing all non-alphanumerics
              // ex: 123 Main St. != 123 Main St --> 123MainSt == 123MainSt
              if (!Strings.isNullOrEmpty(importEvent.contactMailingStreet)) {
                String importStreet = importEvent.contactMailingStreet.replaceAll("[^A-Za-z0-9]", "");
                String sfdcStreet = c.mailingAddress.street == null ? "" : c.mailingAddress.street.replaceAll("[^A-Za-z0-9]", "");
                if (importStreet.equals(sfdcStreet)) {
                  return true;
                }
              }
              if (!Strings.isNullOrEmpty(importEvent.accountBillingStreet)) {
                String importStreet = importEvent.accountBillingStreet.replaceAll("[^A-Za-z0-9]", "");
                String sfdcStreet = c.account.billingAddress.street == null ? "" : c.account.billingAddress.street.replaceAll("[^A-Za-z0-9]", "");
                if (importStreet.equals(sfdcStreet)) {
                  return true;
                }
              }
              if (!Strings.isNullOrEmpty(importEvent.contactMobilePhone)) {
                String importMobile = importEvent.contactMobilePhone.replaceAll("\\D", "");
                String sfdcMobile = c.mobilePhone == null ? "" : c.mobilePhone.replaceAll("\\D", "");
                if (importMobile.equals(sfdcMobile)) {
                  return true;
                }
              }

              return false;
            }).toList();
        log.info("number of contacts for name {} {}: {}", importEvent.contactFirstName, importEvent.contactLastName, existingContacts.size());

        if (existingContacts.size() > 1) {
          // To be safe, let's skip this row for now and deal with it manually...
          log.warn("skipping contact in row {} due to multiple contacts found by-name", i + 2);
        } else if (existingContacts.size() == 1) {
          CrmContact existingContact = existingContacts.get(0);

          String accountId = existingContact.account.id;
          if (account == null) {
            if (!Strings.isNullOrEmpty(accountId)) {
              CrmAccount existingAccount = (CrmAccount) existingContact.account;
              account = updateBulkImportAccount(existingAccount, importEvent, batchUpdateAccounts, accountUpdates);
            }
          }

          contact = updateBulkImportContact(existingContact, accountId, importEvent, batchUpdateContacts);
        } else {
          importEvent.secondPass = true;
          continue;
        }
      }
      // Otherwise, abandon all hope and insert, but only if we at least have a lastname or email.
      else if (!Strings.isNullOrEmpty(importEvent.contactEmail) || !Strings.isNullOrEmpty(importEvent.contactLastName)) {
        importEvent.secondPass = true;
        continue;
      }

      if (contact != null) {
        if (!Strings.isNullOrEmpty(importEvent.contactCampaignId)) {
          addContactToCampaign(contact.id, importEvent.contactCampaignId);
        } else if (!Strings.isNullOrEmpty(importEvent.contactCampaignName) && campaignNameToId.containsKey(importEvent.contactCampaignName.toLowerCase(Locale.ROOT))) {
          addContactToCampaign(contact.id, campaignNameToId.get(importEvent.contactCampaignName.toLowerCase(Locale.ROOT)));
        }
      }

      if (nonBatchMode) {
        nonBatchAccountIds.set(i, account != null ? account.id : null);
        nonBatchContactIds.set(i, contact != null ? contact.id : null);
      }

      // Save off the secondary contacts, always batching them (no need to hold onto IDs for later use).
      // But, skip this whole process if for some reason we don't already have an Account to add them into.
      if (!secondaryFirstNames.isEmpty() && account != null && !Strings.isNullOrEmpty(account.id)) {
        // Retrieve all the contacts on this account. If it previously existed, ensure we're not creating duplicate
        // contacts from the secondary list.
        List<CrmContact> accountContacts = crmService.searchContacts(ContactSearch.byAccountId(account.id), contactCustomFields).getResults();

        for (String secondaryFirstName : secondaryFirstNames) {
          importEvent.contactFirstName = secondaryFirstName;
          // null out the email/phone, since they're typically relevant only to the primary
          importEvent.contactEmail = null;
          importEvent.contactMobilePhone = null;
          importEvent.contactHomePhone = null;
          importEvent.contactWorkPhone = null;

          List<CrmContact> existingContacts = accountContacts.stream()
              .filter(c -> secondaryFirstName.equalsIgnoreCase(c.firstName)).toList();
          if (existingContacts.size() == 1) {
            CrmContact existingContact = existingContacts.get(0);

            updateBulkImportContact(existingContact, account.id, importEvent, batchUpdateContacts);
          } else if (existingContacts.size() == 0) {
            insertBulkImportContact(importEvent, account.id, batchInsertContacts,
                existingContactsByEmail, contactExternalRefFieldName, existingContactsByExRef, nonBatchMode);
          }
        }
      }

      env.logJobProgress("Imported " + (i + 1) + " contacts");
    }

    crmService.batchFlush();

    if (rdMode) {
      // TODO: Won't this loop process the same RD over and over each time it appears in an Opp row? Keep track of "visited"?
      for (int i = 0; i < importEvents.size(); i++) {
        CrmImportEvent importEvent = importEvents.get(i);

        log.info("import processing recurring donations on row {} of {}", i + 2, importEvents.size() + 1);

        CrmRecurringDonation recurringDonation = new CrmRecurringDonation();

        // TODO: duplicates setRecurringDonationFields
        if (env.getConfig().salesforce.enhancedRecurringDonations) {
          if (nonBatchContactIds.get(i) != null) {
            recurringDonation.contact.id = nonBatchContactIds.get(i);
          } else {
            recurringDonation.account.id = nonBatchAccountIds.get(i);
          }
        } else {
          recurringDonation.account.id = nonBatchAccountIds.get(i);
        }

        // TODO: Add a RD Campaign Name column as well?
        if (!Strings.isNullOrEmpty(importEvent.recurringDonationCampaignId)) {
          recurringDonation.campaignId = importEvent.recurringDonationCampaignId;
        } else if (!Strings.isNullOrEmpty(importEvent.opportunityCampaignName) && campaignNameToId.containsKey(importEvent.opportunityCampaignName.toLowerCase(Locale.ROOT))) {
          recurringDonation.campaignId = campaignNameToId.get(importEvent.opportunityCampaignName.toLowerCase(Locale.ROOT));
        }

        if (!Strings.isNullOrEmpty(importEvent.recurringDonationId)) {
          recurringDonation.id = importEvent.recurringDonationId;
          setBulkImportRecurringDonationFields(recurringDonation, existingRecurringDonationById.get(importEvent.recurringDonationId), importEvent);
          if (!batchUpdateRecurringDonations.contains(importEvent.recurringDonationId)) {
            batchUpdateRecurringDonations.add(importEvent.recurringDonationId);
            crmService.batchUpdate(recurringDonation);
          }
        } else {
          // If the account and contact upserts both failed, avoid creating an orphaned rd.
          if (nonBatchAccountIds.get(i) == null && nonBatchContactIds.get(i) == null) {
            continue;
          }

          setBulkImportRecurringDonationFields(recurringDonation, null, importEvent);
          crmService.batchInsert(recurringDonation);
        }

        env.logJobProgress("Imported " + (i + 1) + " recurring donations");
      }

      crmService.batchFlush();
    }

    if (oppMode) {
      for (int i = 0; i < importEvents.size(); i++) {
        CrmImportEvent importEvent = importEvents.get(i);

        log.info("import processing opportunities on row {} of {}", i + 2, importEvents.size() + 1);

        CrmOpportunity opportunity = new CrmOpportunity();

        opportunity.account.id = nonBatchAccountIds.get(i);
        if (!Strings.isNullOrEmpty(nonBatchContactIds.get(i))) {
          opportunity.contact.id = nonBatchContactIds.get(i);
        }

        if (!Strings.isNullOrEmpty(importEvent.opportunityCampaignId)) {
          opportunity.campaignId = importEvent.opportunityCampaignId;
        } else if (!Strings.isNullOrEmpty(importEvent.opportunityCampaignName) && campaignNameToId.containsKey(importEvent.opportunityCampaignName.toLowerCase(Locale.ROOT))) {
          opportunity.campaignId = campaignNameToId.get(importEvent.opportunityCampaignName.toLowerCase(Locale.ROOT));
        }

        if (!Strings.isNullOrEmpty(importEvent.opportunityId)) {
          opportunity.id = importEvent.opportunityId;
          setBulkImportOpportunityFields(opportunity, existingOpportunitiesById.get(opportunity.id), importEvent);
          if (!batchUpdateOpportunities.contains(opportunity.id)) {
            batchUpdateOpportunities.add(opportunity.id);
            crmService.batchUpdate(opportunity);
          }
        } else if (opportunityExternalRefKey.isPresent() && existingOpportunitiesByExRefId.containsKey(importEvent.raw.get(opportunityExternalRefKey.get()))) {
          CrmOpportunity existingOpportunity = existingOpportunitiesByExRefId.get(importEvent.raw.get(opportunityExternalRefKey.get()));

          opportunity.id = existingOpportunity.id;
          setBulkImportOpportunityFields(opportunity, existingOpportunity, importEvent);
          if (!batchUpdateOpportunities.contains(opportunity.id)) {
            batchUpdateOpportunities.add(opportunity.id);
            crmService.batchUpdate(opportunity);
          }
        } else {
          // If the account and contact upserts both failed, avoid creating an orphaned opp.
          if (nonBatchAccountIds.get(i) == null && nonBatchContactIds.get(i) == null) {
            log.info("skipping opp {} import, due to account/contact failure", i + 2);
            continue;
          }
          if (!importEvent.opportunitySkipDuplicateCheck && importEvent.opportunityAmount != null && Strings.isNullOrEmpty(importEvent.opportunityId)) {
            List<CrmOpportunity> existingOpportunities = crmService.searchOpportunities(nonBatchAccountIds.get(i), nonBatchContactIds.get(i),
                importEvent.opportunityDate, importEvent.opportunityAmount.doubleValue(), opportunityCustomFields);
            if (!existingOpportunities.isEmpty()) {
              log.info("skipping opp {} import, due to possible duplicate: {}", i + 2, existingOpportunities.get(0).id);
              continue;
            }
          }

          setBulkImportOpportunityFields(opportunity, null, importEvent);

          crmService.batchInsert(opportunity);
        }
        env.logJobProgress("Imported " + (i + 1) + " opportunities");
      }

      crmService.batchFlush();
    }

    log.info("bulk import complete");
  }

  protected CrmAccount updateBulkImportAccount(CrmAccount existingAccount, CrmImportEvent importEvent,
      List<String> bulkUpdateAccounts, boolean accountUpdates) throws Exception {
    // TODO: Odd situation. When insertBulkImportContact creates a contact, it's also creating an Account, sets the
    //  AccountId on the Contact and then adds the Contact to existingContactsByEmail so we can reuse it. But when
    //  we encounter the contact again, the code upstream attempts to update the Account. 1) We don't need to, since it
    //  was just created, and 2) we can't set Contact.Account since that child can't exist when the contact is inserted,
    //  and that insert might happen later during batch processing.
    if (existingAccount == null) {
      return null;
    }

    if (!accountUpdates) {
      return existingAccount;
    }

    CrmAccount account = new CrmAccount();
    account.id = existingAccount.id;

    setBulkImportAccountFields(account, existingAccount, importEvent);

    if (!bulkUpdateAccounts.contains(account.id)) {
      bulkUpdateAccounts.add(account.id);
      crmService.batchUpdate(account);
    }

    return account;
  }

  protected CrmAccount insertBulkImportAccount(
      String accountName,
      CrmImportEvent importEvent,
      Optional<String> accountExternalRefFieldName,
      Map<String, CrmAccount> existingAccountsByExRef
  ) throws Exception {
    CrmAccount account = new CrmAccount();

    account.name = accountName;
    setBulkImportAccountFields(account, null, importEvent);

    account.id = crmService.insertAccount(account);

    // Since we hold existingAccountsByExRef in memory and don't requery it, add entries as we go to prevent
    // duplicate inserts.
    if (accountExternalRefFieldName.isPresent() && !Strings.isNullOrEmpty(account.getRawData(accountExternalRefFieldName.get()))) {
      existingAccountsByExRef.put(account.getRawData(accountExternalRefFieldName.get()), account);
    }

    return account;
  }

  protected CrmContact updateBulkImportContact(CrmContact existingContact, String accountId, CrmImportEvent importEvent,
      List<String> bulkUpdateContacts) throws Exception {
    CrmContact contact = new CrmContact();
    contact.id = existingContact.id;
    contact.account.id = accountId;

    contact.firstName = importEvent.contactFirstName;
    contact.lastName = importEvent.contactLastName;

    setBulkImportContactFields(contact, existingContact, importEvent);
    if (!bulkUpdateContacts.contains(existingContact.id)) {
      bulkUpdateContacts.add(existingContact.id);
      crmService.batchUpdate(contact);
    }

    return contact;
  }

  protected CrmContact insertBulkImportContact(
      CrmImportEvent importEvent,
      String accountId,
      List<String> bulkInsertContacts,
      Multimap<String, CrmContact> existingContactsByEmail,
      Optional<String> contactExternalRefFieldName,
      Map<String, CrmContact> existingContactsByExRef,
      boolean nonBatchMode
  ) throws Exception {
    CrmContact contact = new CrmContact();

    // last name is required
    if (Strings.isNullOrEmpty(importEvent.contactLastName)) {
      contact.lastName = "Anonymous";
    } else {
      contact.firstName = importEvent.contactFirstName;
      contact.lastName = importEvent.contactLastName;
    }

    setBulkImportContactFields(contact, null, importEvent);

    contact.account.id = accountId;

    if (nonBatchMode) {
      contact.id = crmService.insertContact(contact);
    } else {
      String key;
      if (!Strings.isNullOrEmpty(contact.email)) {
        key = contact.email;
      } else {
        key = contact.firstName + " " + contact.lastName;
      }

      if (!bulkInsertContacts.contains(key)) {
        bulkInsertContacts.add(key);
        crmService.batchInsert(contact);
      }
    }

    // Since we hold existingContactsByEmail in memory and don't requery it, add entries as we go to prevent
    // duplicate inserts.
    if (!Strings.isNullOrEmpty(contact.email)) {
      existingContactsByEmail.put(contact.email.toLowerCase(Locale.ROOT), contact);
    }
    // Ditto for extrefs.
    if (contactExternalRefFieldName.isPresent() && !Strings.isNullOrEmpty(contact.getRawData(contactExternalRefFieldName.get()))) {
      existingContactsByExRef.put(contact.getRawData(contactExternalRefFieldName.get()), contact);
    }

    return contact;
  }

  protected void setBulkImportContactFields(CrmContact contact, CrmContact existingContact, CrmImportEvent importEvent)
      throws ExecutionException {
    if (!Strings.isNullOrEmpty(importEvent.contactRecordTypeId)) {
      contact.typeId = importEvent.contactRecordTypeId;
    } else if (!Strings.isNullOrEmpty(importEvent.contactRecordTypeName)) {
      contact.typeId = recordTypeNameToIdCache.get(importEvent.contactRecordTypeName);
    }

    contact.mailingAddress.street = importEvent.contactMailingStreet;
    contact.mailingAddress.city = importEvent.contactMailingCity;
    contact.mailingAddress.state = importEvent.contactMailingState;
    contact.mailingAddress.postalCode = importEvent.contactMailingZip;
    contact.mailingAddress.country = importEvent.contactMailingCountry;
    contact.homePhone = importEvent.contactHomePhone;
    contact.mobilePhone = importEvent.contactMobilePhone;
    contact.workPhone = importEvent.contactWorkPhone;
    contact.preferredPhone = CrmContact.PreferredPhone.fromName(importEvent.contactPreferredPhone);

    if (!Strings.isNullOrEmpty(importEvent.contactEmail) && !"na".equalsIgnoreCase(importEvent.contactEmail) && !"n/a".equalsIgnoreCase(importEvent.contactEmail)) {
      // Some sources provide comma separated lists. Simply use the first one.
      contact.email = importEvent.contactEmail.split("[,;\\s]+")[0];
    }

    if (importEvent.contactOptInEmail != null && importEvent.contactOptInEmail) {
      contact.addRawData(env.getConfig().salesforce.fieldDefinitions.emailOptIn, true);
      contact.addRawData(env.getConfig().salesforce.fieldDefinitions.emailOptOut, false);
    }
    if (importEvent.contactOptOutEmail != null && importEvent.contactOptOutEmail) {
      contact.addRawData(env.getConfig().salesforce.fieldDefinitions.emailOptIn, false);
      contact.addRawData(env.getConfig().salesforce.fieldDefinitions.emailOptOut, true);
    }
    if (importEvent.contactOptInSms != null && importEvent.contactOptInSms) {
      contact.addRawData(env.getConfig().salesforce.fieldDefinitions.smsOptIn, true);
      contact.addRawData(env.getConfig().salesforce.fieldDefinitions.smsOptOut, false);
    }
    if (importEvent.contactOptOutSms != null && importEvent.contactOptOutSms) {
      contact.addRawData(env.getConfig().salesforce.fieldDefinitions.smsOptIn, false);
      contact.addRawData(env.getConfig().salesforce.fieldDefinitions.smsOptOut, true);
    }

    contact.ownerId = importEvent.contactOwnerId;

    setBulkImportCustomFields(contact, existingContact, "Contact", importEvent);
  }

  protected void setBulkImportAccountFields(CrmAccount account, CrmAccount existingAccount, CrmImportEvent importEvent)
      throws ExecutionException {
    // TODO: CACHE THIS!
    if (!Strings.isNullOrEmpty(importEvent.accountRecordTypeId)) {
      account.typeId = importEvent.accountRecordTypeId;
    } else if (!Strings.isNullOrEmpty(importEvent.accountRecordTypeName)) {
      account.typeId = recordTypeNameToIdCache.get(importEvent.accountRecordTypeName);
    }

    account.billingAddress.street = importEvent.accountBillingStreet;
    account.billingAddress.city = importEvent.accountBillingCity;
    account.billingAddress.state = importEvent.accountBillingState;
    account.billingAddress.postalCode = importEvent.accountBillingZip;
    account.billingAddress.country = importEvent.accountBillingCountry;

    account.ownerId = importEvent.accountOwnerId;

    setBulkImportCustomFields(account, existingAccount, "Account", importEvent);
  }

  protected void setBulkImportRecurringDonationFields(CrmRecurringDonation recurringDonation, CrmRecurringDonation existingRecurringDonation, CrmImportEvent importEvent)
      throws ConnectionException, InterruptedException {
    if (importEvent.recurringDonationAmount != null) {
      recurringDonation.setField("Npe03__Amount__c", importEvent.recurringDonationAmount.doubleValue());
    }
    recurringDonation.setField("Npe03__Open_Ended_Status__c", importEvent.recurringDonationStatus);
    recurringDonation.setField("Npe03__Schedule_Type__c", "Multiply By");
    CrmRecurringDonation.Frequency frequency = CrmRecurringDonation.Frequency.fromName(importEvent.recurringDonationInterval);
    if (frequency != null) {
      recurringDonation.setField("Npe03__Installment_Period__c", frequency.name());
    }
    recurringDonation.setField("Npe03__Date_Established__c", importEvent.recurringDonationStartDate);
    recurringDonation.setField("Npe03__Next_Payment_Date__c", importEvent.recurringDonationNextPaymentDate);

    if (env.getConfig().salesforce.enhancedRecurringDonations) {
      recurringDonation.setField("npsp__RecurringType__c", "Open");
      // It's a picklist, so it has to be a string and not numeric :(
      recurringDonation.setField("npsp__Day_of_Month__c", importEvent.recurringDonationStartDate.get(Calendar.DAY_OF_MONTH) + "");
    }

    recurringDonation.setField("OwnerId", importEvent.recurringDonationOwnerId);

    setBulkImportCustomFields(recurringDonation, existingRecurringDonation, "Recurring Donation", importEvent);
  }

  protected void setBulkImportOpportunityFields(CrmOpportunity opportunity, CrmOpportunity existingOpportunity, CrmImportEvent importEvent)
      throws ExecutionException {
    if (!Strings.isNullOrEmpty(importEvent.opportunityRecordTypeId)) {
      opportunity.setField("RecordTypeId", importEvent.opportunityRecordTypeId);
    } else if (!Strings.isNullOrEmpty(importEvent.opportunityRecordTypeName)) {
      opportunity.setField("RecordTypeId", recordTypeNameToIdCache.get(importEvent.opportunityRecordTypeName));
    }
    if (!Strings.isNullOrEmpty(importEvent.opportunityName)) {
      // 120 is typically the max length
      int length = Math.min(importEvent.opportunityName.length(), 120);
      opportunity.setField("Name", importEvent.opportunityName.substring(0, length));
    } else if (existingOpportunity == null || Strings.isNullOrEmpty(existingOpportunity.getField("Name"))) {
      opportunity.setField("Name", importEvent.contactFullName() + " Opportunity");
    }
    opportunity.setField("Description", importEvent.opportunityDescription);
    if (importEvent.opportunityAmount != null) {
      opportunity.setField("Amount", importEvent.opportunityAmount.doubleValue());
    }
    if (!Strings.isNullOrEmpty(importEvent.opportunityStageName)) {
      opportunity.setField("StageName", importEvent.opportunityStageName);
    } else {
      opportunity.setField("StageName", "Closed Won");
    }
    opportunity.setField("CloseDate", importEvent.opportunityDate);

    opportunity.setField("OwnerId", importEvent.opportunityOwnerId);

    setBulkImportCustomFields(opportunity, existingOpportunity, "Opportunity", importEvent);
  }

  protected void setBulkImportCustomFields(CrmRecord crmRecord, CrmRecord existingCrmRecord, String type, CrmImportEvent importEvent) {
    String prefix = type + " Custom ";

    importEvent.raw.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix) && !Strings.isNullOrEmpty(entry.getValue())).forEach(entry -> {
      String key = entry.getKey().replace(prefix, "");

      if (key.startsWith("Append ")) {
        // appending to a multiselect picklist
        key = key.replace("Append ", "");
        String value = getMultiselectPicklistValue(key, entry.getValue(), existingCrmRecord);
        crmRecord.setField(key, value);
      } else {
        crmRecord.setField(key, getCustomBulkValue(entry.getValue()));
      }
    });
  }

  protected String getMultiselectPicklistValue(String key, String value, CrmRecord existingCrmRecord) {
    if (existingCrmRecord != null) {
      String existingValue = existingCrmRecord.getRawData(key);
      if (!Strings.isNullOrEmpty(existingValue) && !existingValue.contains(value)) {
        return Strings.isNullOrEmpty(existingValue) ? value : existingValue + ";" + value;
      }
    }
    return value;
  }

  // When querying for existing records, we need to include the custom values the import event cares about. We will need
  // those custom values in order to, for example, append to existing multiselect picklists.
  protected String[] getAccountCustomFields(List<CrmImportEvent> importEvents) {
    return importEvents.stream().flatMap(e -> e.raw.keySet().stream())
        .distinct().filter(k -> k.startsWith("Account Custom "))
        .map(k -> k.replace("Account Custom ", "").replace("Append ", "")).toArray(String[]::new);
  }
  protected String[] getContactCustomFields(List<CrmImportEvent> importEvents) {
    String[] contactFields = importEvents.stream().flatMap(e -> e.raw.keySet().stream())
        .distinct().filter(k -> k.startsWith("Contact Custom "))
        .map(k -> k.replace("Contact Custom ", "").replace("Append ", "")).toArray(String[]::new);
    // We also need the account values!
    String[] accountFields = importEvents.stream().flatMap(e -> e.raw.keySet().stream())
        .distinct().filter(k -> k.startsWith("Account Custom "))
        .map(k -> "Account." + k.replace("Account Custom ", "").replace("Append ", "")).toArray(String[]::new);
    return ArrayUtils.addAll(contactFields, accountFields);
  }
  protected String[] getRecurringDonationCustomFields(List<CrmImportEvent> importEvents) {
    return importEvents.stream().flatMap(e -> e.raw.keySet().stream())
        .distinct().filter(k -> k.startsWith("Recurring Donation Custom "))
        .map(k -> k.replace("Recurring Donation Custom ", "").replace("Append ", "")).toArray(String[]::new);
  }
  protected String[] getOpportunityCustomFields(List<CrmImportEvent> importEvents) {
    return importEvents.stream().flatMap(e -> e.raw.keySet().stream())
        .distinct().filter(k -> k.startsWith("Opportunity Custom "))
        .map(k -> k.replace("Opportunity Custom ", "").replace("Append ", "")).toArray(String[]::new);
  }

  protected void processBulkImportCampaignRecords(List<CrmImportEvent> importEvents) throws Exception {
    String[] campaignCustomFields = importEvents.stream().flatMap(e -> e.raw.keySet().stream())
        .distinct().filter(k -> k.startsWith("Campaign Custom "))
        .map(k -> k.replace("Campaign Custom ", "").replace("Append ", "")).toArray(String[]::new);;

    List<String> campaignIds = importEvents.stream().map(e -> e.campaignId)
        .filter(campaignId -> !Strings.isNullOrEmpty(campaignId)).distinct().toList();
    Map<String, CrmCampaign> existingCampaignById = new HashMap<>();
    if (campaignIds.size() > 0) {
      crmService.getCampaignsByIds(campaignIds, campaignCustomFields).forEach(c -> existingCampaignById.put(c.id, c));
    }

    for (int i = 0; i < importEvents.size(); i++) {
      CrmImportEvent importEvent = importEvents.get(i);

      log.info("import processing campaigns on row {} of {}", i + 2, importEvents.size() + 1);

      CrmCampaign campaign = new CrmCampaign();
      campaign.setField("Name", importEvent.campaignName);

      if (!Strings.isNullOrEmpty(importEvent.campaignId)) {
        campaign.id = importEvent.campaignId;
        setBulkImportCustomFields(campaign, existingCampaignById.get(importEvent.campaignId), "Campaign", importEvent);
        crmService.batchUpdate(campaign);
      } else {
        setBulkImportCustomFields(campaign, null, "Campaign", importEvent);
        crmService.batchInsert(campaign);
      }

      env.logJobProgress("Imported " + (i + 1) + " campaigns");
    }
  }

  // TODO: This is going to be a pain in the butt, but we'll try to dynamically support different data types for custom
  //  fields in bulk imports/updates, without requiring the data type to be provided. This is going to be brittle...
  protected Object getCustomBulkValue(String value) {
    Calendar c = null;
    try {
      // If a client is uploading a sheet for bulk upsert,
      // the dates are most likely to be in their own TZ
      c = Utils.getCalendarFromDateString(value, env.getConfig().timezoneId);
    } catch (Exception e) {}

    if (c != null) {
      return c;
    } else if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value)) {
      return true;
    } else if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value)) {
      return false;
    } else {
      return value;
    }
  }
}
