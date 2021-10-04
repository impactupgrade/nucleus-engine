/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.ManageDonationEvent;
import com.impactupgrade.nucleus.model.PaymentGatewayDeposit;
import com.impactupgrade.nucleus.model.PaymentGatewayTransaction;

import java.util.Date;
import java.util.List;

public interface PaymentGatewayService extends SegmentService {

  List<PaymentGatewayTransaction> getTransactions(Date startDate, Date endDate) throws Exception;
  List<PaymentGatewayDeposit> getDeposits(Date startDate, Date endDate) throws Exception;

  void updateSubscription(ManageDonationEvent manageDonationEvent) throws Exception;
  void closeSubscription(ManageDonationEvent manageDonationEvent) throws Exception;
}
