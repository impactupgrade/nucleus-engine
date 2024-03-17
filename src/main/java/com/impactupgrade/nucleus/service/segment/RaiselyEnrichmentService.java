package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.RaiselyClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmDonation;
import org.apache.commons.lang3.SerializationUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RaiselyEnrichmentService implements EnrichmentService {

  protected Environment env;
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
    this.env = env;
    raiselyClient = env.raiselyClient();
  }


  @Override
  public boolean eventIsFromPlatform(CrmDonation crmDonation) {
    return env.getConfig().raisely.stripeAppId.equalsIgnoreCase(crmDonation.application);
  }

  @Override
  public void enrich(CrmDonation crmDonation) throws Exception {
    String donationId = parseDonationId(crmDonation.description);
    RaiselyClient.Donation raiselyDonation = raiselyClient.getDonation(donationId);

    if (raiselyDonation == null || raiselyDonation.items == null || raiselyDonation.items.isEmpty()) {
      // return the original
    } else {
      // TODO: Are there other types of items? Could there be a ticket + products + donation?
      List<RaiselyClient.DonationItem> donationItems = raiselyDonation.items.stream().filter(i -> !"ticket".equalsIgnoreCase(i.type)).toList();
      List<RaiselyClient.DonationItem> ticketItems = raiselyDonation.items.stream().filter(i -> "ticket".equalsIgnoreCase(i.type)).toList();
      double coveredFee = raiselyDonation.feeCovered ? (raiselyDonation.fee / 100.0) : 0.0;
      if (ticketItems.isEmpty()) {
        // donations only
        crmDonation.transactionType = EnvironmentConfig.TransactionType.DONATION;
        crmDonation.amount = calculateTotalAmount(donationItems) + coveredFee;
      } else if (donationItems.isEmpty()) {
        // tickets only
        crmDonation.transactionType = EnvironmentConfig.TransactionType.TICKET;
        crmDonation.amount = calculateTotalAmount(ticketItems) + coveredFee;
      } else {
        // multiple of each -- let the ticket be the primary and donation secondary
        crmDonation.transactionType = EnvironmentConfig.TransactionType.TICKET;
        double ticketAmount = calculateTotalAmount(ticketItems);
        double donationAmount = calculateTotalAmount(donationItems);
        crmDonation.amount = ticketAmount + coveredFee;

        // TODO: This will have the same Stripe IDs! For most clients, this won't work, since we skip processing
        //  gifts if their Stripe ID already exists in the CRM. Talking to DR AU about how to handle. Nuke the IDs
        //  for one or the other?

        // TODO: Total hack. These contain raw CRM objects, which often are not serializable. Back them up, then restore.
        Object crmAccountRawObject = crmDonation.account.crmRawObject;
        Object crmContactRawObject = crmDonation.contact.crmRawObject;
        crmDonation.account.crmRawObject = null;
        crmDonation.contact.crmRawObject = null;

        CrmDonation clonedCrmDonation = SerializationUtils.clone(crmDonation);

        crmDonation.account.crmRawObject = crmAccountRawObject;
        crmDonation.contact.crmRawObject = crmContactRawObject;
        clonedCrmDonation.account.crmRawObject = crmAccountRawObject;
        clonedCrmDonation.contact.crmRawObject = crmContactRawObject;

        clonedCrmDonation.transactionType = EnvironmentConfig.TransactionType.DONATION;
        clonedCrmDonation.amount = donationAmount;
        // Downstream, we need a notion of parent/child to handle secondary events within the CrmServices.
        clonedCrmDonation.parent = crmDonation;
        crmDonation.children.add(clonedCrmDonation);
      }
    }
  }

  //TODO: move to utility class?
  public String parseDonationId(String description) {
    if (Strings.isNullOrEmpty(description)) {
      return null;
    }

    Pattern r = Pattern.compile("Donation \\((\\d+)\\).*");
    Matcher m = r.matcher(description);
    if (m.find()) {
      return m.group(1);
    } else {
      return null;
    }
  }

  //HELPERS
  protected double calculateTotalAmount(List<RaiselyClient.DonationItem> items) {
    double totalAmount = 0.0;
    for (RaiselyClient.DonationItem item : items) {
      totalAmount += item.amount / 100.0;
    }
    return totalAmount;
  }
}
