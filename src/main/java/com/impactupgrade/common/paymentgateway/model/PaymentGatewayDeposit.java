package com.impactupgrade.common.paymentgateway.model;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

public class PaymentGatewayDeposit {

  private Calendar date;
  private String url;

  private final Map<String, Ledger> ledgers = new HashMap<>();

  public Calendar getDate() {
    return date;
  }

  public void setDate(Calendar date) {
    this.date = date;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public double getGross() {
    return ledgers.values().stream().map(l -> l.gross).reduce(0.0, Double::sum);
  }

  public double getNet() {
    return ledgers.values().stream().map(l -> l.net).reduce(0.0, Double::sum);
  }

  public double getFees() {
    return getGross() - getNet();
  }

  public Map<String, Ledger> getLedgers() {
    return ledgers;
  }

  public void addTransaction(double gross, double net, String parentCampaignId, String parentCampaignName,
      String campaignId, String campaignName) {
    if (!ledgers.containsKey(parentCampaignId)) {
      Ledger ledger = new Ledger();
      ledger.campaignName = parentCampaignName;
      ledgers.put(parentCampaignId, ledger);
    }
    if (!ledgers.get(parentCampaignId).subLedgers.containsKey(campaignId)) {
      Ledger ledger = new Ledger();
      ledger.campaignName = campaignName;
      ledgers.get(parentCampaignId).subLedgers.put(campaignId, ledger);
    }

    ledgers.get(parentCampaignId).gross += gross;
    ledgers.get(parentCampaignId).net += net;
    ledgers.get(parentCampaignId).subLedgers.get(campaignId).gross += gross;
    ledgers.get(parentCampaignId).subLedgers.get(campaignId).net += net;
  }

  public void addTransaction(double gross, double net, String campaignId, String campaignName) {
    if (!ledgers.containsKey(campaignId)) {
      Ledger ledger = new Ledger();
      ledger.campaignName = campaignName;
      ledgers.put(campaignId, ledger);
    }

    ledgers.get(campaignId).gross += gross;
    ledgers.get(campaignId).net += net;
  }

  public static class Ledger {
    private String campaignName;
    private double gross = 0.0;
    private double net = 0.0;
    private final Map<String, Ledger> subLedgers = new HashMap<>();

    public String getCampaignName() {
      return campaignName;
    }

    public double getGross() {
      return gross;
    }

    public double getNet() {
      return net;
    }

    public double getFees() {
      return gross - net;
    }

    public Map<String, Ledger> getSubLedgers() {
      return subLedgers;
    }
  }
}
