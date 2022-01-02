package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Calendar;

public class HubSpotEmailService implements EmailService {

  private static final Logger log = LogManager.getLogger(HubSpotEmailService.class);

  protected Environment env;

  @Override
  public String name() {
    return "hubspot";
  }

  @Override
  public void init(Environment env) {
    this.env = env;
  }

  @Override
  public void sendEmailText(String subject, String body, boolean isHtml, String to, String from) {
    // TODO
  }

  @Override
  public void sendEmailTemplate(String template, String to) {
    // TODO
  }

  @Override
  public void syncContacts(Calendar lastSync) throws Exception {
    log.info("not implemented: syncContacts");
    // TODO: May not need this (break up the interface?). If HS used for email, orgs will always have that as their
    //  CRM as well. No syncs ever needed?
  }
}
