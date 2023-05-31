package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.client.MessageBirdSMSClient;
import com.impactupgrade.nucleus.client.TwilioClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;

public class MessageBirdTextingService implements TextingService {
  protected MessageBirdSMSClient messageBirdSMSClient;
  protected Environment env;
  @Override
  public void init(Environment env) {
    this.env = env;
    this.messageBirdSMSClient = env.messageBirdSMSClient();
  }
  @Override
  public String name() {
    return "messagebird";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return false;
  }
  @Override
  public void sendMessage(String message, CrmContact crmContact, String to, String from) {

  }

}
