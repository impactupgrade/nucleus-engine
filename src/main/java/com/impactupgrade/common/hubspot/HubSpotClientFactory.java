package com.impactupgrade.common.hubspot;

import com.impactupgrade.integration.hubspot.HubSpotClient;

public class HubSpotClientFactory {

    // TODO: stupidly simple, but leaving this pattern in case we need something more complex in the future

    private static final String API_KEY = System.getenv("HUBSPOT.KEY");
    private static final HubSpotClient CLIENT = new HubSpotClient(API_KEY);

    public static HubSpotClient client() {
        return CLIENT;
    }
}
