package com.impactupgrade.nucleus.util.crmsync;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;

import java.util.Optional;

public class RecurringDonationSyncHelper extends SyncHelper<CrmRecurringDonation> {

  public RecurringDonationSyncHelper(Environment env) {
    super(env);
  }

  @Override
  public Optional<CrmRecurringDonation> getPrimaryRecord(String primaryRecordId) throws Exception {
    return primaryCrm.getRecurringDonationById(primaryRecordId);
  }

  @Override
  public Optional<CrmRecurringDonation> getSecondaryRecord(CrmRecurringDonation secondaryCrmRecord) throws Exception {
    return primaryCrm.getRecurringDonationBySubscriptionId(secondaryCrmRecord.paymentGatewaySubscriptionId);
  }

  @Override
  public void insertRecordToSecondary(CrmRecurringDonation secondaryCrmRecord) throws Exception {
    secondaryCrm.insertRecurringDonation(secondaryCrmRecord);
  }

  @Override
  public void updateRecordInSecondary(CrmRecurringDonation secondaryCrmRecord) throws Exception {
    secondaryCrm.updateRecurringDonation(secondaryCrmRecord);
  }
}
