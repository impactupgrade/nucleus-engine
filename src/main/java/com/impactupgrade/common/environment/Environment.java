package com.impactupgrade.common.environment;

import com.impactupgrade.common.backup.BackupController;
import com.impactupgrade.common.crm.AggregateCrmDestinationService;
import com.impactupgrade.common.crm.CrmSourceService;
import com.impactupgrade.common.crm.sfdc.SfdcClient;
import com.impactupgrade.common.crm.sfdc.SfdcController;
import com.impactupgrade.common.messaging.MessagingService;
import com.impactupgrade.common.messaging.twilio.TwilioController;
import com.impactupgrade.common.paymentgateway.DonationService;
import com.impactupgrade.common.paymentgateway.DonorService;
import com.impactupgrade.common.paymentgateway.PaymentGatewayController;
import com.impactupgrade.common.paymentgateway.paymentspring.PaymentSpringController;
import com.impactupgrade.common.paymentgateway.stripe.StripeClient;
import com.impactupgrade.common.paymentgateway.stripe.StripeController;
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
   * Define one CRMs as the primary integration point for SMS engagement.
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

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // UNIQUE FIELDS
  // TODO: Make these all env vars?
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public String[] accountMetadataKeys() {
    return new String[]{"sf_account"};
  }

  public String[] campaignMetadataKeys() {
    return new String[]{"sf_campaign"};
  }

  public String[] contactMetadataKeys() {
    return new String[]{"sf_contact"};
  }

  public String[] recordTypeMetadataKeys() {
    return new String[]{"sf_opp_record_type"};
  }
}
