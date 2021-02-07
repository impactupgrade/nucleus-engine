package com.impactupgrade.common.environment;

import com.impactupgrade.common.backup.BackupController;
import com.impactupgrade.common.crm.AggregateCrmDestinationService;
import com.impactupgrade.common.crm.CrmSourceService;
import com.impactupgrade.common.crm.sfdc.SfdcClient;
import com.impactupgrade.common.crm.sfdc.SfdcController;
import com.impactupgrade.common.exception.NotImplementedException;
import com.impactupgrade.common.paymentgateway.DonationService;
import com.impactupgrade.common.paymentgateway.DonorService;
import com.impactupgrade.common.paymentgateway.PaymentGatewayController;
import com.impactupgrade.common.paymentgateway.paymentspring.PaymentSpringController;
import com.impactupgrade.common.paymentgateway.stripe.StripeController;
import com.impactupgrade.common.twilio.TwilioController;

import javax.ws.rs.container.ContainerRequestContext;

/**
 * This class allows the app to provide all the custom data and flows we need that are super-specific to the individual
 * org's environment.
 */
public class Environment {

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
  public TwilioController twilioController() { return new TwilioController(); }

  // TODO: To date, there hasn't been a need to customize these at the request level! For now, keep them here,
  // since RequestEnvironment primarily works at the integration/client level.

  public DonationService donationService() { return new DonationService(this); }
  public DonorService donorService() { return new DonorService(this); }

  public SfdcClient sfdcClient() { return new SfdcClient(this); }
  public SfdcClient sfdcClient(String username, String password) { return new SfdcClient(this, username, password); }

  /**
   * Define a single CRM as the end-all source-of-truth for retrievals and queries.
   */
  public CrmSourceService crmSourceService() {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "crmSource");
  }

  /**
   * Define one or more CRMs as the destinations for donation data.
   */
  public AggregateCrmDestinationService crmDonationDestinationServices() {
    throw new NotImplementedException(getClass().getSimpleName() + "." + "crmDonationDestinations");
  }

  // TODO: other destinations: SMS interactions, etc.

  /**
   * Many organizations will only have one RequestEnvironment. But some (DR, Donation Spring, etc.) are by-request.
   * In those cases, they'll need to provide their own constructor (and generally, a unique Controller method
   * to go with it). So make it obvious that this is only the default.
   */
  public RequestEnvironment newRequestEnvironment(ContainerRequestContext context) {
    return new RequestEnvironment(context);
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
}
