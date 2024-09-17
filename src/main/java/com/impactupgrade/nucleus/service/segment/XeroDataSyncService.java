package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.AccountingTransaction;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.PagedResults;

import java.util.Calendar;
import java.util.List;

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
    PagedResults<CrmContact> contactPagedResults = env.primaryCrmService().getDonorContacts(updatedAfter);
    for (PagedResults.ResultSet<CrmContact> resultSet : contactPagedResults.getResultSets()) {
      if (env.accountingPlatformService().isPresent()) {
        try {
          env.accountingPlatformService().get().updateOrCreateContacts(resultSet.getRecords());
        } catch (Exception e) {
          //TODO: process errors
        }
      }
    }
  }

  @Override
  public void syncTransactions(Calendar updatedAfter) throws Exception {
    List<CrmDonation> crmDonations = env.primaryCrmService().getDonations(updatedAfter);
    if (env.accountingPlatformService().isPresent()) {
      //TODO: get all contacts ids for crm contacts and do bulk update instead?
      for (CrmDonation crmDonation : crmDonations) {
        try {
          CrmContact crmContact = crmDonation.contact;
          String contactId = env.accountingPlatformService().get().updateOrCreateContacts(List.of(crmContact))
              .stream()
              .findFirst().orElse(null);
          env.accountingPlatformService().get().createTransaction(toAccountingTransaction(contactId, crmContact, crmDonation));
        } catch (Exception e) {
          //TODO: process errors
        }
      }

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
}
