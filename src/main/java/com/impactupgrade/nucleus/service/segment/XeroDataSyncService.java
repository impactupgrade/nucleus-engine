package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.PagedResults;
import com.impactupgrade.nucleus.util.Utils;
import com.sforce.soap.partner.sobject.SObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class XeroDataSyncService implements DataSyncService {

  protected Environment env;

  private static final String CONTACT_ID_CUSTOM_FIELD_NAME = "npe01__One2OneContact__c";

  @Override
  public String name() {
    return "xeroDataSync";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return true;
  }

  @Override
  public void init(Environment env) {
    this.env = env;
  }

  // The method can/should be moved to an abstract class
  // However, it depends on whether downstream platform support bulk contact processing or not -
  // may be bulk vs 1 by 1 processing
  //TODO: move to abstract class?
  @Override
  public void syncContacts(Calendar updatedAfter) throws Exception {
    if (env.accountingPlatformService().isPresent()) {
      PagedResults<CrmContact> contactPagedResults = env.primaryCrmService().getDonorIndividualContacts(updatedAfter);
      for (PagedResults.ResultSet<CrmContact> resultSet : contactPagedResults.getResultSets()) {
        if (resultSet.getRecords().isEmpty()) continue;
        try {
          env.accountingPlatformService().get().updateOrCreateContacts(resultSet.getRecords());
        } catch (Exception e) {
          env.logJobError("{}/syncContacts failed: {}", this.name(), e);
        }
      }

      PagedResults<CrmAccount> accountPagedResults = env.primaryCrmService().getDonorOrganizationAccounts(updatedAfter);
      for (PagedResults.ResultSet<CrmAccount> resultSet : accountPagedResults.getResultSets()) {
        if (resultSet.getRecords().isEmpty()) continue;
        List<CrmContact> crmContacts = getPrimaryContactsForAccounts(resultSet.getRecords());
        try {
          env.accountingPlatformService().get().updateOrCreateContacts(crmContacts);
        } catch (Exception e) {
          env.logJobError("{}/syncContacts failed: {}", this.name(), e);
        }
      }
    } else {
      env.logJobWarn("Accounting Platform Service is not defined!");
    }
  }

  @Override
  public void syncTransactions(Calendar updatedAfter) throws Exception {
    if (env.accountingPlatformService().isPresent()) {
      List<CrmDonation> crmDonations = env.primaryCrmService().getDonations(updatedAfter);
      if (crmDonations.isEmpty()) {
        return;
      }
      try {
        env.accountingPlatformService().get().updateOrCreateTransactions(crmDonations);
      } catch (Exception e) {
        env.logJobError("{}/syncTransactions failed: {}", this.name(), e);
      }
    } else {
      env.logJobWarn("Accounting Platform Service is not defined!");
    }
  }

  private List<CrmContact> getPrimaryContactsForAccounts(List<CrmAccount> crmAccounts) throws Exception {
    Map<String, CrmAccount> contactsToAccountsMap = new HashMap<>();
    List<CrmContact> fauxContacts = new ArrayList<>();
    crmAccounts.stream()
        .filter(crmAccount -> crmAccount.crmRawObject instanceof SObject)
        .forEach(crmAccount -> {
          String contactId = (String) ((SObject) crmAccount.crmRawObject).getField(CONTACT_ID_CUSTOM_FIELD_NAME);
          if (!Strings.isNullOrEmpty(contactId)) {
            contactsToAccountsMap.put(contactId, crmAccount);
          } else {
            // adding faux contact if account does not have primary contact available
            fauxContacts.add(toFauxContact(crmAccount));
          }
        });

    List<String> contactIds = contactsToAccountsMap.keySet().stream().toList();
    List<CrmContact> crmContacts = env.primaryCrmService().getContactsByIds(contactIds);
    crmContacts.forEach(crmContact -> {
      crmContact.account = contactsToAccountsMap.get(crmContact.id);
    });
    crmContacts.addAll(fauxContacts);
    return crmContacts;
  }

  private CrmContact toFauxContact(CrmAccount crmAccount) {
    CrmContact crmContact = new CrmContact();
    crmContact.id = crmAccount.id;
    String[] firstLastName = Utils.fullNameToFirstLast(crmAccount.name);
    crmContact.firstName = firstLastName[0];
    crmContact.lastName = firstLastName[1];
    crmContact.mailingAddress = crmAccount.billingAddress;
    crmContact.crmRawObject = crmAccount.crmRawObject;
    crmContact.email = crmAccount.email;
    crmContact.account = crmAccount;
    return crmContact;
  }
}
