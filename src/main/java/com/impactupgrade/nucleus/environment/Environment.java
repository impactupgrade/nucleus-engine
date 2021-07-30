/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.environment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.SfdcBulkClient;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.client.SfdcMetadataClient;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.service.logic.DonationService;
import com.impactupgrade.nucleus.service.logic.DonorService;
import com.impactupgrade.nucleus.service.logic.MessagingService;
import com.impactupgrade.nucleus.service.segment.BloomerangCrmService;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.service.segment.HubSpotCrmService;
import com.impactupgrade.nucleus.service.segment.PaymentGatewayService;
import com.impactupgrade.nucleus.service.segment.SfdcCrmService;
import com.impactupgrade.nucleus.service.segment.StripePaymentGatewayService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Every action within Nucleus is kicked off by either an HTTP Request or a manual script. This class
 * is responsible for carrying the context of the flow throughout all processing steps, defining how
 * the organization's set of platforms, configurations, and customizations.
 *
 * We can't statically define this, one per organization. Instead, the whole concept is dynamic,
 * per-flow. This allows multitenancy (ex: DR having a single instance with multiple "Funding Nations",
 * each with their own unique characteristics and platform keys) and dynamic
 * logic based on additional context. We also determine the context at this level (rather than
 * at the application level) to queue us up for SaaS models with DB-driven configs.
 */
public class Environment {

  private static final Logger log = LogManager.getLogger(Environment.class);

  // Whenever possible, we focus on being configuration-driven using one, large JSON file.
  private final EnvironmentConfig config = EnvironmentConfig.init();

  // Additional context, if available.
  // It seems odd to track URI and headers separately, rather than simply storing HttpServletRequest itself.
  // However, many (most?) frameworks don't allow aspects of request to be accessed over and over. Due to the mechanics,
  // some only allow it to be streamed once.
  private String uri = null;
  private final Map<String, String> headers = new HashMap<>();
  private MultivaluedMap<String, String> otherContext = new MultivaluedHashMap<>();

  public EnvironmentConfig getConfig() {
    return config;
  }

  public void setRequest(HttpServletRequest request) {
    if (request != null) {
      uri = request.getRequestURI();

      Enumeration<String> headerNames = request.getHeaderNames();
      while (headerNames.hasMoreElements()) {
        String headerName = headerNames.nextElement();
        headers.put(headerName, request.getHeader(headerName));
      }
    }
  }

  public String getUri() {
    return uri;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public MultivaluedMap<String, String> getOtherContext() {
    return otherContext;
  }

  public void setOtherContext(MultivaluedMap<String, String> otherContext) {
    this.otherContext = otherContext;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // REGISTRY
  // Provides a simple registry of services, clients, etc. to allow subprojects to override concepts as needed!
  // Yes, we could use Spring/ServiceRegistry/OSGi. But holding off on frameworks until we absolutely need them...
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // logic services
  public DonationService donationService() { return new DonationService(this); }
  public DonorService donorService() { return new DonorService(this); }
  public MessagingService messagingService() { return new MessagingService(this); }

  /*
  return config.platforms.crm.stream()
        .filter(crm -> crm.name.equalsIgnoreCase(key))
        .findFirst()
        .orElseThrow(() -> new RuntimeException("not implemented"));
   */

  // segment services
  public CrmService crmService(String name) {
    if (Strings.isNullOrEmpty(name)) {
      if (Strings.isNullOrEmpty(config.crmPrimary)) {
        throw new RuntimeException("define a crmPrimary in environment.json");
      }

      // by default, always use the primary
      return crmService(config.crmPrimary);
    } else {
      name = name.toLowerCase(Locale.ROOT);
      if ("bloomerang".equalsIgnoreCase(name)) {
        return new BloomerangCrmService(this);
      } else if ("hubspot".equalsIgnoreCase(name)) {
        return new HubSpotCrmService(this);
      } else if ("salesforce".equalsIgnoreCase(name)) {
        return new SfdcCrmService(this);
      }

      throw new RuntimeException("crm not found: " + name);
    }
  }
  public PaymentGatewayService paymentGatewayService(String name) {
    name = name.toLowerCase(Locale.ROOT);
    if ("stripe".equalsIgnoreCase(name)) {
      return new StripePaymentGatewayService(this);
    }

    throw new RuntimeException("paymentGateway not found: " + name);
  }

  // vendor clients
  public SfdcClient sfdcClient() { return new SfdcClient(this); }
  public SfdcClient sfdcClient(String username, String password) { return new SfdcClient(this, username, password); }
  public SfdcBulkClient sfdcBulkClient() { return new SfdcBulkClient(this); }
  public SfdcMetadataClient sfdcMetadataClient() { return new SfdcMetadataClient(this); }
  public StripeClient stripeClient() { return new StripeClient(this); }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // DYNAMIC CONFIRMATION
  // Most configuration is static and should be in environment.json. However, some pieces are dynamic and depend on
  // processing context. Allow organization to override these at the request level.
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public String getCurrency() {
    return "usd";
  }
}
