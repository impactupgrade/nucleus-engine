package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmCampaign;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmImportEvent;
import com.impactupgrade.nucleus.model.CrmNote;
import com.impactupgrade.nucleus.model.CrmOpportunity;
import com.impactupgrade.nucleus.model.CrmRecord;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.util.Utils;
import com.sforce.soap.partner.sobject.SObject;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.impactupgrade.nucleus.util.Utils.alphanumericOnly;
import static com.impactupgrade.nucleus.util.Utils.numericOnly;

public class BulkImportService {

  private static final Logger log = LogManager.getLogger(BulkImportService.class);

  private final Environment env;

  public BulkImportService(Environment env) {
    this.env = env;
  }

  // TODO: To make things easier, we assume CrmDonation throughout, even though some may simply be CrmOpportunity.
  //  Since CrmDonation extends CrmOpportunity, we can get away with it, but that model feels like it needs a refactor.

  public void processBulkImport(List<CrmImportEvent> importEvents) throws Exception {
    processBulkImport(importEvents, null);
  }

  public void processBulkImport(List<CrmImportEvent> importEvents, String crmType) throws Exception {
    // MODES:
    // - Core Records: Accounts + Contacts + Recurring Donations + Opportunities
    // - Campaigns
    // - TODO: Other types of records?

    if (importEvents.isEmpty()) {
      env.logJobWarn("no importEvents to import; exiting...");
      return;
    }

    CrmService crmService = env.crmService(crmType);

    boolean campaignMode = importEvents.stream().flatMap(e -> e.raw.entrySet().stream())
        .anyMatch(entry -> entry.getKey().startsWith("Campaign") && !Strings.isNullOrEmpty(entry.getValue()));
    if (campaignMode) {
      processBulkImportCampaignRecords(importEvents, crmService);
      return;
    }

    processBulkImportCoreRecords(importEvents, crmService);
  }

  // TODO: Much of this bulk import code needs genericized and pulled upstream!
  protected void processBulkImportCoreRecords(List<CrmImportEvent> importEvents, CrmService crmService) throws Exception {
    // This entire method uses bulk queries and bulk inserts wherever possible!
    // We make multiple passes, focusing on one object at a time in order to use the bulk API.

    // TODO: Instead of passing around this gigantic set of keys, maps, etc. to all the sub methods,
    //  create a BulkImportCoreContext class to house all the variables, then pass that instance around instead.

    String[] accountCustomFields = importEvents.stream().flatMap(e -> e.getAccountCustomFieldNames().stream()).distinct().toArray(String[]::new);
    String[] contactCustomFields = importEvents.stream().flatMap(e -> e.getContactCustomFieldNames().stream()).distinct().toArray(String[]::new);
    String[] recurringDonationCustomFields = importEvents.stream().flatMap(e -> e.getRecurringDonationCustomFieldNames().stream()).distinct().toArray(String[]::new);
    String[] opportunityCustomFields = importEvents.stream().flatMap(e -> e.getOpportunityCustomFieldNames().stream()).distinct().toArray(String[]::new);

    List<String> accountIds = importEvents.stream().map(e -> e.account.id)
        .filter(accountId -> !Strings.isNullOrEmpty(accountId)).distinct().toList();
    Map<String, CrmAccount> existingAccountsById = new HashMap<>();
    if (!accountIds.isEmpty()) {
      crmService.getAccountsByIds(accountIds, accountCustomFields).forEach(c -> {
        // cache both the 15 and 18 char versions, so the sheet can use either
        existingAccountsById.put(c.id, c);
        existingAccountsById.put(c.id.substring(0, 15), c);
      });
    }

    Optional<String> accountExtRefKey = importEvents.get(0).raw.keySet().stream()
        .filter(k -> k.startsWith("Account ExtRef ")).distinct().findFirst();
    Optional<String> accountExtRefFieldName = accountExtRefKey.map(k -> k.replace("Account ExtRef ", ""));
    Map<String, CrmAccount> existingAccountsByExtRef = new HashMap<>();
    if (accountExtRefKey.isPresent()) {
      List<String> accountExtRefIds = importEvents.stream().map(e -> e.raw.get(accountExtRefKey.get())).filter(s -> !Strings.isNullOrEmpty(s)).toList();
      if (!accountExtRefIds.isEmpty()) {
        // The imported sheet data comes in as all strings, so use toString here too to convert numberic extref values.
        crmService.getAccountsByUniqueField(accountExtRefFieldName.get(), accountExtRefIds, accountCustomFields)
            .forEach(c -> existingAccountsByExtRef.put(c.fieldFetcher.apply(accountExtRefFieldName.get()).toString(), c));
      }
    }

    List<String> accountNames = importEvents.stream().map(e -> e.account.name)
        .filter(name -> !Strings.isNullOrEmpty(name)).distinct().collect(Collectors.toList());
    importEvents.stream().flatMap(e -> e.contactOrganizations.stream()).map(o -> o.name)
        .filter(name -> !Strings.isNullOrEmpty(name)).distinct().forEach(accountNames::add);
    Multimap<String, CrmAccount> existingAccountsByName = ArrayListMultimap.create();
    if (!accountNames.isEmpty()) {
      // Normalize the case!
      crmService.getAccountsByNames(accountNames, accountCustomFields)
          .forEach(c -> existingAccountsByName.put(c.name.toLowerCase(Locale.ROOT), c));
    }

    List<String> contactIds = importEvents.stream().map(e -> e.contactId)
        .filter(contactId -> !Strings.isNullOrEmpty(contactId)).distinct().toList();
    Map<String, CrmContact> existingContactsById = new HashMap<>();
    if (!contactIds.isEmpty()) {
      crmService.getContactsByIds(contactIds, contactCustomFields).forEach(c -> {
        // cache both the 15 and 18 char versions, so the sheet can use either
        existingContactsById.put(c.id, c);
        existingContactsById.put(c.id.substring(0, 15), c);
      });
    }

    List<String> contactEmails = importEvents.stream()
        .map(CrmImportEvent::getAllContactEmails)
        .flatMap(Collection::stream)
        .filter(email -> !Strings.isNullOrEmpty(email)).distinct().toList();
    Multimap<String, CrmContact> existingContactsByEmail = ArrayListMultimap.create();
    if (!contactEmails.isEmpty()) {
      // Normalize the case!
      crmService.getContactsByEmails(contactEmails, contactCustomFields)
          .forEach(c -> {
                if (!Strings.isNullOrEmpty(c.email)) {
                  existingContactsByEmail.put(c.email.toLowerCase(Locale.ROOT), c);
                }
                if (!Strings.isNullOrEmpty(c.getField("npe01__HomeEmail__c"))) {
                  existingContactsByEmail.put(c.getField("npe01__HomeEmail__c").toString().toLowerCase(Locale.ROOT), c);
                }
                if (!Strings.isNullOrEmpty(c.getField("npe01__WorkEmail__c"))) {
                  existingContactsByEmail.put(c.getField("npe01__WorkEmail__c").toString().toLowerCase(Locale.ROOT), c);
                }
                if (!Strings.isNullOrEmpty(c.getField("npe01__AlternateEmail__c"))) {
                  existingContactsByEmail.put(c.getField("npe01__AlternateEmail__c").toString().toLowerCase(Locale.ROOT), c);
                }
              }
          );
    }

    List<Pair<String, String>> contactNames = importEvents.stream()
        .filter(e -> !Strings.isNullOrEmpty(e.contactFirstName) || !Strings.isNullOrEmpty(e.contactLastName))
        .map(e -> Pair.of(e.contactFirstName, e.contactLastName))
        .toList();
    Multimap<String, CrmContact> existingContactsByName = ArrayListMultimap.create();
    if (!contactNames.isEmpty()) {
      // Normalize the case!
      crmService.getContactsByNames(contactNames, contactCustomFields)
          .forEach(c -> existingContactsByName.put(c.getFullName().toLowerCase(Locale.ROOT), c));
    }

    Optional<String> contactExtRefKey = importEvents.get(0).raw.keySet().stream()
        .filter(k -> k.startsWith("Contact ExtRef ")).findFirst();
    Optional<String> contactExtRefFieldName = contactExtRefKey.map(k -> k.replace("Contact ExtRef ", ""));
    Map<String, CrmContact> existingContactsByExtRef = new HashMap<>();
    if (contactExtRefKey.isPresent()) {
      List<String> contactExtRefIds = importEvents.stream().map(e -> e.raw.get(contactExtRefKey.get()))
          .filter(s -> !Strings.isNullOrEmpty(s)).distinct().toList();
      if (!contactExtRefIds.isEmpty()) {
        // The imported sheet data comes in as all strings, so use toString here too to convert numberic extref values.
        crmService.getContactsByUniqueField(contactExtRefFieldName.get(), contactExtRefIds, contactCustomFields)
            .forEach(c -> existingContactsByExtRef.put(c.fieldFetcher.apply(contactExtRefFieldName.get()).toString(), c));
      }
    }

    List<String> campaignNames = importEvents.stream()
        .flatMap(e -> Stream.concat(e.contactCampaignNames.stream(), Stream.of(e.opportunityCampaignName)))
        .filter(name -> !Strings.isNullOrEmpty(name)).distinct().collect(Collectors.toList());
    Map<String, String> campaignNameToId = Collections.emptyMap();
    if (!campaignNames.isEmpty()) {
      // Normalize the case!
      campaignNameToId = crmService.getCampaignsByNames(campaignNames).stream()
          .collect(Collectors.toMap(c -> c.name.toLowerCase(Locale.ROOT), SObject::getId));
    }

    List<String> recurringDonationIds = importEvents.stream().map(e -> e.recurringDonationId)
        .filter(recurringDonationId -> !Strings.isNullOrEmpty(recurringDonationId)).distinct().toList();
    Map<String, CrmRecurringDonation> existingRecurringDonationById = new HashMap<>();
    if (!recurringDonationIds.isEmpty()) {
      crmService.getRecurringDonationsByIds(recurringDonationIds, recurringDonationCustomFields).forEach(c -> {
        // cache both the 15 and 18 char versions, so the sheet can use either
        existingRecurringDonationById.put(c.id, c);
        existingRecurringDonationById.put(c.id.substring(0, 15), c);
      });
    }

    List<String> opportunityIds = importEvents.stream().map(e -> e.opportunityId)
        .filter(opportunityId -> !Strings.isNullOrEmpty(opportunityId)).distinct().toList();
    Map<String, CrmDonation> existingOpportunitiesById = new HashMap<>();
    if (!opportunityIds.isEmpty()) {
      crmService.getDonationsByIds(opportunityIds, opportunityCustomFields).forEach(c -> {
        // cache both the 15 and 18 char versions, so the sheet can use either
        existingOpportunitiesById.put(c.id, c);
        existingOpportunitiesById.put(c.id.substring(0, 15), c);
      });
    }

    Optional<String> opportunityExtRefKey = importEvents.get(0).raw.keySet().stream()
        .filter(k -> k.startsWith("Opportunity ExtRef ")).findFirst();
    Map<String, CrmDonation> existingOpportunitiesByExtRefId = new HashMap<>();
    if (opportunityExtRefKey.isPresent()) {
      List<String> opportunityExtRefIds = importEvents.stream().map(e -> e.raw.get(opportunityExtRefKey.get()))
          .filter(s -> !Strings.isNullOrEmpty(s)).distinct().toList();
      if (!opportunityExtRefIds.isEmpty()) {
        String fieldName = opportunityExtRefKey.get().replace("Opportunity ExtRef ", "");
        crmService.getDonationsByUniqueField(fieldName, opportunityExtRefIds, opportunityCustomFields)
            .forEach(c -> existingOpportunitiesByExtRefId.put(c.fieldFetcher.apply(fieldName).toString(), c));
      }
    }

    Set<String> seenRelationships = new HashSet<>();
    if (env.getConfig().salesforce.npsp) {
      List<SObject> relationships = crmService.queryListAutoPaged("SELECT npe5__Contact__c, npe5__Organization__c FROM npe5__Affiliation__c WHERE npe5__Contact__c!='' AND npe5__Organization__c!=''");
      for (SObject relationship : relationships) {
        String from = relationship.getField("npe5__Contact__c");
        String to = relationship.getField("npe5__Organization__c");
        seenRelationships.add(from + "::" + to);
        seenRelationships.add(to + "::" + from);
      }
    } else {
      List<SObject> relationships = crmService.queryListAutoPaged("SELECT ContactId, AccountId FROM AccountContactRelation WHERE ContactId!='' AND AccountId!=''");
      for (SObject relationship : relationships) {
        String from = relationship.getField("ContactId");
        String to = relationship.account.id;
        seenRelationships.add(from + "::" + to);
        seenRelationships.add(to + "::" + from);
      }
    }

    // we use by-id maps for batch inserts/updates, since the sheet can contain duplicate contacts/accounts
    // (especially when importing opportunities)
    List<String> batchInsertContacts = new ArrayList<>();
    List<String> batchUpdateContacts = new ArrayList<>();
    List<String> batchUpdateOpportunities = new ArrayList<>();
    List<String> batchUpdateRecurringDonations = new ArrayList<>();

    boolean hasAccountColumns = importEvents.stream().flatMap(e -> e.raw.entrySet().stream())
        .anyMatch(entry -> entry.getKey().startsWith("Account") && !Strings.isNullOrEmpty(entry.getValue()));
    boolean hasContactColumns = importEvents.stream().flatMap(e -> e.raw.entrySet().stream())
        .anyMatch(entry -> entry.getKey().startsWith("Contact") && !Strings.isNullOrEmpty(entry.getValue()));
    boolean hasContactOrgColumns = importEvents.stream().anyMatch(e -> !e.contactOrganizations.isEmpty());

    boolean hasOppLookups = importEvents.stream().anyMatch(e -> e.opportunityDate != null || e.opportunityId != null);
    boolean hasRdLookups = importEvents.stream().anyMatch(e -> e.recurringDonationAmount != null || e.recurringDonationId != null);
    boolean hasCampaignLookups = importEvents.stream().anyMatch(e ->
        !e.contactCampaignIds.isEmpty() || !e.contactCampaignNames.isEmpty() || !e.accountCampaignIds.isEmpty() || !e.accountCampaignNames.isEmpty());

    // For the following contexts, we unfortunately can't use batch inserts/updates of accounts/contacts.
    // Opportunity/RD inserts, 1..n Organization affiliations, Campaign updates
    // TODO: We probably *can*, but the code will be rather complex to manage the variety of batch actions paired with CSV rows.
    boolean nonBatchMode = hasOppLookups || hasRdLookups || hasCampaignLookups || hasContactOrgColumns;

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

    int eventsSize = importEvents.size();
    for (int _i = 0; _i < eventsSize * 2; _i++) {
      // TODO: Not sure if this setup is clever or hacky...
      boolean secondPass = _i >= eventsSize;
      if (_i == eventsSize) {
        crmService.batchFlush();
      }
      int i = _i % eventsSize;

      CrmImportEvent importEvent = importEvents.get(i);

      if (secondPass && !importEvent.secondPass) {
        continue;
      }

      env.logJobInfo("import processing contacts/account on row {} of {}", i + 2, eventsSize + 1);

      // Special case. Unlike the other "hasLookup" fields that are defined outside of the loop, we need to know if
      // this row actually has values for those fields. In some imports, organizations with no contacts are mixed
      // in with rows that have contacts. The column headers exist, but if there are no values, we assume
      // account-only import for that individual row. Not an all-or-nothing situation.
      boolean hasContactExtRef = contactExtRefKey.isPresent() && !Strings.isNullOrEmpty(importEvent.raw.get(contactExtRefKey.get()));
      boolean hasContactLookups = !Strings.isNullOrEmpty(importEvent.contactId)
          || importEvent.hasEmail()
          || !Strings.isNullOrEmpty(importEvent.contactLastName) || hasContactExtRef;

      CrmAccount account = null;
      CrmContact contact = null;

      // A few situations have come up where there were not cleanly-split first vs. last name columns, but instead a
      // "salutation" (firstname) and then a full name. Allow users to provide the former as the firstname
      // and the latter as the "lastname", but clean it up. This must happen before the first names are split up, below!
      if (!Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(importEvent.contactLastName)
          && importEvent.contactLastName.contains(importEvent.contactFirstName)) {
        // TODO: The above may still be important, but this introduces bugs. Examples: Anonymous as the first and last
        //  name (last name will be stripped to empty string), real last names that contain a first name (Brett Bretterson), etc.
//        importEvent.contactLastName = importEvent.contactLastName.replace(importEvent.contactFirstName, "").trim();
      }

      // Deep breath. It gets weird.

      // If we're in the second pass, we already know we need to insert the contact.
      if (secondPass) {
        account = upsertExistingAccountByName(importEvent, existingAccountsById, existingAccountsByName,
            accountExtRefKey, accountExtRefFieldName, existingAccountsByExtRef, hasAccountColumns, crmService);

        contact = insertBulkImportContact(importEvent, account, batchInsertContacts, existingContactsByEmail,
            existingContactsByName, contactExtRefFieldName, existingContactsByExtRef, nonBatchMode, crmService);
      }
      // If we're in account-only mode (we have no contact info to match against):
      else if (hasAccountColumns && (!hasContactColumns || !hasContactLookups) && !Strings.isNullOrEmpty(importEvent.account.name)) {
        account = upsertExistingAccountByName(importEvent, existingAccountsById, existingAccountsByName,
            accountExtRefKey, accountExtRefFieldName, existingAccountsByExtRef, hasAccountColumns, crmService);
      }
      // If the explicit Contact ID was given and the contact actually exists, update.
      else if (!Strings.isNullOrEmpty(importEvent.contactId) && existingContactsById.containsKey(importEvent.contactId)) {
        CrmContact existingContact = existingContactsById.get(importEvent.contactId);

        if (account == null) {
          String accountId = existingContact.account.id;
          if (!Strings.isNullOrEmpty(accountId)) {
            CrmAccount existingAccount = existingContact.account;
            account = updateBulkImportAccount(existingAccount, importEvent.account, importEvent.raw, "Account",
                hasAccountColumns, crmService);
          }
        }

        // use accountId, not account.id -- if the contact was recently imported by this current process,
        // the contact.account child relationship will not yet exist in the existingContactsById map
        contact = updateBulkImportContact(existingContact, account, importEvent, batchUpdateContacts, crmService);
      }
      // Similarly, if we have an external ref ID, check that next.
      else if (contactExtRefKey.isPresent() && existingContactsByExtRef.containsKey(importEvent.raw.get(contactExtRefKey.get()))) {
        CrmContact existingContact = existingContactsByExtRef.get(importEvent.raw.get(contactExtRefKey.get()));

        if (account == null) {
          String accountId = existingContact.account.id;
          if (!Strings.isNullOrEmpty(accountId)) {
            CrmAccount existingAccount = existingContact.account;
            account = updateBulkImportAccount(existingAccount, importEvent.account, importEvent.raw, "Account",
                hasAccountColumns, crmService);
          }
        }

        // use accountId, not account.id -- if the contact was recently imported by this current process,
        // the contact.account child relationship will not yet exist in the existingContactsByExtRefId map
        contact = updateBulkImportContact(existingContact, account, importEvent, batchUpdateContacts, crmService);
      }
      // Else if a contact already exists with the given email address, update.
      else if (importEvent.hasEmail() && findExistingContactByEmail(importEvent, existingContactsByEmail).isPresent()) {
        // TODO: do this once further up?
        CrmContact existingContact = findExistingContactByEmail(importEvent, existingContactsByEmail).get();
        if (account == null) {
          String accountId = existingContact.account.id;
          if (!Strings.isNullOrEmpty(accountId)) {
            CrmAccount existingAccount = existingContact.account;
            account = updateBulkImportAccount(existingAccount, importEvent.account, importEvent.raw, "Account",
                hasAccountColumns, crmService);
          }
        }

        // use accountId, not account.id -- if the contact was recently imported by this current process,
        // the contact.account child relationship will not yet exist in the existingContactsByEmail map
        contact = updateBulkImportContact(existingContact, account, importEvent, batchUpdateContacts, crmService);
      }
      // If we have a first and last name, try searching for an existing contact by name.
      // Only do this if we can match against street address or phone number as well. Simply by-name is too risky.
      // Better to allow duplicates than to overwrite records.
      // If 1 match, update. If 0 matches, insert. If 2 or more matches, skip completely out of caution.
      else if (!Strings.isNullOrEmpty(importEvent.contactFirstName) && !Strings.isNullOrEmpty(importEvent.contactLastName)
          && existingContactsByName.containsKey(importEvent.contactFirstName.toLowerCase(Locale.ROOT) + " " + importEvent.contactLastName.toLowerCase(Locale.ROOT))) {
        List<CrmContact> existingContacts = existingContactsByName.get(importEvent.contactFirstName.toLowerCase(Locale.ROOT) + " " + importEvent.contactLastName.toLowerCase()).stream()
            .filter(c -> {
              // If the SFDC record has no address or phone at all, allow the by-name match. It might seem like this
              // somewhat defeats the purpose, but we're running into situations where basic records were originally
              // created with extremely bare info.
              if (c.mailingAddress.street == null && c.account.billingAddress.street == null
                  && c.account.mailingAddress.street == null
                  && c.homePhone == null && c.mobilePhone == null && c.workPhone == null) {
                return true;
              }

              // To make matching simpler, since home phone on one side could = mobile phone on the other side
              // and billing address on one side could match the mailing address on the other side, we cram
              // all options from each side into a list. Then, simply look for an intersection of both lists as a match.

              // make the address checks a little more resilient by removing all non-alphanumerics
              // ex: 123 Main St. != 123 Main St --> 123MainSt == 123MainSt

              List<String> list1 = Stream.of(
                  alphanumericOnly(importEvent.contactMailingStreet),
                  alphanumericOnly(importEvent.account.billingAddress.street),
                  alphanumericOnly(importEvent.account.mailingAddress.street),
                  alphanumericOnly(importEvent.originalStreet),
                  numericOnly(importEvent.contactHomePhone),
                  numericOnly(importEvent.contactMobilePhone),
                  numericOnly(importEvent.contactPhone)
              ).filter(s -> !Strings.isNullOrEmpty(s)).collect(Collectors.toList()); // mutable list

              List<String> list2 = Stream.of(
                  alphanumericOnly(c.mailingAddress.street),
                  alphanumericOnly(c.account.billingAddress.street),
                  alphanumericOnly(c.account.mailingAddress.street),
                  numericOnly(c.homePhone),
                  numericOnly(c.mobilePhone),
                  numericOnly(c.workPhone)
              ).filter(s -> !Strings.isNullOrEmpty(s)).collect(Collectors.toList()); // mutable list

              list1.retainAll(list2);
              return !list1.isEmpty();
            }).sorted(Comparator.comparing(c -> Utils.getCalendarFromDateTimeString(c.getField("CreatedDate")))).toList();

        if (existingContacts.isEmpty()) {
          importEvent.secondPass = true;
          continue;
        } else {
          CrmContact existingContact = existingContacts.get(0);

          if (account == null) {
            String accountId = existingContact.account.id;
            if (!Strings.isNullOrEmpty(accountId)) {
              CrmAccount existingAccount = existingContact.account;
              account = updateBulkImportAccount(existingAccount, importEvent.account, importEvent.raw, "Account",
                  hasAccountColumns, crmService);
            }
          }

          contact = updateBulkImportContact(existingContact, account, importEvent, batchUpdateContacts, crmService);
        }
      }
      // Otherwise, abandon all hope and insert, but only if we at least have a field to use as a lookup.
      else if (hasContactLookups) {
        importEvent.secondPass = true;
        continue;
      }

      if (account != null) {
        for (String campaignId : importEvent.accountCampaignIds) {
          if (!Strings.isNullOrEmpty(campaignId)) {
            crmService.addAccountToCampaign(account.id, campaignId, true);
          }
        }

        for (String campaignName : importEvent.accountCampaignNames) {
          if (!Strings.isNullOrEmpty(campaignName)) {
            if (campaignNameToId.containsKey(campaignName.toLowerCase(Locale.ROOT))) {
              crmService.addAccountToCampaign(account.id, campaignNameToId.get(campaignName.toLowerCase(Locale.ROOT)), true);
            } else {
              String campaignId = crmService.insertCampaign(new CrmCampaign(null, campaignName));
              campaignNameToId.put(campaignId, campaignName);
              crmService.addAccountToCampaign(account.id, campaignId, true);
            }
          }
        }

        if (!Strings.isNullOrEmpty(importEvent.accountNote)) {
          CrmNote crmNote = new CrmNote(account.id, null, importEvent.accountNote, Calendar.getInstance());
          crmService.insertNote(crmNote);
        }
      }

      if (contact != null) {
        for (String campaignId : importEvent.contactCampaignIds) {
          if (!Strings.isNullOrEmpty(campaignId)) {
            crmService.addContactToCampaign(contact.id, campaignId, true);
          }
        }

        for (String campaignName : importEvent.contactCampaignNames) {
          if (!Strings.isNullOrEmpty(campaignName)) {
            if (campaignNameToId.containsKey(campaignName.toLowerCase(Locale.ROOT))) {
              crmService.addContactToCampaign(contact.id, campaignNameToId.get(campaignName.toLowerCase(Locale.ROOT)), true);
            } else {
              String campaignId = crmService.insertCampaign(new CrmCampaign(null, campaignName));
              campaignNameToId.put(campaignId, campaignName);
              crmService.addContactToCampaign(contact.id, campaignId, true);
            }
          }
        }

        if (!Strings.isNullOrEmpty(importEvent.contactNote)) {
          CrmNote crmNote = new CrmNote(contact.id, null, importEvent.contactNote, Calendar.getInstance());
          crmService.insertNote(crmNote);
        }
      }

      if (hasContactOrgColumns && contact != null) {
        importOrgAffiliations(contact, existingAccountsById, existingAccountsByExtRef, existingAccountsByName,
            seenRelationships, importEvent, crmService);
      }

      if (nonBatchMode) {
        nonBatchAccountIds.set(i, account != null ? account.id : null);
        nonBatchContactIds.set(i, contact != null ? contact.id : null);
      }

      env.logJobInfo("Imported {} contacts", (i + 1));
    }

    crmService.batchFlush();

    if (hasRdLookups) {
      // TODO: Won't this loop process the same RD over and over each time it appears in an Opp row? Keep track of "visited"?
      for (int i = 0; i < eventsSize; i++) {
        CrmImportEvent importEvent = importEvents.get(i);

        env.logJobInfo("import processing recurring donations on row {} of {}", i + 2, eventsSize + 1);

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
          recurringDonation.setField("Npe03__Recurring_Donation_Campaign__c", importEvent.recurringDonationCampaignId);
        } else if (!Strings.isNullOrEmpty(importEvent.opportunityCampaignName) && campaignNameToId.containsKey(importEvent.opportunityCampaignName.toLowerCase(Locale.ROOT))) {
          recurringDonation.setField("Npe03__Recurring_Donation_Campaign__c", campaignNameToId.get(importEvent.opportunityCampaignName.toLowerCase(Locale.ROOT)));
        }

        if (!Strings.isNullOrEmpty(importEvent.recurringDonationId)) {
          recurringDonation.id = importEvent.recurringDonationId;
          setBulkImportRecurringDonationFields(recurringDonation, existingRecurringDonationById.get(importEvent.recurringDonationId), importEvent);
          if (!batchUpdateRecurringDonations.contains(importEvent.recurringDonationId)) {
            batchUpdateRecurringDonations.add(importEvent.recurringDonationId);
            crmService.batchUpdateRecurringDonation(recurringDonation);
          }
        } else {
          // If the account and contact upserts both failed, avoid creating an orphaned rd.
          if (nonBatchAccountIds.get(i) == null && nonBatchContactIds.get(i) == null) {
            continue;
          }

          setBulkImportRecurringDonationFields(recurringDonation, null, importEvent);
          crmService.batchInsertRecurringDonation(recurringDonation);
        }

        env.logJobInfo("Imported {} recurring donations", (i + 1));
      }

      crmService.batchFlush();
    }

    if (hasOppLookups) {
      for (int i = 0; i < eventsSize; i++) {
        CrmImportEvent importEvent = importEvents.get(i);

        env.logJobInfo("import processing opportunities on row {} of {}", i + 2, eventsSize + 1);

        CrmDonation opportunity = new CrmDonation();

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
            crmService.batchUpdateOpportunity(opportunity);
          }
        } else if (opportunityExtRefKey.isPresent() && existingOpportunitiesByExtRefId.containsKey(importEvent.raw.get(opportunityExtRefKey.get()))) {
          CrmDonation existingOpportunity = existingOpportunitiesByExtRefId.get(importEvent.raw.get(opportunityExtRefKey.get()));

          opportunity.id = existingOpportunity.id;
          setBulkImportOpportunityFields(opportunity, existingOpportunity, importEvent);
          if (!batchUpdateOpportunities.contains(opportunity.id)) {
            batchUpdateOpportunities.add(opportunity.id);
            crmService.batchUpdateOpportunity(opportunity);
          }
        } else {
          // If the account and contact upserts both failed, avoid creating an orphaned opp.
          if (nonBatchAccountIds.get(i) == null && nonBatchContactIds.get(i) == null) {
            env.logJobInfo("skipping opp {} import, due to account/contact failure", i + 2);
            continue;
          }

          setBulkImportOpportunityFields(opportunity, null, importEvent);

          crmService.batchInsertOpportunity(opportunity);
        }
        env.logJobInfo("Imported {} opportunities", (i + 1));
      }

      crmService.batchFlush();
    }

    env.logJobInfo("bulk import complete");
  }

  protected CrmAccount upsertExistingAccountByName(
      CrmImportEvent importEvent,
      Map<String, CrmAccount> existingAccountsById,
      Multimap<String, CrmAccount> existingAccountsByName,
      Optional<String> accountExtRefKey,
      Optional<String> accountExtRefFieldName,
      Map<String, CrmAccount> existingAccountsByExtRef,
      boolean hasAccountColumns,
      CrmService crmService
  ) throws Exception {
    CrmAccount account = null;
    if (!Strings.isNullOrEmpty(importEvent.account.id)) {
      account = existingAccountsById.get(importEvent.account.id);
    }
    if (account == null && (accountExtRefKey.isPresent() && !Strings.isNullOrEmpty(importEvent.raw.get(accountExtRefKey.get())))) {
      account = existingAccountsByExtRef.get(importEvent.raw.get(accountExtRefKey.get()));
    }
    if (account == null && !Strings.isNullOrEmpty(importEvent.account.name)) {
      account = existingAccountsByName.get(importEvent.account.name.toLowerCase(Locale.ROOT)).stream().findFirst().orElse(null);
    }

    if (account == null) {
      account = insertBulkImportAccount(importEvent.account, importEvent.raw, existingAccountsByName,
          accountExtRefFieldName, existingAccountsByExtRef, "Account", hasAccountColumns, crmService);
    } else {
      account = updateBulkImportAccount(account, importEvent.account, importEvent.raw, "Account", true, crmService);
    }

    return account;
  }

  protected CrmAccount updateBulkImportAccount(CrmAccount existingAccount, CrmAccount crmAccount, Map<String, String> raw,
      String columnPrefix, boolean hasAccountColumns, CrmService crmService) throws Exception {
    // TODO: Odd situation. When insertBulkImportContact creates a contact, it's also creating an Account, sets the
    //  AccountId on the Contact and then adds the Contact to existingContactsByEmail so we can reuse it. But when
    //  we encounter the contact again, the code upstream attempts to update the Account. 1) We don't need to, since it
    //  was just created, and 2) we can't set Contact.Account since that child can't exist when the contact is inserted,
    //  and that insert might happen later during batch processing.
    if (existingAccount == null) {
      return null;
    }

    if (!hasAccountColumns) {
      return existingAccount;
    }

    CrmAccount account = new CrmAccount();
    account.id = existingAccount.id;

    setBulkImportAccountFields(account, existingAccount, crmAccount, columnPrefix, raw);

    crmService.batchUpdateAccount(account);

    return account;
  }

  protected CrmAccount insertBulkImportAccount(
      CrmAccount crmAccount,
      Map<String, String> raw,
      Multimap<String, CrmAccount> existingAccountsByName,
      Optional<String> accountExtRefFieldName,
      Map<String, CrmAccount> existingAccountsByExtRef,
      String columnPrefix,
      boolean hasAccountColumns,
      CrmService crmService
  ) throws Exception {
    // TODO: This speeds up, but we have some clients (most recent one: TER) where household auto generation
    //  is kicking off workflows (TER: Primary Contact Changed Process) that nail CPU/query limits. If we want
    //  to use this, we might need to dial back the batch sizes...
    if (!hasAccountColumns) {
      return null;
    }

    CrmAccount account = new CrmAccount();

    setBulkImportAccountFields(account, null, crmAccount, columnPrefix, raw);

    String accountId = crmService.insertAccount(account);
    account.id = accountId;

    if (!Strings.isNullOrEmpty(crmAccount.name)) {
      existingAccountsByName.put(crmAccount.name.toLowerCase(Locale.ROOT), account);
    }
    if (accountExtRefFieldName.isPresent() && !Strings.isNullOrEmpty((String) account.fieldFetcher.apply(accountExtRefFieldName.get()))) {
      existingAccountsByExtRef.put((String) account.fieldFetcher.apply(accountExtRefFieldName.get()), account);
    }

    return account;
  }

  protected void setBulkImportAccountFields(CrmAccount account, CrmAccount existingAccount, CrmAccount crmAccount,
      String columnPrefix, Map<String, String> raw) {
    if (!Strings.isNullOrEmpty(crmAccount.recordTypeId)) {
      account.recordTypeId = crmAccount.recordTypeId;
    } else if (!Strings.isNullOrEmpty(crmAccount.recordTypeName)) {
      account.recordTypeId = recordTypeNameToIdCache.get(crmAccount.recordTypeName);
    }

    // IMPORTANT: Only do this if this is an insert, IE existingAccount == null. Setting an explicit name on an
    // UPDATE will auto set npo02__SYSTEM_CUSTOM_NAMING__c='NAME', which effectively disabled NPSP's naming rules.
    if (existingAccount == null) {
      if (Strings.isNullOrEmpty(crmAccount.name)) {
        // Likely a household and likely to be overwritten by NPSP's household naming rules.
        account.name = "Household";
      } else {
        account.name = crmAccount.name;
      }
    }

    setCustomBulkValue(account, "BillingStreet", crmAccount.billingAddress.street);
    setCustomBulkValue(account, "BillingCity", crmAccount.billingAddress.city);
    setCustomBulkValue(account, "BillingState", crmAccount.billingAddress.state);
    setCustomBulkValue(account, "BillingPostalCode", crmAccount.billingAddress.postalCode);
    setCustomBulkValue(account, "BillingCountry", crmAccount.billingAddress.country);
    setCustomBulkValue(account, "ShippingStreet", crmAccount.mailingAddress.street);
    setCustomBulkValue(account, "ShippingCity", crmAccount.mailingAddress.city);
    setCustomBulkValue(account, "ShippingState", crmAccount.mailingAddress.state);
    setCustomBulkValue(account, "ShippingPostalCode", crmAccount.mailingAddress.postalCode);
    setCustomBulkValue(account, "ShippingCountry", crmAccount.mailingAddress.country);

    setCustomBulkValue(account, "Description", crmAccount.description);
    setCustomBulkValue(account, "OwnerId", crmAccount.ownerId);
    setCustomBulkValue(account, "Phone", crmAccount.phone);
    setCustomBulkValue(account, "Type", crmAccount.type);
    setCustomBulkValue(account, "Website", crmAccount.website);

    setBulkImportCustomFields(account, existingAccount, columnPrefix, raw);
  }

  protected Optional<CrmContact> findExistingContactByEmail(CrmImportEvent importEvent, Multimap<String, CrmContact> existingContactsByEmail) {
    List<String> emails = importEvent.getAllContactEmails().stream()
        .filter(email -> !Strings.isNullOrEmpty(email))
        .map(email -> email.toLowerCase(Locale.ROOT))
        .filter(existingContactsByEmail::containsKey)
        .toList();
    return emails.stream()
        .map(email -> existingContactsByEmail.get(email))
        .flatMap(Collection::stream)
        .min(Comparator.comparing(c -> Utils.getCalendarFromDateTimeString(c.getField("CreatedDate"))));
  }

  protected CrmContact updateBulkImportContact(CrmContact existingContact, CrmAccount account, CrmImportEvent importEvent,
      List<String> bulkUpdateContacts, CrmService crmService) throws Exception {
    CrmContact contact = new CrmContact();
    contact.id = existingContact.id;
    if (account != null) {
      contact.account.id = account.id;
    }

    contact.firstName = importEvent.contactFirstName;
    contact.lastName = importEvent.contactLastName;

    setBulkImportContactFields(contact, existingContact, importEvent);
    if (!bulkUpdateContacts.contains(existingContact.id)) {
      bulkUpdateContacts.add(existingContact.id);
      crmService.batchUpdateContact(contact);
    }

    return contact;
  }

  protected CrmContact insertBulkImportContact(
      CrmImportEvent importEvent,
      CrmAccount account,
      List<String> bulkInsertContacts,
      Multimap<String, CrmContact> existingContactsByEmail,
      Multimap<String, CrmContact> existingContactsByName,
      Optional<String> contactExtRefFieldName,
      Map<String, CrmContact> existingContactsByExtRef,
      boolean nonBatchMode,
      CrmService crmService
  ) throws Exception {
    CrmContact contact = new CrmContact();

    String fullName = null;
    // last name is required
    if (Strings.isNullOrEmpty(importEvent.contactLastName)) {
      contact.lastName = "Anonymous";
    } else {
      contact.firstName = importEvent.contactFirstName;
      contact.lastName = importEvent.contactLastName;
      fullName = importEvent.contactFirstName + " " + importEvent.contactLastName;
    }

    boolean isAnonymous = "Anonymous".equalsIgnoreCase(contact.lastName);

    setBulkImportContactFields(contact, null, importEvent);

    if (account != null) {
      contact.account.id = account.id;
    }

    if (nonBatchMode) {
      String contactId = crmService.insertContact(contact);
      contact.id = contactId;
    } else {
      // TODO: We need to prevent contacts that appear on multiple rows (especially for opportunity imports) from being
      //  created over and over. Incorporate id and extref too? What about common first/last names? Allow
      //  that since duplicates within the SAME SHEET are likely the same person?
      // TODO: Minimally need to check the other email fields.
      String key = null;
      if (!Strings.isNullOrEmpty(contact.email)) {
        key = contact.email;
      } else if (!isAnonymous) {
        key = contact.firstName + " " + contact.lastName;
      }

      if (key == null) {
        crmService.batchInsertContact(contact);
      } else if (!bulkInsertContacts.contains(key)) {
        bulkInsertContacts.add(key);
        crmService.batchInsertContact(contact);
      }
    }

    // Since we hold maps in memory and don't requery them. Add entries as we go to prevent duplicate inserts.
    if (!Strings.isNullOrEmpty(contact.email)) {
      existingContactsByEmail.put(contact.email.toLowerCase(Locale.ROOT), contact);
    }
    if (!Strings.isNullOrEmpty(contact.getField("npe01__HomeEmail__c"))) {
      existingContactsByEmail.put(contact.getField("npe01__HomeEmail__c").toString().toLowerCase(Locale.ROOT), contact);
    }
    if (!Strings.isNullOrEmpty(contact.getField("npe01__WorkEmail__c"))) {
      existingContactsByEmail.put(contact.getField("npe01__WorkEmail__c").toString().toLowerCase(Locale.ROOT), contact);
    }
    if (!Strings.isNullOrEmpty(contact.getField("npe01__AlternateEmail__c"))) {
      existingContactsByEmail.put(contact.getField("npe01__AlternateEmail__c").toString().toLowerCase(Locale.ROOT), contact);
    }

    if (!isAnonymous && !Strings.isNullOrEmpty(fullName)) {
      existingContactsByName.put(fullName.toLowerCase(Locale.ROOT), contact);
    }
    if (contactExtRefFieldName.isPresent() && !Strings.isNullOrEmpty((String) contact.fieldFetcher.apply(contactExtRefFieldName.get()))) {
      existingContactsByExtRef.put((String) contact.fieldFetcher.apply(contactExtRefFieldName.get()), contact);
    }

    return contact;
  }

  protected void setBulkImportContactFields(CrmContact contact, CrmContact existingContact, CrmImportEvent importEvent) {
    if (!Strings.isNullOrEmpty(importEvent.contactRecordTypeId)) {
      contact.recordTypeId = importEvent.contactRecordTypeId;
    } else if (!Strings.isNullOrEmpty(importEvent.contactRecordTypeName)) {
      contact.recordTypeId = recordTypeNameToIdCache.get(importEvent.contactRecordTypeName);
    }

    setCustomBulkValue(contact, "Salutation", importEvent.contactSalutation);
    setCustomBulkValue(contact, "Description", importEvent.contactDescription);
    setCustomBulkValue(contact, "MailingStreet", importEvent.contactMailingStreet);
    setCustomBulkValue(contact, "MailingCity", importEvent.contactMailingCity);
    setCustomBulkValue(contact, "MailingState", importEvent.contactMailingState);
    setCustomBulkValue(contact, "MailingPostalCode", importEvent.contactMailingZip);
    setCustomBulkValue(contact, "MailingCountry", importEvent.contactMailingCountry);
    setCustomBulkValue(contact, "HomePhone", importEvent.contactHomePhone);
    setCustomBulkValue(contact, "MobilePhone", importEvent.contactMobilePhone);
    setCustomBulkValue(contact, "Phone", importEvent.contactPhone);
    if (env.getConfig().salesforce.npsp) {
      setCustomBulkValue(contact, "npe01__WorkPhone__c", importEvent.contactWorkPhone);
    }

    if (!Strings.isNullOrEmpty(importEvent.contactEmail) && !"na".equalsIgnoreCase(importEvent.contactEmail) && !"n/a".equalsIgnoreCase(importEvent.contactEmail)) {
      // Some sources provide comma separated lists. Simply use the first one.
      String email = importEvent.contactEmail.split("[,;\\s]+")[0];
      setCustomBulkValue(contact, "Email", email);
    }

    if (!Strings.isNullOrEmpty(importEvent.contactPersonalEmail) && !"na".equalsIgnoreCase(importEvent.contactPersonalEmail) && !"n/a".equalsIgnoreCase(importEvent.contactPersonalEmail) && env.getConfig().salesforce.npsp) {
      // Some sources provide comma separated lists. Simply use the first one.
      String email = importEvent.contactPersonalEmail.split("[,;\\s]+")[0];
      setCustomBulkValue(contact, "npe01__HomeEmail__c", email);
    }

    if (!Strings.isNullOrEmpty(importEvent.contactWorkEmail) && !"na".equalsIgnoreCase(importEvent.contactWorkEmail) && !"n/a".equalsIgnoreCase(importEvent.contactWorkEmail) && env.getConfig().salesforce.npsp) {
      // Some sources provide comma separated lists. Simply use the first one.
      String workEmail = importEvent.contactWorkEmail.split("[,;\\s]+")[0];
      setCustomBulkValue(contact, "npe01__WorkEmail__c", workEmail);
    }

    if (!Strings.isNullOrEmpty(importEvent.contactOtherEmail) && !"na".equalsIgnoreCase(importEvent.contactOtherEmail) && !"n/a".equalsIgnoreCase(importEvent.contactOtherEmail) && env.getConfig().salesforce.npsp) {
      // Some sources provide comma separated lists. Simply use the first one.
      String otherEmail = importEvent.contactOtherEmail.split("[,;\\s]+")[0];
      setCustomBulkValue(contact, "npe01__AlternateEmail__c", otherEmail);
    }

    if ((existingContact == null || Strings.isNullOrEmpty(existingContact.getField("npe01__PreferredPhone__c"))) && env.getConfig().salesforce.npsp) {
      String customFieldValue = switch (importEvent.contactPhonePreference) {
        case HOME -> "Home";
        case MOBILE -> "Mobile";
        case WORK -> "Work";
        case OTHER -> "Other";
      };
      setCustomBulkValue(contact, "npe01__PreferredPhone__c", customFieldValue);
    }

    if (importEvent.contactOptInEmail != null && importEvent.contactOptInEmail) {
      setCustomBulkValue(contact, env.getConfig().salesforce.fieldDefinitions.emailOptIn, true);
      setCustomBulkValue(contact, env.getConfig().salesforce.fieldDefinitions.emailOptOut, false);
    }
    if (importEvent.contactOptOutEmail != null && importEvent.contactOptOutEmail) {
      setCustomBulkValue(contact, env.getConfig().salesforce.fieldDefinitions.emailOptIn, false);
      setCustomBulkValue(contact, env.getConfig().salesforce.fieldDefinitions.emailOptOut, true);
    }
    if (importEvent.contactOptInSms != null && importEvent.contactOptInSms) {
      setCustomBulkValue(contact, env.getConfig().salesforce.fieldDefinitions.smsOptIn, true);
      setCustomBulkValue(contact, env.getConfig().salesforce.fieldDefinitions.smsOptOut, false);
    }
    if (importEvent.contactOptOutSms != null && importEvent.contactOptOutSms) {
      setCustomBulkValue(contact, env.getConfig().salesforce.fieldDefinitions.smsOptIn, false);
      setCustomBulkValue(contact, env.getConfig().salesforce.fieldDefinitions.smsOptOut, true);
    }

    if ((existingContact == null || Strings.isNullOrEmpty(existingContact.getField("npe01__Preferred_Email__c"))) && env.getConfig().salesforce.npsp) {
      String customFieldValue = switch (importEvent.contactEmailPreference) {
        case PERSONAL -> "Personal";
        case WORK -> "Work";
        case OTHER -> "Alternate";
      };
      setCustomBulkValue(contact, "npe01__Preferred_Email__c", customFieldValue);
    }

    setCustomBulkValue(contact, "OwnerId", importEvent.contactOwnerId);

    setBulkImportCustomFields(contact, existingContact, "Contact", importEvent.raw);
  }

  // TODO: This mostly duplicates the primary import's accounts by-id, by-extref, and by-name. DRY it up?
  protected void importOrgAffiliations(
      CrmContact contact,
      Map<String, CrmAccount> existingAccountsById,
      Map<String, CrmAccount> existingAccountsByExtRef,
      Multimap<String, CrmAccount> existingAccountsByName,
      Set<String> seenRelationships,
      CrmImportEvent importEvent,
      CrmService crmService
  ) throws Exception {
    int size = importEvent.contactOrganizations.size();
    for (int j = 0; j < size; j++) {
      CrmAccount crmOrg = importEvent.contactOrganizations.get(j);
      String role = importEvent.contactOrganizationRoles.get(j);

      int finalJ = j + 1;
      Optional<String> orgExtRefKey = importEvent.raw.keySet().stream()
          .filter(k -> k.startsWith("Organization " + finalJ + " ExtRef ")).distinct().findFirst();
      Optional<String> orgExtRefFieldName = orgExtRefKey.map(k -> k.replace("Organization " + finalJ + " ExtRef ", ""));

      CrmAccount org = null;
      if (!Strings.isNullOrEmpty(crmOrg.id)) {
        CrmAccount existingOrg = existingAccountsById.get(crmOrg.id);

        if (existingOrg != null) {
          org = new CrmAccount();
          org.id = existingOrg.id;

          setBulkImportAccountFields(org, existingOrg, crmOrg, "Organization " + finalJ, importEvent.raw);
          crmService.batchUpdateAccount(org);
        }
      } else if (orgExtRefKey.isPresent() && !Strings.isNullOrEmpty(importEvent.raw.get(orgExtRefKey.get()))) {
        CrmAccount existingOrg = existingAccountsByExtRef.get(importEvent.raw.get(orgExtRefKey.get()));

        if (existingOrg != null) {
          org = new CrmAccount();
          org.id = existingOrg.id;

          setBulkImportAccountFields(org, existingOrg, crmOrg, "Organization " + finalJ, importEvent.raw);
          crmService.batchUpdateAccount(org);
        } else {
          if (Strings.isNullOrEmpty(crmOrg.recordTypeId) && Strings.isNullOrEmpty(crmOrg.recordTypeName)) {
            crmOrg.recordTypeName = "Organization";
          }

          // IMPORTANT: We're making an assumption here that if an ExtRef is provided, the expectation is that the
          // account already exists (which wasn't true, above) OR that it should be created. REGARDLESS of the contact's
          // current account, if any. We're opting to create the new account, update the contact's account ID, and
          // possibly abandon its old account (if it exists).
          // TODO: This was mainly due to CLHS' original FACTS migration that created isolated households or, worse,
          //  combined grandparents into the student's household. Raiser's Edge has the correct relationships and
          //  households, so we're using this to override the past.
          org = insertBulkImportAccount(crmOrg, importEvent.raw, existingAccountsByName, orgExtRefFieldName,
              existingAccountsByExtRef, "Organization " + finalJ, true, crmService);
        }
      } else {
        CrmAccount existingOrg = existingAccountsByName.get(crmOrg.name.toLowerCase(Locale.ROOT)).stream().findFirst().orElse(null);

        if (existingOrg == null) {
          if (Strings.isNullOrEmpty(crmOrg.recordTypeId) && Strings.isNullOrEmpty(crmOrg.recordTypeName)) {
            crmOrg.recordTypeName = "Organization";
          }

          org = insertBulkImportAccount(crmOrg, importEvent.raw, existingAccountsByName, orgExtRefFieldName,
              existingAccountsByExtRef, "Organization " + finalJ, true, crmService);
          existingAccountsByName.put(crmOrg.name.toLowerCase(Locale.ROOT), org);
        } else {
          org = updateBulkImportAccount(existingOrg, crmOrg, importEvent.raw, "Organization " + finalJ, true, crmService);
        }
      }

      if (env.getConfig().salesforce.npsp) {
        if (seenRelationships.contains(contact.id + "::" + org.id) || seenRelationships.contains(org.id + "::" + contact.id)) {
          continue;
        }

        SObject affiliation = new SObject("npe5__Affiliation__c");
        affiliation.setField("npe5__Contact__c", contact.id);
        affiliation.setField("npe5__Organization__c", org.id);
        affiliation.setField("npe5__Status__c", "Current");
        affiliation.setField("npe5__Role__c", role);
        crmService.batchInsert(affiliation);
      } else {
        // In commercial SFDC, a contact is considered "private" if it does not have a primary account (AccountId).
        // So in our setup, we need to ensure Org #1 is set as AccountId, if and only if the Contact does not already
        // have AccountId set. But note that AccountId will automatically create an AccountContactRelation, but without
        // a defined role. So we still need to update it to set that role.
        // That's confusing. Examples, assuming we're importing Contact (C) and Organization 1 (O1)
        // - C has no AccountId. Set it to O1, allow the AccountContactRelation to be created, then update it to set the role.
        // - C has an AccountId and it's already set to O1. Update the AccountContactRelation to set the role, if it's not already set.
        // - C has an AccountId and it's a different Org. Leave AccountId alone and create the new AccountContactRelation.

        if (Strings.isNullOrEmpty(contact.account.id)) {
          // Private contact. Set AccountId and update the relation's role.

          CrmContact contactUpdate = new CrmContact();
          contactUpdate.id = contact.id;
          contactUpdate.account.id = org.id;
          // cannot batch -- need to wait for the update to finish so the relation (below) is available
          crmService.updateContact(contactUpdate);

          if (!Strings.isNullOrEmpty(role)) {
            Optional<SObject> relation = crmService.querySingle("SELECT Id FROM AccountContactRelation WHERE ContactId='" + contact.id + "' AND AccountId='" + org.id + "'");
            if (relation.isPresent()) {
              SObject relationUpdate = new SObject("AccountContactRelation");
              relationUpdate.id = relation.get().id;
              // TODO: The default Roles field is a multiselect picklist. We nearly ALWAYS create this custom, free-text
              //  field instead. But we're obviously making an assumption here...
              relationUpdate.setField("Role__c", role);
              crmService.batchUpdate(relationUpdate);
            } else {
              env.logJobError("AccountContactRelation could not be found for {} and {}", contact.id, org.id);
            }
          }
        } else {
          if (!Strings.isNullOrEmpty(role)) {
            Optional<SObject> relation = crmService.querySingle("SELECT Id, Role__c FROM AccountContactRelation WHERE ContactId='" + contact.id + "' AND AccountId='" + org.id + "'");
            if (relation.isPresent()) {
              if (Strings.isNullOrEmpty(relation.get().getField("Role__c"))) {
                SObject relationUpdate = new SObject("AccountContactRelation");
                relationUpdate.id = relation.get().id;
                // TODO: The default Roles field is a multiselect picklist. We nearly ALWAYS create this custom, free-text
                //  field instead. But we're obviously making an assumption here...
                relationUpdate.setField("Role__c", role);
                crmService.batchUpdate(relationUpdate);
              }
            } else {
              SObject affiliation = new SObject("AccountContactRelation");
              affiliation.setField("ContactId", contact.id);
              affiliation.setField("AccountId", org.id);
              affiliation.setField("IsActive", true);
              // TODO: The default Roles field is a multiselect picklist. We nearly ALWAYS create this custom, free-text
              //  field instead. But we're obviously making an assumption here...
              affiliation.setField("Role__c", role);
              crmService.batchInsert(affiliation);
            }
          }
        }
      }

      seenRelationships.add(contact.id + "::" + org.id);
      seenRelationships.add(org.id + "::" + contact.id);
    }
  }

  protected void setBulkImportRecurringDonationFields(CrmRecurringDonation recurringDonation,
      CrmRecurringDonation existingRecurringDonation, CrmImportEvent importEvent) {
    if (importEvent.recurringDonationAmount != null) {
      recurringDonation.amount = importEvent.recurringDonationAmount.doubleValue();
    }
    recurringDonation.status = importEvent.recurringDonationStatus;
    recurringDonation.setField("Npe03__Schedule_Type__c", "Multiply By");
    CrmRecurringDonation.Frequency frequency = CrmRecurringDonation.Frequency.fromName(importEvent.recurringDonationInterval);
    if (frequency != null) {
      recurringDonation.frequency = frequency.name();
    }
    recurringDonation.setField("Npe03__Date_Established__c", importEvent.recurringDonationStartDate);
    recurringDonation.setField("Npe03__Next_Payment_Date__c", importEvent.recurringDonationNextPaymentDate);

    if (env.getConfig().salesforce.enhancedRecurringDonations) {
      recurringDonation.setField("npsp__RecurringType__c", "Open");
      // It's a picklist, so it has to be a string and not numeric :(
      recurringDonation.setField("npsp__Day_of_Month__c", importEvent.recurringDonationStartDate.get(Calendar.DAY_OF_MONTH) + "");
    }

    recurringDonation.ownerId = importEvent.recurringDonationOwnerId;

    setBulkImportCustomFields(recurringDonation, existingRecurringDonation, "Recurring Donation", importEvent.raw);
  }

  protected void setBulkImportOpportunityFields(CrmDonation opportunity, CrmDonation existingOpportunity, CrmImportEvent importEvent)
      throws ExecutionException {
    if (!Strings.isNullOrEmpty(importEvent.opportunityRecordTypeId)) {
      opportunity.recordTypeId = importEvent.opportunityRecordTypeId;
    } else if (!Strings.isNullOrEmpty(importEvent.opportunityRecordTypeName)) {
      opportunity.recordTypeId = recordTypeNameToIdCache.get(importEvent.opportunityRecordTypeName);
    }
    if (!Strings.isNullOrEmpty(importEvent.opportunityName)) {
      // 120 is typically the max length
      int length = Math.min(importEvent.opportunityName.length(), 120);
      opportunity.name = importEvent.opportunityName.substring(0, length);
    } else if (existingOpportunity == null || Strings.isNullOrEmpty(existingOpportunity.name)) {
      opportunity.name = importEvent.contactFullName() + " Donation";
    }
    opportunity.description = importEvent.opportunityDescription;
    if (importEvent.opportunityAmount != null) {
      opportunity.amount = importEvent.opportunityAmount.doubleValue();
    }
    if (!Strings.isNullOrEmpty(importEvent.opportunityStageName)) {
      opportunity.status = importEvent.opportunityStageName;
    } else {
      opportunity.status = CrmDonation.Status.SUCCESSFUL;
    }
    opportunity.closeDate = importEvent.opportunityDate;

    opportunity.ownerId = importEvent.opportunityOwnerId;

    setBulkImportCustomFields(opportunity, existingOpportunity, "Opportunity", importEvent.raw);
  }

  protected void setBulkImportCustomFields(CrmRecord record, CrmRecord existingRecord, String columnPrefix, Map<String, String> raw) {
    String customPrefix = columnPrefix + " Custom ";
    raw.entrySet().stream().filter(entry -> entry.getKey().startsWith(customPrefix) && !Strings.isNullOrEmpty(entry.getValue())).forEach(entry -> {
      String key = entry.getKey().replace(customPrefix, "");
      if (key.startsWith("Append")) {
        // appending to a multiselect picklist
        key = key.replace("Append", "").trim();
        appendCustomValue(key, entry.getValue(), record, existingRecord);
      } else {
        setCustomBulkValue(record, key, entry.getValue());
      }
    });

    String extrefPrefix = columnPrefix + " ExtRef ";
    raw.entrySet().stream().filter(entry -> entry.getKey().startsWith(extrefPrefix) && !Strings.isNullOrEmpty(entry.getValue())).forEach(entry -> {
      String key = entry.getKey().replace(extrefPrefix, "");
      if (existingRecord == null || Strings.isNullOrEmpty((String) existingRecord.fieldFetcher.apply(key))) {
        setCustomBulkValue(record, key, entry.getValue());
      }
    });
  }

  // TODO: A bit of a hack, but we're using ; as a separator. That allows us to append to multiselect picklists.
  //  It also works for other fields, like Description text boxes. Only downside: the ; looks a little odd.
  protected void appendCustomValue(String key, String value, CrmRecord record, CrmRecord existingRecord) {
    if (existingRecord != null) {
      String existingValue = (String) existingRecord.fieldFetcher.apply(key);
      if (!Strings.isNullOrEmpty(existingValue)) {
        if (existingValue.contains(value)) {
          // existing values already include the new value, so set the existing value verbatim so that we don't
          // wipe anything out
          value = existingValue;
        } else {
          // existing values did not include the new value, so append it
          value = Strings.isNullOrEmpty(existingValue) ? value : existingValue + ";" + value;
        }
      }
    }
    record.setField(key, value);
  }

  protected void processBulkImportCampaignRecords(List<CrmImportEvent> importEvents, CrmService crmService) throws Exception {
    String[] campaignCustomFields = importEvents.stream().flatMap(e -> e.getCampaignCustomFieldNames().stream()).distinct().toArray(String[]::new);

    List<String> campaignIds = importEvents.stream().map(e -> e.campaignId)
        .filter(campaignId -> !Strings.isNullOrEmpty(campaignId)).distinct().toList();
    Map<String, CrmCampaign> existingCampaignById = new HashMap<>();
    if (!campaignIds.isEmpty()) {
      crmService.getCampaignsByIds(campaignIds, campaignCustomFields).forEach(c -> existingCampaignById.put(c.id, c));
    }

    int eventsSize = importEvents.size();
    for (int i = 0; i < eventsSize; i++) {
      CrmImportEvent importEvent = importEvents.get(i);

      env.logJobInfo("import processing campaigns on row {} of {}", i + 2, eventsSize + 1);

      CrmCampaign campaign = new CrmCampaign();
      campaign.name = importEvent.campaignName;

      if (!Strings.isNullOrEmpty(importEvent.campaignRecordTypeId)) {
        campaign.recordTypeId = importEvent.campaignRecordTypeId;
      } else if (!Strings.isNullOrEmpty(importEvent.campaignRecordTypeName)) {
        campaign.recordTypeId = recordTypeNameToIdCache.get(importEvent.campaignRecordTypeName);
      }

      if (!Strings.isNullOrEmpty(importEvent.campaignId)) {
        campaign.id = importEvent.campaignId;
        setBulkImportCustomFields(campaign, existingCampaignById.get(importEvent.campaignId), "Campaign", importEvent.raw);
        crmService.batchUpdateCampaign(campaign);
      } else {
        setBulkImportCustomFields(campaign, null, "Campaign", importEvent.raw);
        crmService.batchInsertCampaign(campaign);
      }

      env.logJobInfo("Imported {} campaigns", (i + 1));
    }

    crmService.batchFlush();
  }

  // TODO: This is going to be a pain in the butt, but we'll try to dynamically support different data types for custom
  //  fields in bulk imports/updates, without requiring the data type to be provided. This is going to be brittle...
  protected void setCustomBulkValue(CrmRecord record, String key, Object value) {
    if (Strings.isNullOrEmpty(key) || value == null || Strings.isNullOrEmpty(value.toString())) {
      return;
    }

    Calendar c = null;
    try {
      if (key.contains("dd/mm/yyyy")) {
        c = Calendar.getInstance();
        c.setTime(new SimpleDateFormat("dd/MM/yyyy").parse(value.toString()));
        key = key.replace("dd/mm/yyyy", "");
      } else if (key.contains("dd-mm-yyyy")) {
        c = Calendar.getInstance();
        c.setTime(new SimpleDateFormat("dd-MM-yyyy").parse(value.toString()));
        key = key.replace("dd-mm-yyyy", "");
      } else if (key.contains("mm/dd/yyyy")) {
        c = Calendar.getInstance();
        c.setTime(new SimpleDateFormat("MM/dd/yyyy").parse(value.toString()));
        key = key.replace("mm/dd/yyyy", "");
      } else if (key.contains("mm/dd/yy")) {
        c = Calendar.getInstance();
        c.setTime(new SimpleDateFormat("MM/dd/yy").parse(value.toString()));
        key = key.replace("mm/dd/yy", "");
      } else if (key.contains("mm-dd-yyyy")) {
        c = Calendar.getInstance();
        c.setTime(new SimpleDateFormat("MM-dd-yyyy").parse(value.toString()));
        key = key.replace("mm-dd-yyyy", "");
      } else if (key.contains("yyyy-mm-dd")) {
        c = Calendar.getInstance();
        c.setTime(new SimpleDateFormat("yyyy-MM-dd").parse(value.toString()));
        key = key.replace("yyyy-mm-dd", "");
      }
    } catch (ParseException e) {
      env.logJobError("failed to parse date", e);
    }

    key = key.trim();

    if (c != null) {
      record.crmRawFieldsToSet.put(key, c);
    }
    // TODO: yes/no -> bool is causing trouble for picklist/text imports that include those values
    else if ("true".equalsIgnoreCase(value.toString())/* || "yes".equalsIgnoreCase(value)*/) {
      record.crmRawFieldsToSet.put(key, true);
    } else if ("false".equalsIgnoreCase(value.toString())/* || "no".equalsIgnoreCase(value)*/) {
      record.crmRawFieldsToSet.put(key, false);
    }
    // But this seems safe?
    else if ("x".equalsIgnoreCase(value.toString())) {
      record.crmRawFieldsToSet.put(key, true);
    } else if ("CLEAR IT".equalsIgnoreCase(value.toString()) || "CLEARIT".equalsIgnoreCase(value.toString())) {
      String[] fieldsToNull = record.getFieldsToNull();
      if (fieldsToNull == null) {
        fieldsToNull = new String[1];
      } else {
        fieldsToNull = Arrays.copyOf(fieldsToNull, fieldsToNull.length + 1);
      }
      fieldsToNull[fieldsToNull.length - 1] = key;
      record.setFieldsToNull(fieldsToNull);
    } else {
      record.crmRawFieldsToSet.put(key, value);
    }
  }
}
