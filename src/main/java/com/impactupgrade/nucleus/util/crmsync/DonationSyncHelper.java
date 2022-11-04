package com.impactupgrade.nucleus.util.crmsync;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmDonation;

import java.util.Optional;

public class DonationSyncHelper extends SyncHelper<CrmDonation> {

  public DonationSyncHelper(Environment env) {
    super(env);
  }

  @Override
  public Optional<CrmDonation> getPrimaryRecord(String primaryRecordId) throws Exception {
    return primaryCrm.getDonationById(primaryRecordId);
  }

  @Override
  public Optional<CrmDonation> getSecondaryRecord(CrmDonation secondaryCrmRecord) throws Exception {
    return secondaryCrm.getDonationByTransactionId(secondaryCrmRecord.paymentGatewayTransactionId);
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