package com.impactupgrade.common.crm.hubspot;

import com.impactupgrade.integration.hubspot.v1.HubSpotV1Client;
import com.impactupgrade.integration.hubspot.v3.HubSpotV3Client;

public class HubSpotClientFactory {

    private static final String API_KEY = System.getenv("HUBSPOT_KEY");

    private static final HubSpotV1Client V1_CLIENT = new HubSpotV1Client(API_KEY);
    private static final HubSpotV3Client V3_CLIENT = new HubSpotV3Client(API_KEY);

    public static HubSpotV1Client v1Client() {
        return V1_CLIENT;
    }

    public static HubSpotV3Client v3Client() {
        return V3_CLIENT;
    }
}
