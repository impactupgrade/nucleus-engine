package com.impactupgrade.nucleus.service.logic;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.PaymentGatewayDeposit;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.service.segment.AccountingPlatformService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.tuple.Pair;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AccountingService {

    private static final Logger log = LogManager.getLogger(AccountingService.class);

    private final List<AccountingPlatformService> accountingPlatformServices;
    private final Gson gson = new Gson();

    public AccountingService(Environment environment) {
        this.accountingPlatformServices = environment.allAccountingPlatformServices();
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

        log.info("Input transactions: {}", transactions.size());

        for (AccountingPlatformService accountingPlatformService : accountingPlatformServices) {
            String accountingPlatformName = accountingPlatformService.name();

            log.info("Accounting platform service '{}' running...", accountingPlatformName);
            try {
                Date startDate = getMostPastTransactionDate(transactions);
                List<?> existingTransactions = accountingPlatformService.getTransactions(startDate);
                log.info("Found existing transactions: {}", existingTransactions.size());

                List<PaymentGatewayEvent> transactionsToCreate = getTransactionsToCreate(
                        transactions, PaymentGatewayEvent::getTransactionId,
                        existingTransactions, accountingPlatformService.getTransactionKeyFunction());

                if (CollectionUtils.isEmpty(transactionsToCreate)) {
                    log.info("No new transactions to create. Returning...");
                    continue;
                }

                List<CrmContact> crmContacts = uniqueItems(collectCrmContacts(transactionsToCreate), crmContact -> crmContact.id);
                List existingContacts = accountingPlatformService.getContacts();

                List<CrmContact> crmContactsToCreate = getCrmContactsToCreate(
                        crmContacts, accountingPlatformService.getCrmContactPrimaryKeyFunction(), accountingPlatformService.getCrmContactSecondaryKeyFunction(),
                        existingContacts, accountingPlatformService.getContactPrimaryKeyFunction(), accountingPlatformService.getContactSecondaryKeyFunction());
                List createdContacts = accountingPlatformService.createContacts(crmContactsToCreate);

                List allContacts = new ArrayList();
                allContacts.addAll(existingContacts);
                allContacts.addAll(createdContacts);

                Map<String, ?> contactsByPrimaryKey = mapItems(allContacts, accountingPlatformService.getContactPrimaryKeyFunction());
                Map<String, ?> contactsBySecondaryKey = mapItems(allContacts, accountingPlatformService.getContactSecondaryKeyFunction());

                accountingPlatformService.createTransactions(transactionsToCreate, contactsByPrimaryKey, contactsBySecondaryKey);

            } catch (Exception e) {
                log.error("Failed to add transactions!", e);
            }

            log.info("Accounting platform service '{}' done.", accountingPlatformName);
        }
    }

    // Utils
    private List<PaymentGatewayEvent> collectTransactions(List<PaymentGatewayDeposit> deposits) {
        if (CollectionUtils.isEmpty(deposits)) {
            return Collections.emptyList();
        }
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

    private List<CrmContact> collectCrmContacts(List<PaymentGatewayEvent> transactions) {
        if (CollectionUtils.isEmpty(transactions)) {
            return Collections.emptyList();
        }
        return transactions.stream()
                .map(PaymentGatewayEvent::getCrmContact)
                .collect(Collectors.toList());
    }

    private <T> List<T> uniqueItems(List<T> items, Function<T, String> uniqueKeyFunction) {
        if (CollectionUtils.isEmpty(items) || Objects.isNull(uniqueKeyFunction)) {
            return Collections.emptyList();
        }
        Map<String, T> itemsMap = new HashMap<>();
        items.stream().forEach(item -> {
            String uniqueKey = uniqueKeyFunction.apply(item);
            if (!itemsMap.containsKey(uniqueKey)) {
                itemsMap.put(uniqueKey, item);
            }
        });
        return Lists.newArrayList(itemsMap.values());
    }

    private Date getMostPastTransactionDate(List<PaymentGatewayEvent> transactions) {
        if (CollectionUtils.isEmpty(transactions)) {
            return null;
        }
        Set<Date> transactionDates = transactions.stream()
                .filter(Objects::nonNull)
                .map(PaymentGatewayEvent::getTransactionDate)
                .filter(Objects::nonNull)
                .map(Calendar::getTime)
                .collect(Collectors.toSet());
        return Collections.min(transactionDates);
    }

    private <T> List<PaymentGatewayEvent> getTransactionsToCreate(
            List<PaymentGatewayEvent> paymentGatewayEvents,
            Function<PaymentGatewayEvent, String> paymentGatewayEventPrimaryKeyFunction,
            List<T> transactions,
            Function<T, String> transactionsPrimaryKeyFunction) {

        if (CollectionUtils.isEmpty(transactions)) {
            return paymentGatewayEvents;
        }

        List<Pair<PaymentGatewayEvent, T>> pairs = createPairs(
                paymentGatewayEvents,
                paymentGatewayEventPrimaryKeyFunction, null,
                transactions,
                transactionsPrimaryKeyFunction, null);

        log.info("Transaction pairs: {}", gson.toJson(pairs));

        return getSingleLefts(pairs);
    }

    private <C> List<CrmContact> getCrmContactsToCreate(
            List<CrmContact> crmContacts,
            Function<CrmContact, String> crmContactPrimaryKeyFunction,
            Function<CrmContact, String> crmContactSecondaryKeyFunction,
            List<C> contacts,
            Function<C, String> cPrimaryKeyFunction,
            Function<C, String> cSecondaryKeyFunction) {

        if (CollectionUtils.isEmpty(contacts)) {
            return crmContacts;
        }

        List<Pair<CrmContact, C>> pairs = createPairs(
                crmContacts,
                crmContactPrimaryKeyFunction, crmContactSecondaryKeyFunction,
                contacts,
                cPrimaryKeyFunction, cSecondaryKeyFunction
        );

        log.info("Contacts pairs: {}", gson.toJson(pairs));

        return getSingleLefts(pairs);
    }

    private <T> Map<String, T> mapItems(List<T> items, Function<T, String> mapFunction) {
        if (CollectionUtils.isEmpty(items) || Objects.isNull(mapFunction)) {
            return Collections.emptyMap();
        }
        Map<String, T> itemsMap = new HashMap<>();
        for (T item : items) {
            String key = mapFunction.apply(item);
            if (!StringUtils.isEmpty(key)) {
                itemsMap.put(key, item);
            }
        }
        return itemsMap;
    }

    private <L, R> List<Pair<L, R>> createPairs(
            List<L> lefts, Function<L, String> leftPrimaryKeyFunction, Function<L, String> leftSecondaryKeyFunction,
            List<R> rights, Function<R, String> rightPrimaryKeyFunction, Function<R, String> rightSecondaryKeyFunction) {
        if (CollectionUtils.isEmpty(lefts)) {
            // Can't create pairs for empty input
            return Collections.emptyList();
        }

        Map<String, R> rightsMappedByPrimaryKey = mapItems(rights, rightPrimaryKeyFunction);
        Map<String, R> rightsMappedBySecondaryKey = mapItems(rights, rightSecondaryKeyFunction);

        return lefts.stream()
                .map(left -> {
                    R right = null;
                    if (Objects.nonNull(leftPrimaryKeyFunction)) {
                        // plan A
                        right = rightsMappedByPrimaryKey.get(leftPrimaryKeyFunction.apply(left));
                    }
                    if (Objects.isNull(right) && Objects.nonNull(leftSecondaryKeyFunction)) {
                        // plan B
                        right = rightsMappedBySecondaryKey.get(leftSecondaryKeyFunction.apply(left));
                    }
                    return Pair.of(left, right);
                })
                .collect(Collectors.toList());
    }

    private <L, R> List<L> getSingleLefts(List<Pair<L, R>> pairs) {
        if (CollectionUtils.isEmpty(pairs)) {
            return Collections.emptyList();
        }
        List<L> singleLefts = pairs.stream()
                .filter(p -> Objects.isNull(p.getRight()))
                .map(Pair::getLeft)
                .collect(Collectors.toList());
        log.info("Singles: {}", gson.toJson(singleLefts));
        return singleLefts;
    }

}
