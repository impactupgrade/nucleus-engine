package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.RaiselyClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RaiselyEnrichmentService implements EnrichmentService {
  protected static final Logger log = LogManager.getLogger(RaiselyEnrichmentService.class);
  protected RaiselyClient raiselyClient;
  protected Environment env;
  protected final String RAISELY_APPLICATION_ID = env.getConfig().raisely.stripeAppId; //'ca_7T2hPQBZLmYBN0AJqvTUEVpYSDxnWqz4' commenting here temporarily
  @Override
  public String name() {
    return "raisely";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return !Strings.isNullOrEmpty(env.getConfig().raisely.accessToken);
  }

  @Override
  public void init(Environment env) {
    this.raiselyClient = new RaiselyClient(env);
  }


  public boolean eventIsFromPlatform(PaymentGatewayEvent event){
    return event.getMetadataValue("application").equals(RAISELY_APPLICATION_ID);
  }

  public List<PaymentGatewayEvent> enrich(PaymentGatewayEvent event) throws Exception {
    String donationId = parseDonationId(event.getMetadataValue("description"));
    Map<PaymentGatewayEvent, String> donationItems = separateDonationItems(donationId, event);

    for (PaymentGatewayEvent donationItem : donationItems.keySet()){
      if (donationItems.get(donationItem).equals("ticket")){
        //Set record type 01228000000TD7KAAW Sales
        donationItem.addMetadata(env.getConfig().salesforce.fieldDefinitions.opportunityRecordType, env.getConfig().paymentEventTypeIds.get(EnvironmentConfig.paymentEventType.SALES));
      }else{
        //set record type 01228000000gOGpAAM Donation
        donationItem.addMetadata(env.getConfig().salesforce.fieldDefinitions.opportunityRecordType, env.getConfig().paymentEventTypeIds.get(EnvironmentConfig.paymentEventType.DONATION));
      }
    }
    return (List<PaymentGatewayEvent>) donationItems.keySet();
  }

  //HELPERS

  private String parseDonationId(String description){
    //Issue to think through -> This will break if the Stripe description format changes
    //Right now just stripping away all non digit characters and using the first 8 (Donation ID) since it is coming first
    String donationId = description.replaceAll("[^0-9]","");
    donationId = donationId.substring(0,7);
    return donationId;
  }

  private Map<PaymentGatewayEvent,String> separateDonationItems(String donationId, PaymentGatewayEvent event){
    Map<PaymentGatewayEvent,String> items = new HashMap<>();
    RaiselyClient.DonationResponse donation = raiselyClient.getDonation(donationId);

    for (RaiselyClient.DonationItem item : donation.items){
      items.put(toPaymentGatewayEvent(donation, item, event), item.type);
    }
    return items;
  }

  private PaymentGatewayEvent toPaymentGatewayEvent(RaiselyClient.DonationResponse donation, RaiselyClient.DonationItem item, PaymentGatewayEvent event){
    PaymentGatewayEvent separateEvent = event;
    Double feeAmount = Double.valueOf(donation.fee);
    if (item.type.equals("ticket") && donation.feeCovered){
      event.setTransactionAmountInDollars((double) ((item.amount/100) + feeAmount));
    }else {
      event.setTransactionAmountInDollars((double) ((item.amount/100)));
    }
    return event;
  }

}
