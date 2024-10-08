/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.AccountingTransaction;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.service.segment.AccountingPlatformService;
import com.impactupgrade.nucleus.util.Utils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Optional;

public class AccountingService {

    private final Environment env;
    private final Optional<AccountingPlatformService> _accountingPlatformService;
    private final List<AccountingPlatformService> accountingPlatformServices;

    public AccountingService(Environment env) {
        this.env = env;
        this._accountingPlatformService = env.accountingPlatformService();
        this.accountingPlatformServices = env.allAccountingPlatformServices();
    }

    public void processTransaction(PaymentGatewayEvent paymentGatewayEvent) throws Exception {
        if (_accountingPlatformService.isEmpty()) {
            return;
        }

        if (Strings.isNullOrEmpty(paymentGatewayEvent.getCrmDonation().id)) {
            env.logJobWarn("payment gateway event {} failed to process the donation; skipping accounting processing", paymentGatewayEvent.getCrmDonation().transactionId);
            return;
        }

        Optional<AccountingTransaction> accountingTransactionO = _accountingPlatformService.get().getTransaction(paymentGatewayEvent.getCrmDonation());
        if (accountingTransactionO.isPresent()) {
            env.logJobInfo("Accounting transaction already exists for transaction id {}. Returning...", paymentGatewayEvent.getCrmDonation().transactionId);
            return;
        }

        processTransaction(paymentGatewayEvent.getCrmDonation());
    }

    protected void processTransaction(CrmDonation crmDonation) throws Exception {
        CrmContact crmContact = getDonationContact(crmDonation);
        if (crmContact == null) {
            // Should be unreachable
            env.logJobWarn("Failed to find crm contact for crm donation {}!", crmDonation.id);
            return;
        }

        String contactId = _accountingPlatformService.get().updateOrCreateContact(crmContact);
        if (!Strings.isNullOrEmpty(contactId)) {
            env.logJobInfo("Upserted contact: {}", contactId);

            AccountingTransaction accountingTransaction = toAccountingTransaction(crmDonation, contactId, crmContact.id);
            String transactionId = _accountingPlatformService.get().createTransaction(accountingTransaction);
            env.logJobInfo("Created transaction: {}", transactionId);
        }
    }

    private CrmContact getDonationContact(CrmDonation crmDonation) throws Exception {
        // We retrieve the full objects from the CRM here, in case they have additional info over what was in
        // the payment event.
        CrmContact crmContact = null;
        if (!StringUtils.isEmpty(crmDonation.account.id)) {
            CrmAccount crmAccount = env.donationsCrmService().getAccountById(crmDonation.account.id).orElse(null);
            // create a faux contact for org type account
            if (crmAccount != null && crmAccount.recordType == EnvironmentConfig.AccountType.ORGANIZATION) {
                crmContact = new CrmContact();
                crmContact.id = crmAccount.id;
                String[] firstLastName = Utils.fullNameToFirstLast(crmAccount.name);
                crmContact.firstName = firstLastName[0];
                crmContact.lastName = firstLastName[1];
                crmContact.mailingAddress = crmAccount.billingAddress;
                crmContact.crmRawObject = crmAccount.crmRawObject;
                crmContact.email = crmDonation.contact.email;
                crmContact.mobilePhone = crmDonation.contact.mobilePhone;
            }
        }
        if (crmContact == null) {
            if (!StringUtils.isEmpty(crmDonation.contact.id)) {
                // Get non-org contact
                crmContact = env.donationsCrmService().getContactById(crmDonation.contact.id).orElse(null);
            }
        }
        return crmContact;
    }

//    public void addDeposits(List<PaymentGatewayDeposit> paymentGatewayDeposits) {
//        if (CollectionUtils.isEmpty(accountingPlatformServices)) {
//            env.logJobInfo("Accounting Platform Services not defined for environment! Returning...");
//            return;
//        }
//
//        List<PaymentGatewayEvent> transactions = collectTransactions(paymentGatewayDeposits);
//        if (CollectionUtils.isEmpty(transactions)) {
//            // Nothing to process
//            env.logJobInfo("Got no transactions to process. Returning...");
//            return;
//        }
//
//        env.logJobInfo("Input transactions count: {}", transactions.size());
//
//        Date startDate = getMinStartDate(transactions);
//
//        for (AccountingPlatformService accountingPlatformService : accountingPlatformServices) {
//            String accountingPlatformName = accountingPlatformService.name();
//            env.logJobInfo("Accounting platform service '{}' running...", accountingPlatformName);
//            try {
//                List<AccountingTransaction> existingTransactions = accountingPlatformService.getTransactions(startDate);
//                env.logJobInfo("Found existing transactions: {}", existingTransactions.size());
//
//                // get all the new transactions we need to create
//                List<AccountingTransaction> transactionsToCreate = processTransactions(
//                        transactions, existingTransactions, accountingPlatformService);
//                if (transactionsToCreate.isEmpty()) {
//                    env.logJobInfo("No new transactions to create. Returning...");
//                    continue;
//                }
//
//                accountingPlatformService.createTransactions(transactionsToCreate);
//
//            } catch (Exception e) {
//                env.logJobError("Failed to add transactions!", e);
//            }
//
//            env.logJobInfo("Accounting platform service '{}' done.", accountingPlatformName);
//        }
//    }
//
//    private List<PaymentGatewayEvent> collectTransactions(List<PaymentGatewayDeposit> deposits) {
//        return deposits.stream()
//                .filter(Objects::nonNull)
//                .map(PaymentGatewayDeposit::getLedgers)
//                .filter(MapUtils::isNotEmpty)
//                .flatMap(ledgersMap -> ledgersMap.entrySet().stream())
//                .filter(Objects::nonNull)
//                .map(ledgerEntry -> ledgerEntry.getValue().getTransactions())
//                .flatMap(Collection::stream)
//                .collect(Collectors.toList());
//    }
//
//    private Date getMinStartDate(List<PaymentGatewayEvent> transactions) {
//        Set<Date> transactionDates = transactions.stream()
//                .filter(Objects::nonNull)
//                .map(PaymentGatewayEvent::getTransactionDate)
//                .filter(Objects::nonNull)
//                .map(Calendar::getTime)
//                .collect(Collectors.toSet());
//        return Collections.min(transactionDates);
//    }
//
//    private List<AccountingTransaction> processTransactions(
//            List<PaymentGatewayEvent> paymentGatewayEvents,
//            List<AccountingTransaction> existingTransactions,
//            AccountingPlatformService accountingPlatformService) throws Exception {
//
//        // This is a several-step process, processing donations and associated contacts/accounts using bulk queries.
//        // Super important to do it this way, as opposed to one loop with one-at-a-time queries. Some deposits carry
//        // a huge number of transactions -- processing would take a long time + Xero has really restrictive rate limits.
//
//        // First, filter down the events that are truly new.
//        List<String> existingTransactionsIds = existingTransactions.stream()
//                .map(existingTransaction -> existingTransaction.paymentGatewayTransactionId)
//                .collect(Collectors.toList());
//        List<PaymentGatewayEvent> newPaymentGatewayEvents = paymentGatewayEvents.stream()
//            .filter(paymentGatewayEvent -> !existingTransactionsIds.contains(paymentGatewayEvent.getTransactionId()))
//            .collect(Collectors.toList());
//
//        // Exit early if there aren't any.
//        if (newPaymentGatewayEvents.isEmpty()) {
//            return Collections.emptyList();
//        }
//
//        // Then, all in one shot, grab all the CRM donations. We don't need the donations for anything other than
//        // as a conduit to the contact/account, since these transactions came straight from the payment gateway deposit event
//        // and are unlikely to have customer details.
//        List<CrmDonation> crmDonations = env.donationsCrmService().getDonations(newPaymentGatewayEvents);
//        // Create two maps, pairing the donation's original transaction id with the contact/account id.
//        // TODO: DR breaks here! Transaction ID is in the PT object, not Opp, so DRSfdcClient's getDonationsByTransactionIds needs rethought.
//        Map<String, String> transactionIdToContactId = new HashMap<>();
//        crmDonations.stream()
//                .filter(crmDonation -> crmDonation.contact != null && !Strings.isNullOrEmpty(crmDonation.contact.id))
//                .forEach(crmDonation -> transactionIdToContactId.put(crmDonation.paymentGatewayTransactionId, crmDonation.contact.id));
//        Map<String, String> transactionIdToAccountId = new HashMap<>();
//        crmDonations.stream()
//                .filter(crmDonation -> crmDonation.account != null && !Strings.isNullOrEmpty(crmDonation.account.id))
//                .forEach(crmDonation -> transactionIdToAccountId.put(crmDonation.paymentGatewayTransactionId, crmDonation.account.id));
//
//        // Then, again all in one shot, grab all the CRM contacts and accounts.
//        List<CrmContact> crmContacts = env.donationsCrmService().getContactsByIds(transactionIdToContactId.values().stream().toList());
//        Map<String, CrmContact> crmContactsByIds = crmContacts.stream().collect(Collectors.toMap(crmContact -> crmContact.id, crmContact -> crmContact));
//        List<CrmAccount> crmAccounts = env.donationsCrmService().getAccountsByIds(transactionIdToAccountId.values().stream().toList());
//        Map<String, CrmAccount> crmAccountsByIds = crmAccounts.stream().collect(Collectors.toMap(crmAccount -> crmAccount.id, crmAccount -> crmAccount));
//
//        // Now loop over the events and batch create/update all the contacts in the accounting platform.
//        Map<String, CrmContact> crmContactsToUpsert = new HashMap<>();
//        for (PaymentGatewayEvent newPaymentGatewayEvent : newPaymentGatewayEvents) {
//            // TODO: This might need a refactor in the future. For Xero, Contacts are a centering point, and here we're
//            //  transforming business gifts into faux CrmContacts. But a better abstract concept may be helpful for QB and others.
//            String paymentGatewayEventTransactionId = newPaymentGatewayEvent.getTransactionId();
//            String contactId = transactionIdToContactId.get(paymentGatewayEventTransactionId);
//            String accountId = transactionIdToAccountId.get(paymentGatewayEventTransactionId);
//            if (!Strings.isNullOrEmpty(accountId) && crmAccountsByIds.get(accountId).type == CrmAccount.Type.ORGANIZATION) {
//                // biz donation -> faux contact for the org account
//                CrmAccount crmAccount = crmAccountsByIds.get(accountId);
//                CrmContact crmAccountContact = new CrmContact();
//                crmAccountContact.id = crmAccount.id;
//                crmAccountContact.fullName = crmAccount.name;
//                crmAccountContact.address = crmAccount.address;
//                crmContactsToUpsert.put(paymentGatewayEventTransactionId, crmAccountContact);
//            } else {
//                // not a biz donation -> use the real contact
//                crmContactsToUpsert.put(paymentGatewayEventTransactionId, crmContactsByIds.get(contactId));
//            }
//        }
//        Map<String, String> crmContactIdToAccountingContactId = accountingPlatformService
//            .updateOrCreateContacts(crmContactsToUpsert.values().stream().toList());
//
//        // Finally, return the combined list of transactions + the contacts from within the *accounting* platform.
//        return newPaymentGatewayEvents.stream().map(newPaymentGatewayEvent -> {
//            String crmContactId = crmContactsToUpsert.get(newPaymentGatewayEvent.getTransactionId()).id;
//            return toAccountingTransaction(newPaymentGatewayEvent,
//                    crmContactIdToAccountingContactId.get(crmContactId), crmContactId);
//        }).collect(Collectors.toList());
//    }

    private AccountingTransaction toAccountingTransaction(CrmDonation crmDonation, String accountingContactId, String crmContactId) {
        return new AccountingTransaction(
            accountingContactId,
            crmContactId,
            crmDonation.amount,
            crmDonation.closeDate,
            crmDonation.description,
            crmDonation.transactionType,
            crmDonation.gatewayName,
            crmDonation.transactionId,
            crmDonation.isRecurring()
        );
    }
}
