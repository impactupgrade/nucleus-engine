/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.StripeClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayDeposit;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Payout;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public class StripePaymentGatewayService implements PaymentGatewayService {

  private static final Logger log = LogManager.getLogger(StripePaymentGatewayService.class);

  protected Environment env;
  protected StripeClient stripeClient;

  @Override
  public String name() { return "stripe"; }

  @Override
  public void init(Environment env) {
    this.env = env;
    stripeClient = env.stripeClient();
  }

  @Override
  public List<PaymentGatewayDeposit> getDeposits(Date startDate, Date endDate) throws Exception {
    List<PaymentGatewayDeposit> deposits = new ArrayList<>();
    List<Payout> payouts = stripeClient.getPayouts(startDate, endDate, 100);
    for (Payout payout : payouts) {
      log.info("found payout {}", payout.getId());
      PaymentGatewayDeposit deposit = new PaymentGatewayDeposit();

      payoutToPaymentGatewayEvents(payout).forEach(e -> {
        String fund = e.getMetadataValue(env.getConfig().metadataKeys.fund);
        deposit.addTransaction(e, fund);
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
  public void updateSubscription(ManageDonationEvent manageDonationEvent) throws StripeException, ParseException {
    if (manageDonationEvent.getAmount() != null && manageDonationEvent.getAmount() > 0) {
      stripeClient.updateSubscriptionAmount(manageDonationEvent.getSubscriptionId(), manageDonationEvent.getAmount());
    }

    if (manageDonationEvent.getNextPaymentDate() != null) {
      stripeClient.updateSubscriptionDate(manageDonationEvent.getSubscriptionId(), manageDonationEvent.getNextPaymentDate());
    }

    if (manageDonationEvent.getPauseDonation() == true) {
      stripeClient.pauseSubscription(manageDonationEvent.getSubscriptionId(), manageDonationEvent.getPauseDonationUntilDate());
    }

    if (manageDonationEvent.getResumeDonation() == true) {
      stripeClient.resumeSubscription(manageDonationEvent.getSubscriptionId(), manageDonationEvent.getResumeDonationOnDate());
    }

    if (manageDonationEvent.getStripeToken() != null) {
      stripeClient.updateSubscriptionPaymentMethod(manageDonationEvent.getSubscriptionId(), manageDonationEvent.getStripeToken());
    }
  }

  @Override
  public void closeSubscription(ManageDonationEvent manageDonationEvent) throws StripeException {
    stripeClient.cancelSubscription(manageDonationEvent.getSubscriptionId());
  }

  public PaymentGatewayEvent chargeToPaymentGatewayEvent(Charge charge) throws StripeException {
    Optional<BalanceTransaction> chargeBalanceTransaction;
    if (Strings.isNullOrEmpty(charge.getBalanceTransaction())) {
      chargeBalanceTransaction = Optional.empty();
    } else {
      chargeBalanceTransaction = Optional.of(env.stripeClient().getBalanceTransaction(charge.getBalanceTransaction()));
      log.info("found balance transaction {}", chargeBalanceTransaction.get().getId());
    }

    return chargeToPaymentGatewayEvent(charge, chargeBalanceTransaction);
  }

  public PaymentGatewayEvent chargeToPaymentGatewayEvent(Charge charge, Optional<BalanceTransaction> chargeBalanceTransaction) throws StripeException {
    PaymentGatewayEvent paymentGatewayEvent = new PaymentGatewayEvent(env);

    Optional<Customer> chargeCustomer;
    if (Strings.isNullOrEmpty(charge.getCustomer())) {
      chargeCustomer = Optional.empty();
    } else {
      chargeCustomer = Optional.of(env.stripeClient().getCustomer(charge.getCustomer()));
      log.info("found customer {}", chargeCustomer.get().getId());
    }

    Optional<Invoice> chargeInvoice;
    if (Strings.isNullOrEmpty(charge.getInvoice())) {
      chargeInvoice = Optional.empty();
    } else {
      chargeInvoice = Optional.of(env.stripeClient().getInvoice(charge.getInvoice()));
      log.info("found invoice {}", chargeInvoice.get().getId());
    }

    paymentGatewayEvent.initStripe(charge, chargeCustomer, chargeInvoice, chargeBalanceTransaction);

    return paymentGatewayEvent;
  }

  public PaymentGatewayEvent paymentIntentToPaymentGatewayEvent(PaymentIntent paymentIntent) throws StripeException {
    Optional<BalanceTransaction> chargeBalanceTransaction = Optional.empty();
    if (paymentIntent.getCharges() != null && !paymentIntent.getCharges().getData().isEmpty()) {
      if (paymentIntent.getCharges().getData().size() == 1) {
        String balanceTransactionId = paymentIntent.getCharges().getData().get(0).getBalanceTransaction();
        if (!Strings.isNullOrEmpty(balanceTransactionId)) {
          chargeBalanceTransaction = Optional.of(env.stripeClient().getBalanceTransaction(balanceTransactionId));
          log.info("found balance transaction {}", chargeBalanceTransaction.get().getId());
        }
      }
    }

    return paymentIntentToPaymentGatewayEvent(paymentIntent, chargeBalanceTransaction);
  }

  public PaymentGatewayEvent paymentIntentToPaymentGatewayEvent(PaymentIntent paymentIntent, Optional<BalanceTransaction> chargeBalanceTransaction) throws StripeException {
    PaymentGatewayEvent paymentGatewayEvent = new PaymentGatewayEvent(env);

    // TODO: For TER, the customers and/or metadata aren't always included in the webhook -- not sure why.
    //  For now, retrieve the whole PaymentIntent and try again...
    PaymentIntent fullPaymentIntent = env.stripeClient().getPaymentIntent(paymentIntent.getId());

    Optional<Customer> chargeCustomer;
    if (Strings.isNullOrEmpty(fullPaymentIntent.getCustomer())) {
      chargeCustomer = Optional.empty();
    } else {
      chargeCustomer = Optional.of(env.stripeClient().getCustomer(fullPaymentIntent.getCustomer()));
      log.info("found customer {}", chargeCustomer.get().getId());
    }

    Optional<Invoice> chargeInvoice;
    if (Strings.isNullOrEmpty(fullPaymentIntent.getInvoice())) {
      chargeInvoice = Optional.empty();
    } else {
      chargeInvoice = Optional.of(env.stripeClient().getInvoice(fullPaymentIntent.getInvoice()));
      log.info("found invoice {}", chargeInvoice.get().getId());
    }

    paymentGatewayEvent.initStripe(fullPaymentIntent, chargeCustomer, chargeInvoice, chargeBalanceTransaction);

    return paymentGatewayEvent;
  }

  public List<PaymentGatewayEvent> payoutToPaymentGatewayEvents(Payout payout) throws StripeException {
    List<PaymentGatewayEvent> paymentGatewayEvents = new ArrayList<>();

    List<BalanceTransaction> balanceTransactions = env.stripeClient().getBalanceTransactions(payout);
    for (BalanceTransaction balanceTransaction : balanceTransactions) {
      if (balanceTransaction.getSourceObject() instanceof Charge charge) {
        log.info("found charge {}", charge.getId());

        PaymentGatewayEvent paymentGatewayEvent;
        if (Strings.isNullOrEmpty(charge.getPaymentIntent())) {
          paymentGatewayEvent = chargeToPaymentGatewayEvent(charge, Optional.of(balanceTransaction));
        } else {
          // TODO: There's a chance we might have to retrieve the full intent, here. MetadataRetriever had a note
          //  that made it sound like payment intents in a payout balance transaction aren't 100% filled out.
          log.info("found intent {}", charge.getPaymentIntent());
          paymentGatewayEvent = paymentIntentToPaymentGatewayEvent(charge.getPaymentIntentObject(), Optional.of(balanceTransaction));
        }
        paymentGatewayEvent.setDepositId(payout.getId());
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(payout.getArrivalDate() * 1000);
        paymentGatewayEvent.setDepositDate(c);

        paymentGatewayEvents.add(paymentGatewayEvent);
      }
    }

    return paymentGatewayEvents;
  }
}
