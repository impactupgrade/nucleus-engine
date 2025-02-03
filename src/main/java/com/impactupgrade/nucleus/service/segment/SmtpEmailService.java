/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.sun.mail.smtp.SMTPTransport;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Date;
import java.util.Properties;

public abstract class SmtpEmailService implements EmailService {

  protected final Properties props = new Properties();

  protected Environment env;

  @Override
  public void init(Environment env) {
    this.env = env;
    props.put("mail.smtp.host", host());
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.port", port());
    props.put("mail.smtp.timeout", "60000");
  }

  @Override
  public void sendEmailText(String subject, String body, boolean isHtml, String to, String cc, String bcc, String from) {
    env.logJobInfo("sendEmailText: to={} cc={} bcc={} from={} subject={} isHtml={}", to, cc, bcc, from, subject, isHtml);
    try {
      Session session = Session.getInstance(props, null);

      Message msg = new MimeMessage(session);
      msg.setFrom(new InternetAddress(from));
      msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
      if (!Strings.isNullOrEmpty(cc)) {
        msg.addRecipients(Message.RecipientType.CC, InternetAddress.parse(cc, false));
      }
      if (!Strings.isNullOrEmpty(bcc)) {
        msg.addRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc, false));
      }
      msg.setSubject(subject);
      if (isHtml) {
        msg.setContent(body, "text/html");
      } else {
        msg.setText(body);
      }
      msg.setSentDate(new Date());

      SMTPTransport t = (SMTPTransport) session.getTransport("smtp");
      t.connect(host(), username(), password());
      t.sendMessage(msg, msg.getAllRecipients());
      t.close();
    } catch (MessagingException e) {
      env.logJobWarn("email failed", e);
    }
  }

  protected abstract String host();
  protected abstract String port();
  protected abstract String username();
  protected abstract String password();
}
