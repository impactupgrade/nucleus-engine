package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmDonation;

public interface EnrichmentService extends SegmentService {

  boolean eventIsFromPlatform(CrmDonation crmDonation);
  void enrich(CrmDonation crmDonation) throws Exception;
}
