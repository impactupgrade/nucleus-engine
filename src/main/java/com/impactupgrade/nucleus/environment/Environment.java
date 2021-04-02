package com.impactupgrade.nucleus.environment;

import com.impactupgrade.nucleus.controller.BackupController;
import com.impactupgrade.nucleus.service.segment.PaymentGatewayService;
import com.impactupgrade.nucleus.service.segment.AggregateCrmDestinationService;
import com.impactupgrade.nucleus.service.segment.CrmSourceService;
import com.impactupgrade.nucleus.client.SfdcClient;
import com.impactupgrade.nucleus.controller.SfdcController;
import com.impactupgrade.nucleus.service.logic.MessagingService;
import com.impactupgrade.nucleus.controller.TwilioController;
import com.impactupgrade.nucleus.service.logic.DonationService;
import com.impactupgrade.nucleus.service.logic.DonorService;
import com.impactupgrade.nucleus.controller.PaymentGatewayController;
import com.impactupgrade.nucleus.controller.PaymentSpringController;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.controller.StripeController;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.glassfish.jersey.server.ResourceConfig;

import javax.servlet.http.HttpServletRequest;

/**
 * This class allows the app to provide all the custom data and flows we need that are super-specific to the individual
 * org's environment.
 */
public class Environment {

  private static final Logger log = LogManager.getLogger(Environment.class);

  // Whenever possible, we focus on being configuration-driven using one, large JSON file.
  private final EnvironmentConfig config = EnvironmentConfig.init();
  public EnvironmentConfig config() { return config; }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // REGISTRY
  // Provides a simple registry of controllers, services, etc. to allow subprojects to override concepts as needed!
  // Yes, we could use Spring/ServiceRegistry/OSGi. But holding off on frameworks until we absolutely need them...
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public BackupController backupController() { return new BackupController(); }
  public PaymentGatewayController paymentGatewayController() { return new PaymentGatewayController(this); }
  public PaymentSpringController paymentSpringController() { return new PaymentSpringController(this); }
  public SfdcController sfdcController() { return new SfdcController(this); }
  public StripeController stripeController() { return new StripeController(this); }
  public TwilioController twilioController() { return new TwilioController(this); }

  // TODO: To date, there hasn't been a need to customize these at the request level! For now, keep them here,
  // since RequestEnvironment primarily works at the integration/client level.

  public DonationService donationService() { return new DonationService(this); }
  public DonorService donorService() { return new DonorService(this); }

  public MessagingService messagingService() { return new MessagingService(this); }

  public SfdcClient sfdcClient() { return new SfdcClient(this); }
  public SfdcClient sfdcClient(String username, String password) { return new SfdcClient(this, username, password); }

  // TODO: replace these methods with string-based selectors in json, once merged with the hubspot branch

  /**
   * Define primary PaymentGateway
   */
  public PaymentGatewayService paymentGatewayService() {
    log.error("NOT IMPLEMENTED");
    return null;
  }

  /**
   * Define a single CRM as the end-all source-of-truth for retrievals and queries.
   */
  public CrmSourceService crmSourceService() {
    log.error("NOT IMPLEMENTED");
    return null;
  }

  /**
   * Define one or more CRMs as the destinations for donation data.
   */
  public AggregateCrmDestinationService crmDonationDestinationServices() {
    log.error("NOT IMPLEMENTED");
    return null;
  }

  /**
   * Define one CRM as the primary integration point for SMS engagement.
   */
  public CrmSourceService crmSMSSourceService() {
    log.error("NOT IMPLEMENTED");
    return null;
  }

  /**
   * Define one or more CRMs as the primary integration point for SMS engagement.
   */
  public AggregateCrmDestinationService crmSMSDestinationServices() {
    log.error("NOT IMPLEMENTED");
    return null;
  }

  /**
   * Allow orgs to add custom Controllers, etc.
   */
  public void registerAPIControllers(ResourceConfig apiConfig) {}

  /**
   * Allow orgs to add completely-custom servlets (CXF, etc.)
   */
  public void registerServlets(ServletContextHandler servletHandler) {}

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // UTILITY
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Many organizations will only have one RequestEnvironment. But some (DR, Donation Spring, etc.) are by-request.
   * In those cases, they'll need to provide their own constructor (and generally, a unique Controller method
   * to go with it). So make it obvious that this is only the default.
   */
  public RequestEnvironment newRequestEnvironment(HttpServletRequest request) {
    return new RequestEnvironment(request);
  }

  /**
   * Much like Environment, this allows context-specific info to be set *per request*. Examples: SaaS flexibility,
   * orgs that have multiple keys within them (DR FNs), etc.
   *
   * Note that not every processing flow needs this! Allow it to be built on-demand by the Controller methods calling
   * Services/Clients where this is truly required.
   *
   * We embed it here to make sure the constructor isn't used directly in hub-common. Force the above method to allow overrides!
   */
  public static class RequestEnvironment {

    protected final HttpServletRequest request;

    protected RequestEnvironment(HttpServletRequest request) {
      this.request = request;
    }

    public StripeClient stripeClient() {
      return new StripeClient(System.getenv("STRIPE_KEY"));
    }

    public String currency() {
      return "usd";
    }
  }
}