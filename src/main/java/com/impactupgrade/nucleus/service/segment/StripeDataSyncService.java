package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.PagedResults;

import java.util.Calendar;
import java.util.List;

public class StripeDataSyncService implements DataSyncService {

  protected Environment env;

  @Override
  public String name() {
    return "stripeDataSync";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return true;
  }

  @Override
  public void init(Environment env) {
    this.env = env;
  }

  @Override
  public void syncContacts(Calendar updatedAfter) throws Exception {
    PagedResults<CrmContact> contactPagedResults = env.primaryCrmService().getDonorIndividualContacts(updatedAfter);
    for (PagedResults.ResultSet<CrmContact> resultSet : contactPagedResults.getResultSets()) {
      //TODO: bulk update?
      for (CrmContact crmContact : resultSet.getRecords()) {
        //TODO: update contact in Stripe
      }
    }
  }

  @Override
  public void syncTransactions(Calendar updatedAfter) throws Exception {
    List<CrmDonation> crmDonations = env.primaryCrmService().getDonations(updatedAfter);
    for (CrmDonation crmDonation : crmDonations) {
      //TODO: bulk update in Stripe
    }
  }
}
