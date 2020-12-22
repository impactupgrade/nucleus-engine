package com.impactupgrade.common.paymentgateway;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

public class PaymentGatewayDeposit {

  private final Calendar date;

  private Map<String, Double> ledgerTotals = new HashMap<>();

  public PaymentGatewayDeposit(Calendar date) {
    this.date = date;
  }

  public Calendar getDate() {
    return date;
  }

  public double getTotal() {
    return ledgerTotals.values().stream().reduce(0.0, Double::sum);
  }

  public Map<String, Double> getLedgerTotals() {
    return ledgerTotals;
  }

  public void addTransaction(double totalReceived, String campaignId) {
    double ledgerTotal = ledgerTotals.getOrDefault(campaignId, 0.0) + totalReceived;
    ledgerTotals.put(campaignId, ledgerTotal);
  }
}
