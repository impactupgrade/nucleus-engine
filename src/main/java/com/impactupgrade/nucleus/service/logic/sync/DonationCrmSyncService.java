package com.impactupgrade.nucleus.service.logic.sync;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmDonation;

import java.util.Optional;

public class DonationCrmSyncService extends CrmSyncService<CrmDonation> {

  public DonationCrmSyncService(Environment env) {
    super(env);
  }

  @Override
  public Optional<CrmDonation> getPrimaryRecord(String primaryRecordId) throws Exception {
    return primaryCrm.getDonationById(primaryRecordId);
  }

  @Override
  public Optional<CrmDonation> getSecondaryRecord(CrmDonation primaryCrmRecord) throws Exception {
    // TODO: What about checks?
    return secondaryCrm.getDonationByTransactionId(primaryCrmRecord.paymentGatewayTransactionId);
  }

  @Override
  public void insertRecordToSecondary(CrmDonation secondaryCrmRecord) throws Exception {
    secondaryCrm.insertDonation(secondaryCrmRecord);
  }

  @Override
  public void updateRecordInSecondary(CrmDonation secondaryCrmRecord) throws Exception {
    secondaryCrm.updateDonation(secondaryCrmRecord);
  }
}
