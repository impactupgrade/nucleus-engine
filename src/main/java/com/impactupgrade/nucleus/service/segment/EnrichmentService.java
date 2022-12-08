package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.PaymentGatewayEvent;

public interface EnrichmentService extends SegmentService {

  boolean eventIsFromPlatform(PaymentGatewayEvent event);

  void enrich(PaymentGatewayEvent event) throws Exception;
}
