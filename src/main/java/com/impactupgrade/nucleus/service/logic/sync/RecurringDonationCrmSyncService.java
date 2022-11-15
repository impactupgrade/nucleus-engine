package com.impactupgrade.nucleus.service.logic.sync;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;

import java.util.Optional;

public class RecurringDonationCrmSyncService extends CrmSyncService<CrmRecurringDonation> {

  public RecurringDonationCrmSyncService(Environment env) {
    super(env);
  }

  @Override
  public Optional<CrmRecurringDonation> getPrimaryRecord(String primaryRecordId) throws Exception {
    return primaryCrm.getRecurringDonationById(primaryRecordId);
  }

  @Override
  public Optional<CrmRecurringDonation> getSecondaryRecord(CrmRecurringDonation primaryCrmRecord) throws Exception {
    return primaryCrm.getRecurringDonationBySubscriptionId(primaryCrmRecord.paymentGatewaySubscriptionId);
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
