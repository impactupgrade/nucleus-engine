package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.RaiselyClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmDonation;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RaiselyEnrichmentService implements EnrichmentService {
  protected static final Logger log = LogManager.getLogger(RaiselyEnrichmentService.class);

  protected String RAISELY_APPLICATION_ID;

  protected RaiselyClient raiselyClient;

  @Override
  public String name() {
    return "raisely";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return !Strings.isNullOrEmpty(env.getConfig().raisely.username);
  }

  @Override
  public void init(Environment env) {
    raiselyClient = new RaiselyClient(env);
    RAISELY_APPLICATION_ID = env.getConfig().raisely.stripeAppId;
  }


  @Override
  public boolean eventIsFromPlatform(CrmDonation crmDonation) {
    return RAISELY_APPLICATION_ID.equalsIgnoreCase(crmDonation.application);
  }

  @Override
  public void enrich(CrmDonation crmDonation) throws Exception {
    String donationId = parseDonationId(crmDonation.description);
    RaiselyClient.Donation raiselyDonation = raiselyClient.getDonation(donationId);

    if (raiselyDonation == null || raiselyDonation.items == null || raiselyDonation.items.size() <= 1) {
      // not a split transaction -- return the original
    } else {
      // TODO: Are there other types of items? Could there be a ticket + products + donation?
      List<RaiselyClient.DonationItem> donationItems = raiselyDonation.items.stream().filter(i -> !"ticket".equalsIgnoreCase(i.type)).toList();
      List<RaiselyClient.DonationItem> ticketItems = raiselyDonation.items.stream().filter(i -> "ticket".equalsIgnoreCase(i.type)).toList();
      if (ticketItems.isEmpty()) {
        // donations only, but this should never happen?
        crmDonation.transactionType = EnvironmentConfig.TransactionType.DONATION;
      } else if (donationItems.isEmpty()) {
        // tickets only
        crmDonation.transactionType = EnvironmentConfig.TransactionType.TICKET;
      } else if (donationItems.size() > 1 || ticketItems.size() > 1) {
        log.warn("Raisely donation {} had multiple donations and/or tickets; expected one of each, so skipping out of caution", donationId);
      } else {
        // one of each -- let the ticket be the primary and donation secondary

        crmDonation.transactionType = EnvironmentConfig.TransactionType.TICKET;
        // if fees are covered, stick them on the TICKET, not the DONATION
        double coveredFee = raiselyDonation.feeCovered ? (raiselyDonation.fee / 100.0) : 0.0;
        crmDonation.amount = (ticketItems.get(0).amount / 100.0) + coveredFee;

        // TODO: This will have the same Stripe IDs! For most clients, this won't work, since we skip processing
        //  gifts if their Stripe ID already exists in the CRM. Talking to DR AU about how to handle. Nuke the IDs
        //  for one or the other?

        // TODO: Total hack. These contain raw CRM objects, which often are not serializable. Back them up, then restore.
        Object crmAccountRawObject = crmDonation.account.rawObject;
        Object crmContactRawObject = crmDonation.contact.rawObject;
        crmDonation.account.rawObject = null;
        crmDonation.contact.rawObject = null;

        CrmDonation clonedCrmDonation = SerializationUtils.clone(crmDonation);

        crmDonation.account.rawObject = crmAccountRawObject;
        crmDonation.contact.rawObject = crmContactRawObject;
        clonedCrmDonation.account.rawObject = crmAccountRawObject;
        clonedCrmDonation.contact.rawObject = crmContactRawObject;

        clonedCrmDonation.transactionType = EnvironmentConfig.TransactionType.DONATION;
        clonedCrmDonation.amount = donationItems.get(0).amount / 100.0;
        // Downstream, we need a notion of parent/child to handle secondary events within the CrmServices.
        clonedCrmDonation.parent = crmDonation;
        crmDonation.children.add(clonedCrmDonation);
      }
    }
  }

  //HELPERS

  protected String parseDonationId(String description){
    Pattern r = Pattern.compile("Donation \\((\\d+)\\).*");
    Matcher m = r.matcher(description);
    if (m.find()) {
      return m.group(1);
    } else {
      return null;
    }
  }

}
