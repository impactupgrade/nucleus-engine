package com.impactupgrade.common.crm.hubspot;

import com.impactupgrade.integration.hubspot.v1.HubSpotV1Client;

public class HubSpotClientFactory {

    private static final String API_KEY = System.getenv("HUBSPOT_KEY");
    private static final HubSpotV1Client CLIENT = new HubSpotV1Client(API_KEY);

    public static HubSpotV1Client client() {
        return CLIENT;
    }
}
