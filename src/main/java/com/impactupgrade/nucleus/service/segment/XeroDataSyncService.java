package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.AccountingContact;
import com.impactupgrade.nucleus.model.AccountingTransaction;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.PagedResults;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class XeroDataSyncService implements DataSyncService {

  protected Environment env;

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
      PagedResults<CrmContact> contactPagedResults = env.primaryCrmService().getDonorContacts(updatedAfter);
      for (PagedResults.ResultSet<CrmContact> resultSet : contactPagedResults.getResultSets()) {
        if (resultSet.getRecords().isEmpty()) continue;
        try {
          env.accountingPlatformService().get().updateOrCreateContacts(resultSet.getRecords());
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

      // Get existing contacts first
      List<CrmContact> crmContacts = getCrmContacts(crmDonations);
      List<AccountingContact> accountingContacts = getAccountingContacts(crmContacts);
      // and keep in a map
      Map<String, AccountingContact> accountingContactMap = accountingContacts.stream()
          .collect(Collectors.toMap(ac -> ac.crmContactId, ac -> ac));

      // Then sync donations, after back-filling contact ids
      List<AccountingTransaction> accountingTransactions = crmDonations.stream()
          .map(crmDonation -> {
            CrmContact crmContact = crmDonation.contact;
            AccountingContact accountingContact = accountingContactMap.get(crmContact.id);
            return toAccountingTransaction(accountingContact.contactId, crmContact, crmDonation);
          })
          .toList();
      try {
        env.accountingPlatformService().get().updateOrCreateTransactions(accountingTransactions);
      } catch (Exception e) {
        env.logJobError("{}/syncTransactions failed: {}", this.name(), e);
      }
    } else {
      env.logJobWarn("Accounting Platform Service is not defined!");
    }
  }

  protected AccountingTransaction toAccountingTransaction(String accountingContactId, CrmContact crmContact, CrmDonation crmDonation) {
    return new AccountingTransaction(
        accountingContactId,
        crmContact.id,
        crmDonation.amount,
        crmDonation.closeDate,
        crmDonation.description,
        crmDonation.transactionType,
        crmDonation.gatewayName,
        crmDonation.transactionId,
        crmDonation.isRecurring());
  }

  private List<CrmContact> getCrmContacts(List<CrmDonation> crmDonations) {
    Set<String> crmContactIds = new HashSet<>();
    return crmDonations.stream()
        .map(crmDonation -> crmDonation.contact)
        // Only unique ids
        .filter(contact -> crmContactIds.add(contact.id))
        .toList();
  }

  private List<AccountingContact> getAccountingContacts(List<CrmContact> crmContacts) throws Exception {
    List<AccountingContact> accountingContacts = new ArrayList<>();
    for (CrmContact crmContact : crmContacts) {
      env.accountingPlatformService().get().getContact(crmContact).ifPresent(accountingContacts::add);
    }
    return accountingContacts;
  }
}
