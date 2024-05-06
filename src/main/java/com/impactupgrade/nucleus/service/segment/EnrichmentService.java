/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmDonation;

public interface EnrichmentService extends SegmentService {

  boolean eventIsFromPlatform(CrmDonation crmDonation);
  void enrich(CrmDonation crmDonation) throws Exception;
}
