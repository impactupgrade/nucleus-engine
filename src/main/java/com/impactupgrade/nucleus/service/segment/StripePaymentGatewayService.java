/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.filter.StripeObjectFilter;
import com.impactupgrade.nucleus.model.CrmDonation;
import com.impactupgrade.nucleus.model.CrmRecurringDonation;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayDeposit;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayEventType;
import com.impactupgrade.nucleus.model.PaymentGatewayTransaction;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Payout;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Optional;

public class StripePaymentGatewayService implements PaymentGatewayService {

  protected Environment env;
  protected StripeClient stripeClient;
  protected StripeObjectFilter stripeObjectFilter;

  @Override
  public String name() {
    return "stripe";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return !Strings.isNullOrEmpty(env.getConfig().stripe.secretKey);
  }

  @Override
  public void init(Environment env) {
    this.env = env;
    stripeClient = env.stripeClient();
    this.stripeObjectFilter = new StripeObjectFilter(env.getConfig().stripe.filteringExpressions);
  }

  @Override
  public List<PaymentGatewayTransaction> getTransactions(Date startDate, Date endDate) throws Exception {
    List<PaymentGatewayTransaction> transactions = new ArrayList<>();
    List<Charge> charges = new ArrayList<>();
    // convert newest first oldest first -- SUPER important for accounting reconciliation, where sequential processing is needed
    stripeClient.getAllCharges(startDate, endDate).forEach(c -> charges.add(0, c));
    for (Charge charge : charges) {
      if (Strings.isNullOrEmpty(charge.getBalanceTransaction())) {
        // hasn't been deposited yet, so skip it
        continue;
      }

      // avoid chargeToPaymentGatewayEvent since we don't need full details and it will attempt to fill everything
      // out with extra Stripe API hits...
      PaymentGatewayEvent e = new PaymentGatewayEvent(env);
      e.initStripe(charge, Optional.ofNullable(charge.getCustomerObject()), Optional.empty(),
          Optional.of(charge.getBalanceTransactionObject()));

      PaymentGatewayTransaction transaction = new PaymentGatewayTransaction(
          GregorianCalendar.from(e.getCrmDonation().closeDate),
          e.getCrmDonation().amount,
          e.getCrmDonation().netAmountInDollars,
          e.getCrmDonation().feeInDollars,
          e.getCrmContact().firstName + " " + e.getCrmContact().lastName,
          e.getCrmContact().email,
          e.getCrmContact().mobilePhone,
          e.getCrmContact().mailingAddress.toString(),
          "Stripe",
          charge.getId(),
          "https://dashboard.stripe.com/charges/" + charge.getId(),
          e.getAllMetadata()
      );
      transactions.add(transaction);
    }
    return transactions;
  }

  @Override
  public List<PaymentGatewayDeposit> getDeposits(Date startDate, Date endDate) throws Exception {
    List<PaymentGatewayDeposit> deposits = new ArrayList<>();
    List<Payout> payouts = stripeClient.getPayouts(startDate, endDate, 100);
    // convert newest first oldest first -- SUPER important for accounting reconciliation, where sequential processing is needed
    Collections.reverse(payouts);
    for (Payout payout : payouts) {
      env.logJobInfo("found payout {}", payout.getId());
      PaymentGatewayDeposit deposit = new PaymentGatewayDeposit();

      payoutToPaymentGatewayEvents(payout).forEach(e -> {
        String fund = e.getCrmDonation().getMetadataValue(env.getConfig().metadataKeys.fund);
        deposit.addTransaction(e.getCrmDonation(), fund);
      });

      Calendar c = Calendar.getInstance();
      c.setTimeInMillis(payout.getArrivalDate() * 1000);

      deposit.setUrl("https://dashboard.stripe.com/payouts/" + payout.getId());
      deposit.setDate(c);

      deposits.add(deposit);
    }
    return deposits;
  }

  @Override
  public List<PaymentGatewayEvent> verifyCharges(Date startDate, Date endDate) {
    List<PaymentGatewayEvent> missingDonations = new ArrayList<>();

    try {
      SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
      List<Charge> charges = new ArrayList<>();
      // convert newest first oldest first -- SUPER important for accounting reconciliation, where sequential processing is needed
      stripeClient.getAllCharges(startDate, endDate).forEach(c -> charges.add(0, c));
      int count = 0;
      for (Charge charge : charges) {
        if (!charge.getStatus().equalsIgnoreCase("succeeded")
            || charge.getPaymentIntentObject() != null && !charge.getPaymentIntentObject().getStatus().equalsIgnoreCase("succeeded")) {
          continue;
        }

        count++;

        try {
          String paymentIntentId = charge.getPaymentIntent();
          String chargeId = charge.getId();
          String transactionId = chargeId;
          Optional<CrmDonation> donation = Optional.empty();
          if (!Strings.isNullOrEmpty(paymentIntentId)) {
            donation = env.donationsCrmService().getDonationByTransactionId(paymentIntentId);
            transactionId = paymentIntentId;
          }
          if (donation.isEmpty()) {
            donation = env.donationsCrmService().getDonationByTransactionId(chargeId);
          }

          if (donation.isEmpty()) {
            env.logJobInfo("verify-charges," + count + ",MISSING," + transactionId + "," + SDF.format(charge.getCreated() * 1000));
          } else if (donation.get().status != CrmDonation.Status.SUCCESSFUL) {
            env.logJobInfo("verify-charges," + count + ",WRONG-STATE," + transactionId + "," + SDF.format(charge.getCreated() * 1000) + "," + donation.get().status);
          } else {
            continue;
          }

          // TODO: For now, avoiding this since it hits the Stripe API to fill in info.
//          PaymentGatewayEvent paymentGatewayEvent;
//          if (Strings.isNullOrEmpty(paymentIntentId)) {
//            paymentGatewayEvent = chargeToPaymentGatewayEvent(charge);
//          } else {
//            paymentGatewayEvent = paymentIntentToPaymentGatewayEvent(charge.getPaymentIntentObject());
//          }
//          missingDonations.add(paymentGatewayEvent);
        } catch (Exception e) {
          env.logJobError("charge verify failed", e);
        }
      }
    } catch (Exception e) {
      env.logJobError("charge verifies failed", e);
    }

    return missingDonations;
  }

  @Override
  public void verifyCharge(String id) throws Exception {

    Charge charge = stripeClient.getCharge(id);
    SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");

    if (!charge.getStatus().equalsIgnoreCase("succeeded")
        || charge.getPaymentIntentObject() != null && !charge.getPaymentIntentObject().getStatus().equalsIgnoreCase("succeeded")) {
      env.logJobInfo("Charge {} succeeded", id);
    }


    try {
      String paymentIntentId = charge.getPaymentIntent();
      String chargeId = charge.getId();
      String transactionId = chargeId;
      Optional<CrmDonation> donation = Optional.empty();
      if (!Strings.isNullOrEmpty(paymentIntentId)) {
        donation = env.donationsCrmService().getDonationByTransactionId(paymentIntentId);
        transactionId = paymentIntentId;
      }
      if (donation.isEmpty()) {
        donation = env.donationsCrmService().getDonationByTransactionId(chargeId);
      }

      if (donation.isEmpty()) {
        env.logJobInfo("verify-charge: MISSING," + transactionId + "," + SDF.format(charge.getCreated() * 1000));
      } else if (donation.get().status != CrmDonation.Status.SUCCESSFUL) {
        env.logJobInfo("verify-charge: WRONG-STATE," + transactionId + "," + SDF.format(charge.getCreated() * 1000) + "," + donation.get().status);
      }

    } catch (Exception e) {
      env.logJobError("charge verify failed", e);
    }
  }


  @Override
  public void verifyAndReplayCharge(String id) throws Exception {
    Charge charge = stripeClient.getCharge(id);

    if (charge == null) {
      env.logJobInfo("Failed to verify and replay charge, no charge with the ID: {} found", id);
      return;
    }
    if (!charge.getStatus().equalsIgnoreCase("succeeded")
        || charge.getPaymentIntentObject() != null && !charge.getPaymentIntentObject().getStatus().equalsIgnoreCase("succeeded")) {
      env.logJobInfo("Charge {} succeeded", id);
      return;
    }

    try {
      String paymentIntentId = charge.getPaymentIntent();

      PaymentGatewayEvent paymentGatewayEvent;
      if (Strings.isNullOrEmpty(paymentIntentId)) {
        paymentGatewayEvent = chargeToPaymentGatewayEvent(charge, true);
      } else {
        paymentGatewayEvent = paymentIntentToPaymentGatewayEvent(charge.getPaymentIntentObject(), true);
      }
      env.contactService().processDonor(paymentGatewayEvent);
      env.donationService().createDonation(paymentGatewayEvent);
      env.accountingService().processTransaction(paymentGatewayEvent);

    } catch (Exception e) {
      env.logJobError("charge replay failed", e);
    }
  }


  // TODO: Once the above is returning results, use this. But for now, keeping the original one...
//  @Override
//  public void verifyAndReplayCharges(Date startDate, Date endDate) {
//    try {
//      SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
//
//      List<PaymentGatewayEvent> missingDonations = verifyCharges(startDate, endDate);
//
//      int count = 0;
//      for (PaymentGatewayEvent missingDonation : missingDonations) {
//        count++;
//
//        try {
//          env.logJobInfo("(" + count + ") REPLAYING: " + missingDonation.getTransactionId());
//          env.contactService().processDonor(missingDonation);
//          env.donationService().createDonation(missingDonation);
//        } catch (Exception e) {
//          env.logJobError("charge replay failed", e);
//        }
//      }
//    } catch (Exception e) {
//      env.logJobError("charge replays failed", e);
//    }
//  }

  @Override
  public void verifyAndReplayCharges(Date startDate, Date endDate) {
    try {
      SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
      List<Charge> charges = new ArrayList<>();
      // convert newest first oldest first -- SUPER important for accounting reconciliation, where sequential processing is needed
      stripeClient.getAllCharges(startDate, endDate).forEach(c -> charges.add(0, c));
      int count = 0;
      int total = charges.size();
      for (Charge charge : charges) {
        count++;
        env.logJobInfo("{} of {}", count, total);

        if (!charge.getStatus().equalsIgnoreCase("succeeded")
            || charge.getPaymentIntentObject() != null && !charge.getPaymentIntentObject().getStatus().equalsIgnoreCase("succeeded")) {
          continue;
        }

        try {
          String paymentIntentId = charge.getPaymentIntent();
          String chargeId = charge.getId();
//          Optional<CrmDonation> donation = Optional.empty();
//          if (!Strings.isNullOrEmpty(paymentIntentId)) {
//            donation = env.donationsCrmService().getDonationByTransactionId(paymentIntentId);
//          }
//          if (donation.isEmpty()) {
//            donation = env.donationsCrmService().getDonationByTransactionId(chargeId);
//          }
//
//          if (donation.isEmpty()) {
//            env.logJobInfo("(" + count + ") MISSING: " + chargeId + "/" + paymentIntentId + " " + SDF.format(charge.getCreated() * 1000));

          PaymentGatewayEvent paymentGatewayEvent;
          if (Strings.isNullOrEmpty(paymentIntentId)) {
            paymentGatewayEvent = chargeToPaymentGatewayEvent(charge, true);
          } else {
            paymentGatewayEvent = paymentIntentToPaymentGatewayEvent(charge.getPaymentIntentObject(), true);
          }
          env.contactService().processDonor(paymentGatewayEvent);
          env.donationService().createDonation(paymentGatewayEvent);
          env.accountingService().processTransaction(paymentGatewayEvent);
//          }
        } catch (Exception e) {
          env.logJobError("charge replay failed", e);
        }
      }
    } catch (Exception e) {
      env.logJobError("charge replays failed", e);
    }
  }

  @Override
  public void verifyAndReplayDeposits(Date startDate, Date endDate) {
    try {
      SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
      List<Payout> payouts = stripeClient.getPayouts(startDate, endDate, 100);
      // convert newest first oldest first -- SUPER important for accounting reconciliation, where sequential processing is needed
      Collections.reverse(payouts);
      for (Payout payout : payouts) {
        try {
          if ("paid".equalsIgnoreCase(payout.getStatus())) {
            env.logJobInfo(SDF.format(new Date(payout.getArrivalDate() * 1000)));
            List<PaymentGatewayEvent> paymentGatewayEvents = payoutToPaymentGatewayEvents(payout);
            env.donationService().processDeposit(paymentGatewayEvents);
          }
        } catch (Exception e) {
          env.logJobError("deposit replay failed", e);
        }
      }
    } catch (Exception e) {
      env.logJobError("deposit replays failed", e);
    }
  }

  @Override
  public void updateSubscription(ManageDonationEvent manageDonationEvent) throws StripeException, ParseException {
    CrmRecurringDonation crmRecurringDonation = manageDonationEvent.getCrmRecurringDonation();

    if (crmRecurringDonation.amount != null && crmRecurringDonation.amount > 0) {
      stripeClient.updateSubscriptionAmount(crmRecurringDonation.subscriptionId, crmRecurringDonation.amount);
    }

    if (manageDonationEvent.getNextPaymentDate() != null) {
      stripeClient.updateSubscriptionDate(crmRecurringDonation.subscriptionId, manageDonationEvent.getNextPaymentDate());
    }

    if (manageDonationEvent.getPauseDonation() == true) {
      stripeClient.pauseSubscription(crmRecurringDonation.subscriptionId, manageDonationEvent.getPauseDonationUntilDate());
    }

    if (manageDonationEvent.getResumeDonation() == true) {
      stripeClient.resumeSubscription(crmRecurringDonation.subscriptionId, manageDonationEvent.getResumeDonationOnDate());
    }

    if (manageDonationEvent.getStripeToken() != null) {
      stripeClient.updateSubscriptionPaymentMethod(crmRecurringDonation.subscriptionId, manageDonationEvent.getStripeToken());
    }
  }

  @Override
  public void closeSubscription(String subscriptionId) throws StripeException {
    stripeClient.cancelSubscription(subscriptionId);
  }

  public PaymentGatewayEvent chargeToPaymentGatewayEvent(Charge charge, boolean fullObjects) throws StripeException {
    Optional<BalanceTransaction> chargeBalanceTransaction;
    if (fullObjects && !Strings.isNullOrEmpty(charge.getBalanceTransaction())) {
      chargeBalanceTransaction = Optional.of(stripeClient.getBalanceTransaction(charge.getBalanceTransaction()));
      env.logJobInfo("found balance transaction {}", chargeBalanceTransaction.get().getId());
    } else {
      chargeBalanceTransaction = Optional.empty();
    }

    return chargeToPaymentGatewayEvent(charge, chargeBalanceTransaction, fullObjects);
  }

  public PaymentGatewayEvent chargeToPaymentGatewayEvent(Charge charge, Optional<BalanceTransaction> chargeBalanceTransaction, boolean fullObjects) throws StripeException {
    PaymentGatewayEvent paymentGatewayEvent = new PaymentGatewayEvent(env);

    Optional<Customer> chargeCustomer;
    if (fullObjects && !Strings.isNullOrEmpty(charge.getCustomer())) {
      chargeCustomer = Optional.of(stripeClient.getCustomer(charge.getCustomer()));
      env.logJobInfo("found customer {}", chargeCustomer.get().getId());
    } else {
      chargeCustomer = Optional.empty();
    }

    Optional<Invoice> chargeInvoice;
    if (fullObjects && !Strings.isNullOrEmpty(charge.getInvoice())) {
      chargeInvoice = Optional.of(stripeClient.getInvoice(charge.getInvoice()));
      env.logJobInfo("found invoice {}", chargeInvoice.get().getId());
    } else {
      chargeInvoice = Optional.empty();
    }

    paymentGatewayEvent.initStripe(charge, chargeCustomer, chargeInvoice, chargeBalanceTransaction);

    return paymentGatewayEvent;
  }

  public PaymentGatewayEvent paymentIntentToPaymentGatewayEvent(PaymentIntent paymentIntent, boolean fullObjects) throws StripeException {
    Optional<BalanceTransaction> chargeBalanceTransaction = Optional.empty();
    if (paymentIntent.getCharges() != null && !paymentIntent.getCharges().getData().isEmpty()) {
      if (paymentIntent.getCharges().getData().size() == 1) {
        String balanceTransactionId = paymentIntent.getCharges().getData().get(0).getBalanceTransaction();
        if (fullObjects && !Strings.isNullOrEmpty(balanceTransactionId)) {
          chargeBalanceTransaction = Optional.of(stripeClient.getBalanceTransaction(balanceTransactionId));
          env.logJobInfo("found balance transaction {}", chargeBalanceTransaction.get().getId());
        }
      }
    }

    return paymentIntentToPaymentGatewayEvent(paymentIntent, chargeBalanceTransaction, fullObjects);
  }

  public PaymentGatewayEvent paymentIntentToPaymentGatewayEvent(PaymentIntent paymentIntent, Optional<BalanceTransaction> chargeBalanceTransaction, boolean fullObjects) throws StripeException {
    PaymentGatewayEvent paymentGatewayEvent = new PaymentGatewayEvent(env);

    // TODO: For TER, the customers and/or metadata aren't always included in the webhook -- not sure why.
    //  For now, retrieve the whole PaymentIntent and try again...
    paymentIntent = stripeClient.getPaymentIntent(paymentIntent.getId());

    Optional<Customer> chargeCustomer;
    if (fullObjects && !Strings.isNullOrEmpty(paymentIntent.getCustomer())) {
      chargeCustomer = Optional.of(stripeClient.getCustomer(paymentIntent.getCustomer()));
      env.logJobInfo("found customer {}", chargeCustomer.get().getId());
    } else {
      chargeCustomer = Optional.empty();
    }

    Optional<Invoice> chargeInvoice;
    if (fullObjects && !Strings.isNullOrEmpty(paymentIntent.getInvoice())) {
      chargeInvoice = Optional.of(stripeClient.getInvoice(paymentIntent.getInvoice()));
      env.logJobInfo("found invoice {}", chargeInvoice.get().getId());
    } else {
      chargeInvoice = Optional.empty();
    }

    paymentGatewayEvent.initStripe(paymentIntent, chargeCustomer, chargeInvoice, chargeBalanceTransaction);

    return paymentGatewayEvent;
  }

  public List<PaymentGatewayEvent> payoutToPaymentGatewayEvents(Payout payout) throws StripeException {
    List<PaymentGatewayEvent> paymentGatewayEvents = new ArrayList<>();

    List<BalanceTransaction> balanceTransactions = stripeClient.getBalanceTransactions(payout);
    for (BalanceTransaction balanceTransaction : balanceTransactions) {
      if ("adjustment".equalsIgnoreCase(balanceTransaction.getType())) {
        // TODO
        continue;
      }

      if (balanceTransaction.getSourceObject() instanceof Charge charge) {
        env.logJobInfo("found charge {}", charge.getId());

        PaymentGatewayEvent paymentGatewayEvent;
        if (Strings.isNullOrEmpty(charge.getPaymentIntent())) {
          paymentGatewayEvent = chargeToPaymentGatewayEvent(charge, Optional.of(balanceTransaction), false);
        } else {
          env.logJobInfo("found intent {}", charge.getPaymentIntent());
          paymentGatewayEvent = paymentIntentToPaymentGatewayEvent(charge.getPaymentIntentObject(), Optional.of(balanceTransaction), false);
        }

        paymentGatewayEvent.getCrmDonation().depositId = payout.getId();
        paymentGatewayEvent.getCrmDonation().depositDate = ZonedDateTime.ofInstant(Instant.ofEpochSecond(payout.getArrivalDate()), ZoneId.of("UTC"));
        paymentGatewayEvent.setPaymentGatewayEventType(PaymentGatewayEventType.PAYMENT_SUCCESS);
        paymentGatewayEvents.add(paymentGatewayEvent);
      } else if (balanceTransaction.getSourceObject() instanceof Refund refund) {
        env.logJobInfo("found refund {}", refund.getId());

        PaymentGatewayEvent paymentGatewayEvent = new PaymentGatewayEvent(env);
        paymentGatewayEvent.initStripe(refund);
        paymentGatewayEvent.getCrmDonation().depositId = payout.getId();
        paymentGatewayEvent.getCrmDonation().depositDate = ZonedDateTime.ofInstant(Instant.ofEpochSecond(payout.getArrivalDate()), ZoneId.of("UTC"));

        paymentGatewayEvents.add(paymentGatewayEvent);
      }
    }

    return paymentGatewayEvents;
  }

  // TODO: interface method?
  public boolean filter(StripeObject stripeObject) {
    return stripeObjectFilter.filter(stripeObject);
  }
}
