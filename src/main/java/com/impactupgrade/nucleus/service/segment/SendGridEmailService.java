package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.sun.mail.smtp.SMTPTransport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Calendar;
import java.util.Date;
import java.util.Properties;

public class SendGridEmailService implements EmailService {

  private static final Logger log = LogManager.getLogger(SendGridEmailService.class);

  // TODO: move to env.json? Or use that as an override and continue to maintain a default instance for clients?
  private static final String SMTP_SERVER = "smtp.sendgrid.net";
  private static final String SMTP_USERNAME = "apikey";
  private static final String SMTP_PASSWORD = System.getenv("SENDGRID_KEY");
  private static final Properties props = new Properties();
  static {
    props.put("mail.smtp.host", "smtp.sendgrid.net");
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.port", "587");
    props.put("mail.smtp.timeout", "60000");
  }

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
  public void sendEmailText(String subject, String body, boolean isHtml, String to, String from) {
    log.info("sendEmailText: to={} from={} subject={} isHtml={}", to, from, subject, isHtml);
    try {
      Session session = Session.getInstance(props, null);

      Message msg = new MimeMessage(session);
      msg.setFrom(new InternetAddress(from));
      msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
      msg.setSubject(subject);
      if (isHtml) {
        msg.setContent(body, "text/html");
      } else {
        msg.setText(body);
      }
      msg.setSentDate(new Date());

      SMTPTransport t = (SMTPTransport) session.getTransport("smtp");
      t.connect(SMTP_SERVER, SMTP_USERNAME, SMTP_PASSWORD);
      t.sendMessage(msg, msg.getAllRecipients());
      t.close();
    } catch (MessagingException e) {
      log.warn("email failed", e);
    }
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
}
