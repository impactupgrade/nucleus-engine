package com.impactupgrade.nucleus.service.segment;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.sun.mail.smtp.SMTPTransport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Date;
import java.util.List;
import java.util.Properties;

public abstract class SmtpEmailService extends AbstractEmailService {

  private static final Logger log = LogManager.getLogger(SmtpEmailService.class);

  protected EnvironmentConfig.EmailPlatform emailPlatform;
  protected Properties props = new Properties();

  @Override
  public void init(Environment env) {
    super.init(env);

    // Find the transactionalSender. If it's not explicitly set, assume the first (or only).
    emailPlatform = emailPlatforms().stream().filter(ep -> ep.transactionalSender).findFirst()
            .orElse(emailPlatforms().get(0));

    props.put("mail.smtp.host", host(emailPlatform));
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.port", port(emailPlatform));
    props.put("mail.smtp.timeout", "60000");
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
      t.connect(host(emailPlatform), username(emailPlatform), password(emailPlatform));
      t.sendMessage(msg, msg.getAllRecipients());
      t.close();
    } catch (MessagingException e) {
      log.warn("email failed", e);
    }
  }

  protected abstract List<EnvironmentConfig.EmailPlatform> emailPlatforms();

  protected abstract String host(EnvironmentConfig.EmailPlatform emailPlatform);
  protected abstract String port(EnvironmentConfig.EmailPlatform emailPlatform);
  protected abstract String username(EnvironmentConfig.EmailPlatform emailPlatform);
  protected abstract String password(EnvironmentConfig.EmailPlatform emailPlatform);
}
