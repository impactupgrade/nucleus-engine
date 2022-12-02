package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.RaiselyClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
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
  public boolean eventIsFromPlatform(PaymentGatewayEvent event) {
    return RAISELY_APPLICATION_ID.equalsIgnoreCase(event.getApplication());
  }

  @Override
  public List<PaymentGatewayEvent> enrich(PaymentGatewayEvent event) throws Exception {
    String donationId = parseDonationId(event.getTransactionDescription());

    RaiselyClient.Donation donation = raiselyClient.getDonation(donationId);

    if (donation.items == null || donation.items.size() <= 1) {
      // not a split transaction -- return the original
      return List.of(event);
    } else {
      // TODO: Are there other types of items? Could there be a ticket + products + donation?
      List<RaiselyClient.DonationItem> donationItems = donation.items.stream().filter(i -> !"ticket".equalsIgnoreCase(i.type)).toList();
      List<RaiselyClient.DonationItem> ticketItems = donation.items.stream().filter(i -> "ticket".equalsIgnoreCase(i.type)).toList();
      if (ticketItems.isEmpty()) {
        // donations only, but this should never happen?
        event.addMetadata("payment_type", EnvironmentConfig.PaymentEventType.DONATION.name());
        return List.of(event);
      } else if (donationItems.isEmpty()) {
        // tickets only
        event.addMetadata("payment_type", EnvironmentConfig.PaymentEventType.TICKET.name());
        return List.of(event);
      } else if (donationItems.size() > 1 || ticketItems.size() > 1) {
        log.warn("Raisely donation {} had multiple donations and/or tickets; expected one of each, so skipping out of caution", donationId);
      } else {
        // one of each

        event.addMetadata("payment_type", EnvironmentConfig.PaymentEventType.DONATION.name());
        event.setTransactionAmountInDollars((double) ((donationItems.get(0).amount/100)));

        // TODO: This will have the same Stripe IDs! For most clients, this won't work, since we skip processing
        //  gifts if their Stripe ID already exists in the CRM. Talking to DR AU about how to handle. Nuke the IDs
        //  for one or the other?
        PaymentGatewayEvent clonedEvent = SerializationUtils.clone(event);
        clonedEvent.addMetadata("payment_type", EnvironmentConfig.PaymentEventType.TICKET.name());
        // if fees are covered, stick them on the TICKET, not the DONATION
        double coveredFee = donation.feeCovered ? donation.fee : 0.0;
        clonedEvent.setTransactionAmountInDollars((double) ((ticketItems.get(0).amount/100) + coveredFee));
      }
    }

    List<PaymentGatewayEvent> items = new ArrayList<>();
    for (RaiselyClient.DonationItem item : donation.items) {
      items.add(toPaymentGatewayEvent(donation, item, event));
    }

    return items;
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

  protected PaymentGatewayEvent toPaymentGatewayEvent(RaiselyClient.Donation donation, RaiselyClient.DonationItem item, PaymentGatewayEvent originalEvent) {
    PaymentGatewayEvent clonedEvent = SerializationUtils.clone(originalEvent);
    Double feeAmount = Double.valueOf(donation.fee);

    if (item.type.equalsIgnoreCase("ticket") && donation.feeCovered){
      clonedEvent.addMetadata("payment_type", EnvironmentConfig.PaymentEventType.TICKET.name());
      clonedEvent.setTransactionAmountInDollars((double) ((item.amount/100) + feeAmount));
    } else {
      clonedEvent.addMetadata("payment_type", EnvironmentConfig.PaymentEventType.DONATION.name());
      clonedEvent.setTransactionAmountInDollars((double) ((item.amount/100)));
    }
    return clonedEvent;
  }

}
