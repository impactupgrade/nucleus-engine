/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.environment;

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
public class ProcessContext {

  private static final Logger log = LogManager.getLogger(ProcessContext.class);

  // Whenever possible, we focus on being configuration-driven using one, large JSON file.
  // But this is limited to static config. Anything dynamic instead happens at the superclass level.
  protected Environment env = Environment.init();

  // Additional context, if available.
  protected HttpServletRequest request = null;
  protected MultivaluedMap<String, String> otherContext = new MultivaluedHashMap<>();

  // package-private, allowing only the factory to init
  protected ProcessContext() {}

  public Environment getEnv() {
    return env;
  }

  public HttpServletRequest getRequest() {
    return request;
  }

  public void setRequest(HttpServletRequest request) {
    this.request = request;
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

  public CrmService crmService() {
    String platformName = env.platforms.crm.name;
    if ("salesforce".equalsIgnoreCase(platformName)) {
      return new SfdcCrmService(this);
    } else if ("hubspot".equalsIgnoreCase(platformName)) {
      return new HubSpotCrmService(this);
    } else if ("bloomerang".equalsIgnoreCase(platformName)) {
      return new BloomerangCrmService(this);
    }

    throw new RuntimeException("not implemented");
  }
  public DonationService donationService() { return new DonationService(this); }
  public DonorService donorService() { return new DonorService(this); }
  public MessagingService messagingService() { return new MessagingService(this); }
  public PaymentGatewayService paymentGatewayService() {
    String platformName = env.platforms.paymentGateway.name;
    if ("stripe".equalsIgnoreCase(platformName)) {
      return new StripePaymentGatewayService(this);
    }

    throw new RuntimeException("not implemented");
  }

  public SfdcBulkClient sfdcBulkClient() { return new SfdcBulkClient(this); }
  public SfdcClient sfdcClient() { return new SfdcClient(this); }
  public SfdcClient sfdcClient(String username, String password) { return new SfdcClient(this, username, password); }
  public SfdcMetadataClient sfdcMetadataClient() { return new SfdcMetadataClient(this); }
  public StripeClient stripeClient() { return new StripeClient(); }
}
