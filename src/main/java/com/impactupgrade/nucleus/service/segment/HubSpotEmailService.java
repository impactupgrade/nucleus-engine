package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmContact;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Calendar;

public class HubSpotEmailService extends SmtpEmailService {

  private static final Logger log = LogManager.getLogger(HubSpotEmailService.class);

  @Override
  public String name() {
    return "hubspot";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().hubspot != null && env.getConfig().hubspot.email != null;
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

  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    log.info("not implemented: syncUnsubscribes");
    // TODO: May not need this (break up the interface?). If HS used for email, orgs will always have that as their
    //  CRM as well. No syncs ever needed?
  }

  @Override
  public void upsertContact(String listId) throws Exception {
    //TODO
  }

  @Override protected String host() { return "smtp.hubapi.com"; }
  @Override protected String port() { return "587"; }
  // TODO: Can transactional email use the apikey instead?
  @Override protected String username() { return env.getConfig().hubspot.email.username; }
  @Override protected String password() { return env.getConfig().hubspot.email.password; }
}
