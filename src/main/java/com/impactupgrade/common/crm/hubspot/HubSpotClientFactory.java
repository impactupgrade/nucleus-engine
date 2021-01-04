package com.impactupgrade.common.crm.hubspot;

import com.impactupgrade.integration.hubspot.HubSpotClient;

public class HubSpotClientFactory {

    private static final String API_KEY = System.getenv("HUBSPOT_KEY");
    private static final HubSpotClient CLIENT = new HubSpotClient(API_KEY);

    public static HubSpotClient client() {
        return CLIENT;
    }
}
