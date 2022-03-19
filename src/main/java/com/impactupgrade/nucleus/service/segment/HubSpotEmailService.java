package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Calendar;
import java.util.List;

public class HubSpotEmailService extends SmtpEmailService {

  private static final Logger log = LogManager.getLogger(HubSpotEmailService.class);

  protected Environment env;

  @Override
  public String name() {
    return "hubspot";
  }

  @Override
  protected List<EnvironmentConfig.EmailPlatform> emailPlatforms() {
    return List.of(env.getConfig().hubspot.email);
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

  protected String host(EnvironmentConfig.EmailPlatform emailPlatform) { return "smtp.hubapi.com"; }
  protected String port(EnvironmentConfig.EmailPlatform emailPlatform) { return "587"; }
  // TODO: Can transactional email use the apikey instead?
  protected String username(EnvironmentConfig.EmailPlatform emailPlatform) { return emailPlatform.username; }
  protected String password(EnvironmentConfig.EmailPlatform emailPlatform) { return emailPlatform.password; }
}
