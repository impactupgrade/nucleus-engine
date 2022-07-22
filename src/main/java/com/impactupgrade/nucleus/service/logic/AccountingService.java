package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.AccountingTransaction;
import com.impactupgrade.nucleus.model.CrmAccount;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.PaymentGatewayDeposit;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.service.segment.AccountingPlatformService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class AccountingService {

    private static final Logger log = LogManager.getLogger(AccountingService.class);

    private final Environment env;
    private final List<AccountingPlatformService> accountingPlatformServices;

    public AccountingService(Environment env) {
        this.env = env;
        this.accountingPlatformServices = env.allAccountingPlatformServices();
    }

    public void addDeposits(List<PaymentGatewayDeposit> paymentGatewayDeposits) {
        if (CollectionUtils.isEmpty(accountingPlatformServices)) {
            log.info("Accounting Platform Services not defined for environment! Returning...");
            return;
        }

        List<PaymentGatewayEvent> transactions = collectTransactions(paymentGatewayDeposits);
        if (CollectionUtils.isEmpty(transactions)) {
            // Nothing to process
            log.info("Got no transactions to process. Returning...");
            return;
        }

        log.info("Input transactions count: {}", transactions.size());

        Date startDate = getMinStartDate(transactions);

        for (AccountingPlatformService accountingPlatformService : accountingPlatformServices) {
            String accountingPlatformName = accountingPlatformService.name();
            log.info("Accounting platform service '{}' running...", accountingPlatformName);
            try {
                List<AccountingTransaction> existingTransactions = accountingPlatformService.getTransactions(startDate);
                log.info("Found existing transactions: {}", existingTransactions.size());

                // get all the new transactions we need to create
                List<AccountingTransaction> transactionsToCreate = processTransactions(
                        transactions, existingTransactions, accountingPlatformService);
                if (transactionsToCreate.isEmpty()) {
                    log.info("No new transactions to create. Returning...");
                    continue;
                }

                accountingPlatformService.createTransactions(transactionsToCreate);

            } catch (Exception e) {
                log.error("Failed to add transactions!", e);
            }

            log.info("Accounting platform service '{}' done.", accountingPlatformName);
        }
    }

    // Utils
    private List<PaymentGatewayEvent> collectTransactions(List<PaymentGatewayDeposit> deposits) {
        return deposits.stream()
                .filter(Objects::nonNull)
                .map(PaymentGatewayDeposit::getLedgers)
                .filter(MapUtils::isNotEmpty)
                .flatMap(ledgersMap -> ledgersMap.entrySet().stream())
                .filter(Objects::nonNull)
                .map(ledgerEntry -> ledgerEntry.getValue().getTransactions())
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    private Date getMinStartDate(List<PaymentGatewayEvent> transactions) {
        Set<Date> transactionDates = transactions.stream()
                .filter(Objects::nonNull)
                .map(PaymentGatewayEvent::getTransactionDate)
                .filter(Objects::nonNull)
                .map(Calendar::getTime)
                .collect(Collectors.toSet());
        return Collections.min(transactionDates);
    }

    private List<AccountingTransaction> processTransactions(List<PaymentGatewayEvent> paymentGatewayEvents,
            List<AccountingTransaction> existingTransactions, AccountingPlatformService accountingPlatformService) throws Exception {

        // This is a several-step process, processing donations and associated contacts/accounts using bulk queries.
        // Super important to do it this way, as opposed to one loop with one-at-a-time queries. Some deposits carry
        // a huge number of transactions -- processing would take a long time + Xero has really restrictive rate limits.

        // First, filter down the events that are truly new.
        List<String> existingTransactionsIds = existingTransactions.stream().map(existingTransaction -> existingTransaction.paymentGatewayTransactionId).collect(Collectors.toList());
        List<PaymentGatewayEvent> newPaymentGatewayEvents = paymentGatewayEvents.stream()
            .filter(paymentGatewayEvent -> !existingTransactionsIds.contains(paymentGatewayEvent.getTransactionId()))
            .collect(Collectors.toList());

        // Exit early if there aren't any.
        if (newPaymentGatewayEvents.isEmpty()) {
            return Collections.emptyList();
        }

        // Then, all in one shot, grab all the CRM donations. We don't need the donations for anything other than
        // as a conduit to the contact/account, since these transactions came straight from the payment gateway deposit event
        // and are unlikely to have customer details.
        List<CrmDonation> crmDonations = env.donationsCrmService().getDonations(newPaymentGatewayEvents);
        // Create two maps, pairing the donation's original transaction id with the contact/account id.
        // TODO: DR breaks here! Transaction ID is in the PT object, not Opp, so DRSfdcClient's getDonationsByTransactionIds needs rethought.
        Map<String, String> transactionIdToContactId = crmDonations.stream().filter(crmDonation -> crmDonation.contact != null && !Strings.isNullOrEmpty(crmDonation.contact.id)).collect(Collectors.toMap(crmDonation -> crmDonation.paymentGatewayTransactionId, crmDonation -> crmDonation.contact.id));
        Map<String, String> transactionIdToAccountId = crmDonations.stream().filter(crmDonation -> crmDonation.account != null && !Strings.isNullOrEmpty(crmDonation.account.id)).collect(Collectors.toMap(crmDonation -> crmDonation.paymentGatewayTransactionId, crmDonation -> crmDonation.account.id));

        // Then, again all in one shot, grab all the CRM contacts and accounts.
        Map<String, CrmContact> crmContacts = env.donationsCrmService().getContactsByIds(transactionIdToContactId.values().stream().toList()).stream().collect(Collectors.toMap(crmContact -> crmContact.id, crmContact -> crmContact));
        Map<String, CrmAccount> crmAccounts = env.donationsCrmService().getAccountsByIds(transactionIdToAccountId.values().stream().toList()).stream().collect(Collectors.toMap(crmContact -> crmContact.id, crmContact -> crmContact));

        // Now loop over the events and batch create/update all the contacts in the accounting platform.
        Map<String, CrmContact> crmContactsToUpsert = new HashMap<>();
        for (PaymentGatewayEvent newPaymentGatewayEvent : newPaymentGatewayEvents) {
            // TODO: This might need a refactor in the future. For Xero, Contacts are a centering point, and here we're
            //  transforming business gifts into faux CrmContacts. But a better abstract concept may be helpful for QB and others.
            String contactId = transactionIdToContactId.get(newPaymentGatewayEvent.getTransactionId());
            String accountId = transactionIdToAccountId.get(newPaymentGatewayEvent.getTransactionId());
            if (!Strings.isNullOrEmpty(accountId) && crmAccounts.get(accountId).type == CrmAccount.Type.ORGANIZATION) {
                // biz donation -> faux contact for the org account
                CrmAccount crmAccount = crmAccounts.get(accountId);
                CrmContact crmAccountContact = new CrmContact();
                crmAccountContact.id = crmAccount.id;
                crmAccountContact.fullName = crmAccount.name;
                crmAccountContact.address = crmAccount.address;
                crmContactsToUpsert.put(newPaymentGatewayEvent.getTransactionId(), crmAccountContact);
            } else {
                // not a biz donation -> use the real contact
                crmContactsToUpsert.put(newPaymentGatewayEvent.getTransactionId(), crmContacts.get(contactId));
            }
        }
        Map<String, String> crmContactIdToAccountingContactId = accountingPlatformService
            .updateOrCreateContacts(crmContactsToUpsert.values().stream().toList());

        // Finally, return the combined list of transactions + the contacts from within the *accounting* platform.
        return newPaymentGatewayEvents.stream().map(newPaymentGatewayEvent -> {
            String crmContactId = crmContactsToUpsert.get(newPaymentGatewayEvent.getTransactionId()).id;
            return new AccountingTransaction(
                newPaymentGatewayEvent.getTransactionAmountInDollars(),
                newPaymentGatewayEvent.getTransactionDate(),
                newPaymentGatewayEvent.getTransactionDescription(),
                newPaymentGatewayEvent.isTransactionRecurring(),
                newPaymentGatewayEvent.getGatewayName(),
                newPaymentGatewayEvent.getTransactionId(),
                crmContactIdToAccountingContactId.get(crmContactId),
                crmContactId
            );
        }).collect(Collectors.toList());
    }
}
