package com.impactupgrade.nucleus.service.logic.sync;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmAccount;

import java.util.Optional;

public class AccountCrmSyncService extends CrmSyncService<CrmAccount> {

  public AccountCrmSyncService(Environment env) {
    super(env);
  }

  @Override
  public Optional<CrmAccount> getPrimaryRecord(String primaryRecordId) throws Exception {
    return primaryCrm.getAccountById(primaryRecordId);
  }

  @Override
  public Optional<CrmAccount> getSecondaryRecord(CrmAccount primaryCrmRecord) throws Exception {
    // TODO: Include another field? Concerned about name-only
    return secondaryCrm.getAccountByName(primaryCrmRecord.name);
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
