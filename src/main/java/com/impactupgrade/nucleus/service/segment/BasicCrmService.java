package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.model.CrmUser;
import com.impactupgrade.nucleus.model.OpportunityEvent;

import java.util.List;
import java.util.Optional;

public interface BasicCrmService extends CrmService {

  default void addContactToCampaign(CrmContact crmContact, String campaignId) throws Exception {
  }

  default List<CrmContact> getContactsFromList(String listId) throws Exception {
    return null;
  }

  default void addContactToList(CrmContact crmContact, String listId) throws Exception {
  }

  default void removeContactFromList(CrmContact crmContact, String listId) throws Exception {
  }

  default String insertOpportunity(OpportunityEvent opportunityEvent) throws Exception {
    return null;
  }

  default Optional<CrmUser> getUserById(String id) throws Exception {
    return Optional.empty();
  }

  default String insertTask(CrmTask crmTask) throws Exception {
    return null;
  }
}
