package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.AccountingTransaction;
import com.impactupgrade.nucleus.model.CrmContact;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface AccountingPlatformService extends SegmentService {

    // <crm contact id, accounting contact id>
    Map<String, String> updateOrCreateContacts(List<CrmContact> crmContacts) throws Exception;

    List<AccountingTransaction> getTransactions(Date startDate) throws Exception;

    void createTransactions(List<AccountingTransaction> transactions) throws Exception;
}
