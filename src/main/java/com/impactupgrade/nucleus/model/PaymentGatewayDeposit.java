/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.model;

import com.google.common.base.Strings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PaymentGatewayDeposit {

  private static final Logger log = LogManager.getLogger(PaymentGatewayDeposit.class.getName());

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

  public double getNet() { return ledgers.values().stream().map(l -> l.net).reduce(0.0, Double::sum); }

  public double getFees() {
    return ledgers.values().stream().map(l -> l.fees).reduce(0.0, Double::sum);
  }

  public double getRefunds() { return ledgers.values().stream().map(l -> l.refunds).reduce(0.0, Double::sum); }

  public Map<String, Ledger> getLedgers() {
    return ledgers;
  }

  // TODO: May need to rethink the following, since hierarchies of funds was somewhat of a TER-specific concept.

  public void addTransaction(double gross, double net, double fee, String parentFund, String fund) {
    if (!ledgers.containsKey(parentFund)) {
      Ledger ledger = new Ledger();
      ledgers.put(parentFund, ledger);
    }
    if (!ledgers.get(parentFund).subLedgers.containsKey(fund)) {
      Ledger ledger = new Ledger();
      ledgers.get(parentFund).subLedgers.put(fund, ledger);
    }

    ledgers.get(parentFund).gross += gross;
    ledgers.get(parentFund).net += net;
    ledgers.get(parentFund).fees += fee;
    ledgers.get(parentFund).subLedgers.get(fund).gross += gross;
    ledgers.get(parentFund).subLedgers.get(fund).net += net;
    ledgers.get(parentFund).subLedgers.get(fund).fees += fee;
  }

  public void addTransaction(double gross, double net, double fee, String fund) {
    if (Strings.isNullOrEmpty(fund)) {
      fund = "General";
    }

    if (!ledgers.containsKey(fund)) {
      Ledger ledger = new Ledger();
      ledgers.put(fund, ledger);
    }

    ledgers.get(fund).gross += gross;
    ledgers.get(fund).net += net;
    ledgers.get(fund).fees += fee;
  }

  public void addRefund(double refund, String parentFund, String fund) {
    if (!ledgers.containsKey(parentFund)) {
      Ledger ledger = new Ledger();
      ledgers.put(parentFund, ledger);
    }
    if (!ledgers.get(parentFund).subLedgers.containsKey(fund)) {
      Ledger ledger = new Ledger();
      ledgers.get(parentFund).subLedgers.put(fund, ledger);
    }

    ledgers.get(parentFund).refunds += refund;
    ledgers.get(parentFund).subLedgers.get(fund).refunds += refund;
  }

  public void addRefund(double refund, String fund) {
    if (Strings.isNullOrEmpty(fund)) {
      fund = "General";
    }

    if (!ledgers.containsKey(fund)) {
      Ledger ledger = new Ledger();
      ledgers.put(fund, ledger);
    }

    ledgers.get(fund).refunds += refund;
  }

  // TODO: Should CrmDonation have some concept of fund within it?
  public void addTransaction(CrmDonation transaction, String fund) {
    if (Strings.isNullOrEmpty(fund)) {
      fund = "General";
    }

    if (!ledgers.containsKey(fund)) {
      Ledger ledger = new Ledger();
      ledgers.put(fund, ledger);
    }

    ledgers.get(fund).transactions.add(transaction);
    if (!Strings.isNullOrEmpty(transaction.refundId)) {
      // TODO: The above methods receive explicit refunds amount (see how TER hits the Stripe API to retrieve them).
      //  We need to bake that same sort of concept into PaymentGatewayEvent. But also note that refunds are on a
      //  different date compared to the original charge!
//    ledgers.get(fund).refunds += refund;
      log.warn("IMPLEMENT ME! Skipping refund {}", transaction.refundId);
    } else {
      ledgers.get(fund).gross += transaction.amount;
      ledgers.get(fund).net += transaction.netAmountInDollars;
      ledgers.get(fund).fees += (transaction.amount - transaction.netAmountInDollars);
    }
  }

  public static class Ledger {
    private double gross = 0.0;
    private double net = 0.0;
    private double fees = 0.0;
    private double refunds = 0.0;
    // TODO: will require a Portal change
    private List<CrmDonation> transactions = new ArrayList<>();

    private final Map<String, Ledger> subLedgers = new HashMap<>();

    public double getGross() {
      return gross;
    }

    public double getNet() {
      return net;
    }

    public double getFees() {
      return fees;
    }

    public double getRefunds() { return refunds; }

    public Map<String, Ledger> getSubLedgers() {
      return subLedgers;
    }

    public List<CrmDonation> getTransactions() {
      return transactions;
    }
  }
}
