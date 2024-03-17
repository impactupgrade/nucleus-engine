/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.UpdateRecurringDonationEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayDeposit;
import com.impactupgrade.nucleus.model.PaymentGatewayEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayTransaction;

import java.util.Date;
import java.util.List;

public interface PaymentGatewayService extends SegmentService {

  List<PaymentGatewayTransaction> getTransactions(Date startDate, Date endDate) throws Exception;
  List<PaymentGatewayDeposit> getDeposits(Date startDate, Date endDate) throws Exception;

  List<PaymentGatewayEvent> verifyCharges(Date startDate, Date endDate);
//  void verifyCharge(String id) throws Exception;
  void verifyAndReplayCharge(String id) throws Exception;
  void verifyAndReplayCharges(Date startDate, Date endDate);
  void verifyAndReplayDeposits(Date startDate, Date endDate);

  void updateSubscription(UpdateRecurringDonationEvent updateRecurringDonationEvent) throws Exception;
  void closeSubscription(String subscriptionId) throws Exception;
}
