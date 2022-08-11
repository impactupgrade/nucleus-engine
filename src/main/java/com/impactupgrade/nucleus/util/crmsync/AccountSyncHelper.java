package com.impactupgrade.nucleus.util.crmsync;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmAccount;

import java.util.Optional;

public class AccountSyncHelper extends SyncHelper<CrmAccount> {

  public AccountSyncHelper(Environment env) {
    super(env);
  }

  @Override
  public Optional<CrmAccount> getPrimaryRecord(String primaryRecordId) throws Exception {
    return primaryCrm.getAccountById(primaryRecordId);
  }

  @Override
  public Optional<CrmAccount> getSecondaryRecord(CrmAccount secondaryCrmRecord) throws Exception {
    return secondaryCrm.getAccountByName(secondaryCrmRecord.name);
  }

  @Override
  public void insertRecordToSecondary(CrmAccount secondaryCrmRecord) throws Exception {
    secondaryCrm.insertAccount(secondaryCrmRecord);
  }

  @Override
  public void updateRecordInSecondary(CrmAccount secondaryCrmRecord) throws Exception {
    secondaryCrm.updateAccount(secondaryCrmRecord);
  }
}
