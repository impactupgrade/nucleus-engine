package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

public class HubSpotEmailService extends SmtpEmailService {

  @Override
  public String name() {
    return "hubspot";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().hubspot != null && env.getConfig().hubspot.email != null;
  }

  @Override
  public void sendEmailTemplate(String subject, String template, Map<String, Object> data, List<String> tos, String from) {
    // TODO
  }

  @Override
  public void syncContacts(Calendar lastSync) throws Exception {
    env.logJobInfo("not implemented: syncContacts");
    // TODO: May not need this (break up the interface?). If HS used for email, orgs will always have that as their
    //  CRM as well. No syncs ever needed?
  }

  @Override
  public void syncUnsubscribes(Calendar lastSync) throws Exception {
    env.logJobInfo("not implemented: syncUnsubscribes");
    // TODO: May not need this (break up the interface?). If HS used for email, orgs will always have that as their
    //  CRM as well. No syncs ever needed?
  }

  @Override
  public void upsertContact(String email, @Deprecated String contactId) throws Exception {
    //TODO
  }

  @Override protected String host() { return "smtp.hubapi.com"; }
  @Override protected String port() { return "587"; }
  // TODO: Can transactional email use the apikey instead?
  @Override protected String username() { return env.getConfig().hubspot.email.username; }
  @Override protected String password() { return env.getConfig().hubspot.email.password; }
}
