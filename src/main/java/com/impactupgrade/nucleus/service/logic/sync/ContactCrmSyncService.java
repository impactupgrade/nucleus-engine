package com.impactupgrade.nucleus.service.logic.sync;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmContact;

import java.util.Optional;

public class ContactCrmSyncService extends CrmSyncService<CrmContact> {

  public ContactCrmSyncService(Environment env){
    super(env);
  }

  @Override
  public Optional<CrmContact> getPrimaryRecord(String primaryRecordId) throws Exception {
    return primaryCrm.getContactById(primaryRecordId);
  }

  @Override
  public Optional<CrmContact> getSecondaryRecord(CrmContact secondaryCrmRecord) throws Exception {
    return secondaryCrm.searchContacts(ContactSearch.byEmail(secondaryCrmRecord.email)).getSingleResult();
  }

  @Override
  public void insertRecordToSecondary(CrmContact secondaryCrmRecord) throws Exception {
    secondaryCrm.insertContact(secondaryCrmRecord);
  }

  @Override
  public void updateRecordInSecondary(CrmContact secondaryCrmRecord) throws Exception {
    secondaryCrm.updateContact(secondaryCrmRecord);
  }
}
