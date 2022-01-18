package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Calendar;

public class SendGridEmailService extends SmtpEmailService {

  private static final Logger log = LogManager.getLogger(SendGridEmailService.class);

  protected Environment env;

  @Override
  public String name() {
    return "sendgrid";
  }

  @Override
  public void init(Environment env) {
    this.env = env;
  }

  @Override
  public void sendEmailTemplate(String template, String to) {
    log.info("not implemented: sendEmailTemplate");
    // TODO
  }

  @Override
  public void syncContacts(Calendar lastSync) throws Exception {
    log.info("not implemented: syncContacts");
    // TODO
  }

  protected String host() { return "smtp.sendgrid.net"; }
  protected String port() { return "587"; }
  protected String username() { return "apikey"; }
  protected String password() {
    if (Strings.isNullOrEmpty(env.getConfig().sendgridEmail.password)) {
      // legacy support
      return System.getenv("SENDGRID_KEY");
    }
    return env.getConfig().sendgridEmail.password;
  }
}
