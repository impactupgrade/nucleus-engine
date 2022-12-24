package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.AccountingTransaction;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;

import java.util.Optional;

public interface AccountingPlatformService extends SegmentService {

    // TODO
    Optional<AccountingTransaction> getTransaction(PaymentGatewayEvent paymentGatewayEvent) throws Exception;

    String updateOrCreateContact(CrmContact crmContact) throws Exception;

    String createTransaction(AccountingTransaction accountingTransaction) throws Exception;

//    List<AccountingTransaction> getTransactions(Date startDate) throws Exception;
//
//    // <crm contact id, accounting contact id>
//    Map<String, String> updateOrCreateContacts(List<CrmContact> crmContacts) throws Exception;
//
//    void createTransactions(List<AccountingTransaction> transactions) throws Exception;
}
