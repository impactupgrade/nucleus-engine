/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.impactupgrade.integration.hubspot.v1.HubSpotV1Client;
import com.impactupgrade.integration.hubspot.v3.HubSpotV3Client;
import com.impactupgrade.nucleus.environment.Environment;

public class HubSpotClientFactory {

    public static HubSpotV1Client v1Client(Environment env) {
        return new HubSpotV1Client(env.getConfig().hubspot.secretKey);
    }

    public static HubSpotV3Client v3Client(Environment env) {
        return new HubSpotV3Client(env.getConfig().hubspot.secretKey);
    }
}
