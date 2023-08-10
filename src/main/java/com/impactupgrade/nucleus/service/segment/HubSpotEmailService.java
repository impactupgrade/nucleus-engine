package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;

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

  @Override protected String host() { return "smtp.hubapi.com"; }
  @Override protected String port() { return "587"; }
  // TODO: Can transactional email use the apikey instead?
  @Override protected String username() { return env.getConfig().hubspot.email.username; }
  @Override protected String password() { return env.getConfig().hubspot.email.password; }
}
