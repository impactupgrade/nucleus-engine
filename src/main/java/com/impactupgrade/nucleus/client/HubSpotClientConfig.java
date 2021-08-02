/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.impactupgrade.integration.hubspot.v1.HubSpotV1Client;
import com.impactupgrade.integration.hubspot.v3.HubSpotV3Client;
import com.impactupgrade.nucleus.environment.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HubSpotClientConfig {

    @Bean
    public HubSpotV1Client hubspotV1Client(Environment env) {
        return new HubSpotV1Client(env.getConfig().hubspot.secretKey);
    }

    @Bean
    public HubSpotV3Client hubspotV3Client(Environment env) {
        return new HubSpotV3Client(env.getConfig().hubspot.secretKey);
    }
}
