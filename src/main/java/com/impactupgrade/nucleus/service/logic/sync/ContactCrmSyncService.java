package com.impactupgrade.nucleus.service.logic.sync;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmAccount;
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
  public Optional<CrmContact> getSecondaryRecord(CrmContact primaryCrmRecord) throws Exception {
    return secondaryCrm.searchContacts(ContactSearch.byEmail(primaryCrmRecord.email)).getSingleResult();
  }

  @Override
  public void insertRecordToSecondary(CrmContact secondaryCrmRecord) throws Exception {
    if (!Strings.isNullOrEmpty(secondaryCrmRecord.accountId)) {
      AccountCrmSyncService accountCrmSyncService = new AccountCrmSyncService(env);

      // Ensure we properly link it to an account, if it exists.
      // TODO: A little awkward. At this point, secondaryCrmRecord.accountId actually contains the primary accountId.
      // TODO: Some concern about timing issues. Ex: In SFDC, a Contact is created, which auto creates an Account.
      //  Can we guarantee the Account will sync first? Maybe it's as simple as adding an artificial delay to the
      //  Contact case in CrmController? Even worse, HubSpot takes quite a while to resolve.
      if (!Strings.isNullOrEmpty(secondaryCrmRecord.accountId)) {
        Optional<CrmAccount> primaryCrmAccount = accountCrmSyncService.getPrimaryRecord(secondaryCrmRecord.accountId);
        if (primaryCrmAccount.isPresent()) {
          Optional<CrmAccount> secondaryCrmAccount = accountCrmSyncService.getSecondaryRecord(primaryCrmAccount.get());
          if (secondaryCrmAccount.isPresent()) {
            secondaryCrmRecord.accountId = secondaryCrmAccount.get().id;
          }
        }
      }
    }

    secondaryCrm.insertContact(secondaryCrmRecord);
  }

  @Override
  public void updateRecordInSecondary(CrmContact secondaryCrmRecord) throws Exception {
    secondaryCrm.updateContact(secondaryCrmRecord);
  }
}
