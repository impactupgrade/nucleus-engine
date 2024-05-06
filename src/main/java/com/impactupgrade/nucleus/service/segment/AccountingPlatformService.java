/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.AccountingTransaction;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;

import java.util.Optional;

public interface AccountingPlatformService extends SegmentService {

    Optional<AccountingTransaction> getTransaction(CrmDonation crmDonation) throws Exception;

    String updateOrCreateContact(CrmContact crmContact) throws Exception;

    String createTransaction(AccountingTransaction accountingTransaction) throws Exception;

//    List<AccountingTransaction> getTransactions(Date startDate) throws Exception;
//
//    // <crm contact id, accounting contact id>
//    Map<String, String> updateOrCreateContacts(List<CrmContact> crmContacts) throws Exception;
//
//    void createTransactions(List<AccountingTransaction> transactions) throws Exception;
}
