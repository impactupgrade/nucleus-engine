/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.impactupgrade.integration.hubspot.crm.v3.HubSpotCrmV3Client;
import com.impactupgrade.integration.hubspot.form.v3.FormV3Client;
import com.impactupgrade.integration.hubspot.v1.EngagementV1Client;
import com.impactupgrade.integration.hubspot.v1.HubSpotV1Client;
import com.impactupgrade.nucleus.environment.Environment;

public class HubSpotClientFactory {

    public static HubSpotV1Client v1Client(Environment env) {
        return new HubSpotV1Client(env.getConfig().hubspot.secretKey);
    }

    public static HubSpotCrmV3Client crmV3Client(Environment env) {
        return new HubSpotCrmV3Client(env.getConfig().hubspot.secretKey);
    }

    public static FormV3Client formV3Client(Environment env) {
        return new FormV3Client(env.getConfig().hubspot.portalId);
    }

    public static EngagementV1Client engagementV1Client(Environment env) {
        return new EngagementV1Client(env.getConfig().hubspot.secretKey);
    }
}
