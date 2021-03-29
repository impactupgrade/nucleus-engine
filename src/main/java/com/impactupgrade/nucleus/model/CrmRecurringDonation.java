package com.impactupgrade.nucleus.model;

public record CrmRecurringDonation(String id, String accountId, String subscriptionId, Double amount) {

    public CrmRecurringDonation(String id) {
        this(id, null, null, null);
    }
}
