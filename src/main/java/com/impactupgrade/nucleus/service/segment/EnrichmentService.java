package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.PaymentGatewayEvent;

import java.util.List;

public interface EnrichmentService extends SegmentService {

  public boolean eventIsFromPlatform(PaymentGatewayEvent event);

  public List<PaymentGatewayEvent> enrich(PaymentGatewayEvent event) throws Exception;


}
