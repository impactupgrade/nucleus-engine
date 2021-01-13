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
import com.impactupgrade.common.paymentgateway.stripe.StripeClient;
import com.impactupgrade.common.paymentgateway.stripe.StripeController;
import com.impactupgrade.common.twilio.TwilioController;

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

  public DonationService donationService() { return new DonationService(this); }
  public DonorService donorService() { return new DonorService(this); }

  public SfdcClient sfdcClient() { return new SfdcClient(this); }
  public SfdcClient sfdcClient(String username, String password) { return new SfdcClient(this, username, password); }
  public StripeClient stripeClient() { return new StripeClient(this); }

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
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // UNIQUE FIELDS
  // TODO: Make these all env vars?
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  public String[] campaignMetadataKeys() {
    return new String[]{"sf_campaign"};
  }

  public String defaultCurrency() {
    return "usd";
  }
}
