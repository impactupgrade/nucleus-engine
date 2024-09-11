package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PagedResults;

import java.util.Calendar;

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
}
