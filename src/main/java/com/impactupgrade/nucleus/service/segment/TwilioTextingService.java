package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.model.CrmContact;

public class TwilioTextingService implements TextingService {
  @Override
  public void sendMessage(String message, CrmContact crmContact, String sender) {

  }

  @Override
  public CrmContact processSignup(String phone, String firstName, String lastName, String email, String __emailOptIn, String __smsOptIn, String language, String campaignId, String listId) throws Exception {
    return null;
  }

  @Override
  public void optIn(String phone) throws Exception {

  }

  @Override
  public void optOut(String phone) throws Exception {

  }

  @Override
  public void optOut(CrmContact crmContact) throws Exception {

  }
}
