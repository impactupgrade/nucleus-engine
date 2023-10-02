package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmDonation;
import org.apache.commons.lang3.SerializationUtils;

public class CustomDonationsEnrichmentService implements EnrichmentService{
  protected Environment env;
  @Override
  public boolean eventIsFromPlatform(CrmDonation crmDonation) {
    return env.getConfig().customDonations.stripeAppId.equalsIgnoreCase(crmDonation.application);
  }

  @Override
  public void enrich(CrmDonation crmDonation) throws Exception {
    if (eventIsFromPlatform(crmDonation)) {
      // Remove Fees From  Donation Amount
      Double feesPaid = Double.parseDouble(crmDonation.getMetadataValue(env.getConfig().customDonations.feeField));
      crmDonation.amount = crmDonation.amount - feesPaid;

      // Separate fees into a child object (same method used in the Raisely enricher)
      Object crmAccountRawObject = crmDonation.account.crmRawObject;
      Object crmContactRawObject = crmDonation.contact.crmRawObject;
      crmDonation.account.crmRawObject = null;
      crmDonation.contact.crmRawObject = null;

      CrmDonation feesCrmDonationClone = SerializationUtils.clone(crmDonation);

      crmDonation.account.crmRawObject = crmAccountRawObject;
      crmDonation.contact.crmRawObject = crmContactRawObject;
      feesCrmDonationClone.account.crmRawObject = crmAccountRawObject;
      feesCrmDonationClone.contact.crmRawObject = crmContactRawObject;

      feesCrmDonationClone.transactionType = EnvironmentConfig.TransactionType.DONATION;
      feesCrmDonationClone.amount = feesPaid;

      feesCrmDonationClone.parent = crmDonation;
      crmDonation.children.add(feesCrmDonationClone);

    }

  }

  @Override
  public String name() {
    return "customDonations";
  }

  @Override
  public boolean isConfigured(Environment env) {
    //TODO: not checking for a username here because we shouldn't need a client for this enricher
    return !Strings.isNullOrEmpty(env.getConfig().customDonations.stripeAppId);
  }

  @Override
  public void init(Environment env) {
    this.env = env;
  }
}
