package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.OpportunityEvent;

public interface CrmOpportunityService extends CrmService {

  default String insertContact(OpportunityEvent opportunityEvent) throws Exception {
    return insertContact(opportunityEvent.getCrmContact());
  }
  default void updateContact(OpportunityEvent opportunityEvent) throws Exception {
    updateContact(opportunityEvent.getCrmContact());
  }
  String insertOpportunity(OpportunityEvent opportunityEvent) throws Exception;
}
