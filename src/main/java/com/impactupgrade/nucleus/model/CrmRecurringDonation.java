/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

public record CrmRecurringDonation(String id, String subscriptionId, String customerId, Double amount, String paymentGatewayName) {

    // TODO: Why allow this?
    public CrmRecurringDonation(String id) {
        this(id, null, null, null, null);
    }
}
