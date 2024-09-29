package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.PagedResults;

import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
      List<CrmContact> crmContacts = getCrmContacts(crmDonations);
      try {
        env.accountingPlatformService().get().updateOrCreateTransactions(crmDonations, crmContacts);
      } catch (Exception e) {
        env.logJobError("{}/syncTransactions failed: {}", this.name(), e);
      }
    } else {
      env.logJobWarn("Accounting Platform Service is not defined!");
    }
  }

  private List<CrmContact> getCrmContacts(List<CrmDonation> crmDonations) {
    Set<String> crmContactIds = new HashSet<>();
    return crmDonations.stream()
        .map(crmDonation -> crmDonation.contact)
        // Only unique ids
        .filter(contact -> crmContactIds.add(contact.id))
        .toList();
  }
}
