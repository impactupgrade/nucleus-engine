package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PaymentGatewayDeposit;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.service.segment.AccountingPlatformService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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
        for (AccountingPlatformService accountingPlatformService : accountingPlatformServices) {
            String accountingPlatformName = accountingPlatformService.name();
            log.info("Accounting platform service '{}' running...", accountingPlatformName);
            try {
                Date startDate = getMinStartDate(transactions);
                List<?> existingTransactions = accountingPlatformService.getTransactions(startDate);
                log.info("Found existing transactions: {}", existingTransactions.size());

                List<PaymentGatewayEvent> transactionsToCreate = getTransactionsToCreate(
                        transactions, existingTransactions, accountingPlatformService);
                if (transactionsToCreate.isEmpty()) {
                    log.info("No new transactions to create. Returning...");
                    continue;
                }

                List<CrmContact> crmContacts = getCrmContacts(transactionsToCreate);
                crmContacts = uniqueItems(crmContacts, crmContact -> {
                    if (!Strings.isNullOrEmpty(crmContact.email)) {
                        return crmContact.email;
                    } else {
                        return crmContact.fullName();
                    }
                });
                List existingContacts = accountingPlatformService.getContacts();

                List<CrmContact> crmContactsToCreate = getCrmContactsToCreate(
                        crmContacts, existingContacts, accountingPlatformService);

                List createdContacts = accountingPlatformService.createContacts(crmContactsToCreate);
                List allContacts = new ArrayList();
                allContacts.addAll(existingContacts);
                allContacts.addAll(createdContacts);

                Map<String, ?> contactsByPrimaryKey = mapItems(allContacts, c -> accountingPlatformService.getContactPrimaryKey(c));
                Map<String, ?> contactsBySecondaryKey = mapItems(allContacts, c -> accountingPlatformService.getContactSecondaryKey(c));

                accountingPlatformService.createTransactions(transactionsToCreate, contactsByPrimaryKey, contactsBySecondaryKey);

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

    private <T> List<PaymentGatewayEvent> getTransactionsToCreate(
            List<PaymentGatewayEvent> paymentGatewayEvents, List<T> transactions,
            AccountingPlatformService accountingPlatformService) {

        if (CollectionUtils.isEmpty(transactions)) {
            return paymentGatewayEvents;
        }

        Map<String, PaymentGatewayEvent> paymentGatewayEventMap = mapItems(paymentGatewayEvents, PaymentGatewayEvent::getTransactionId);
        Map<String, T> existingTransactionsMap = mapItems(transactions, t -> accountingPlatformService.getTransactionKey(t));

        List<PaymentGatewayEvent> transactionsToCreate = new ArrayList<>();
        for (Map.Entry<String, PaymentGatewayEvent> entry : paymentGatewayEventMap.entrySet()) {
            if (!existingTransactionsMap.containsKey(entry.getKey())) {
                transactionsToCreate.add(entry.getValue());
            }
        }

        return transactionsToCreate;
    }

    // TODO: Business gifts with no contact? Should the CrmAccount create a Xero contact?
    private List<CrmContact> getCrmContacts(List<PaymentGatewayEvent> transactions) throws Exception {
        List<String> contactIds = env.donationsCrmService().getDonations(transactions).stream().filter(donation -> donation.contact != null).map(donation -> donation.contact.id).filter(contactId -> !Strings.isNullOrEmpty(contactId)).collect(Collectors.toList());
        return env.donationsCrmService().getContactsByIds(contactIds);
    }

    private <T> List<T> uniqueItems(List<T> items, Function<T, String> uniqueKeyFunction) {
        if (CollectionUtils.isEmpty(items) || Objects.isNull(uniqueKeyFunction)) {
            return Collections.emptyList();
        }
        Map<String, T> itemsMap = new HashMap<>();
        items.forEach(item -> {
            String uniqueKey = uniqueKeyFunction.apply(item);
            if (!itemsMap.containsKey(uniqueKey)) {
                itemsMap.put(uniqueKey, item);
            }
        });
        return new ArrayList<>(itemsMap.values());
    }

    private <C> List<CrmContact> getCrmContactsToCreate(
            List<CrmContact> crmContacts, List<C> contacts,
            AccountingPlatformService accountingPlatformService) {

        if (CollectionUtils.isEmpty(contacts)) {
            return crmContacts;
        }

        Map<String, C> contactsByPrimaryKey = mapItems(contacts, c -> accountingPlatformService.getContactPrimaryKey(c));
        Map<String, C> contactsBySecondaryKey = mapItems(contacts, c -> accountingPlatformService.getContactSecondaryKey(c));

        List<CrmContact> crmContactsToCreate = new ArrayList<>();
        for (CrmContact crmContact : crmContacts) {
            String primaryKey = accountingPlatformService.getCrmContactPrimaryKey(crmContact);

            if (!contactsByPrimaryKey.containsKey(primaryKey)) {
                String secondaryKey = accountingPlatformService.getCrmContactSecondaryKey(crmContact);

                if (!contactsBySecondaryKey.containsKey(secondaryKey)) {
                    crmContactsToCreate.add(crmContact);
                }
            }
        }

        return crmContactsToCreate;
    }

    private <T> Map<String, T> mapItems(List<T> items, Function<T, String> mapFunction) {
        if (CollectionUtils.isEmpty(items) || Objects.isNull(mapFunction)) {
            return Collections.emptyMap();
        }
        Map<String, T> itemsMap = new HashMap<>();
        for (T item : items) {
            String key = mapFunction.apply(item);
            if (!StringUtils.isEmpty(key)) {
                itemsMap.put(key.toLowerCase(Locale.ROOT), item);
            }
        }
        return itemsMap;
    }

}
