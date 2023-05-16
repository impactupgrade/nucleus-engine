package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;

public class MessageBirdTextingService implements TextingService {

  @Override
  public void sendMessage(String message, CrmContact crmContact, String to, String from) {

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

  @Override
  public String name() {
    return null;
  }

  @Override
  public boolean isConfigured(Environment env) {
    return false;
  }

  @Override
  public void init(Environment env) {

  }
}
