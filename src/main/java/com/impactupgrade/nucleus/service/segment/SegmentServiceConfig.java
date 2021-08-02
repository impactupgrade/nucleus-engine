package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.annotation.RequestScope;

import java.util.Locale;

@Configuration
public class SegmentServiceConfig {

    @Bean("primary")
    @RequestScope
    public CrmService crmService(Environment env) {
        return crmService(env, env.getConfig().crmPrimary);
    }

    @Bean("donations")
    @RequestScope
    public CrmService donationsCrmService(Environment env) {
        return crmService(env, env.getConfig().crmDonations);
    }

    @Bean("messaging")
    @RequestScope
    public CrmService messagingCrmService(Environment env) {
        return crmService(env, env.getConfig().crmMessaging);
    }

    @Bean
    public BloomerangCrmService bloomerangCrmService(Environment env) {
        return new BloomerangCrmService(env);
    }

    @Bean
    public HubSpotCrmService hubSpotCrmService() {
        return new HubSpotCrmService();
    }

    @Bean
    public SfdcCrmService sfdcCrmService() {
        return new SfdcCrmService();
    }

    private CrmService crmService(Environment env, String name) {
        if (Strings.isNullOrEmpty(name)) {
            if (Strings.isNullOrEmpty(env.getConfig().crmPrimary)) {
                throw new RuntimeException("define a crmPrimary in environment.json");
            }

            // by default, always use the primary
            return crmService(env, env.getConfig().crmPrimary);
        } else {
            name = name.toLowerCase(Locale.ROOT);
            if ("bloomerang".equalsIgnoreCase(name)) {
                return bloomerangCrmService(env);
            } else if ("hubspot".equalsIgnoreCase(name)) {
                return hubSpotCrmService();
            } else if ("salesforce".equalsIgnoreCase(name)) {
                return sfdcCrmService();
            }

            throw new RuntimeException("crm not found: " + name);
        }
    }

    @Bean("stripe")
    public PaymentGatewayService stripePaymentGatewayService() {
        return new StripePaymentGatewayService();
    }
}
