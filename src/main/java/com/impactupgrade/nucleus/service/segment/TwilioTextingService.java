package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.client.TwilioClient;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmContact;
import com.twilio.rest.api.v2010.account.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TwilioTextingService implements TextingService {

  protected TwilioClient twilioClient;
  protected Environment env;
  private static final Logger log = LogManager.getLogger(TwilioTextingService.class);

  @Override
  public String name() {
    return "twilio";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return !Strings.isNullOrEmpty(env.getConfig().twilio.secretKey); //TODO is this the best field to check?
  }

  @Override
  public void init(Environment env) {
    this.env = env;
    this.twilioClient = env.twilioClient();
  }
  @Override
  public void sendMessage(String message, CrmContact crmContact, String to, String from) {
    Message twilioMessage = twilioClient.sendMessage(to, from, message, null);
    log.info("sent messageSid {} to {}; status={} errorCode={} errorMessage={}",
            twilioMessage.getSid(), to, twilioMessage.getStatus(), twilioMessage.getErrorCode(), twilioMessage.getErrorMessage());

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
