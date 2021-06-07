package com.impactupgrade.common.util;

import com.google.common.base.Strings;
import com.sun.mail.smtp.SMTPTransport;
import org.springframework.stereotype.Service;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Date;
import java.util.Properties;

@Service
public class EmailUtil {

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

  public static void sendEmail(String subject, String textBody, String htmlBody, String to, String from)
      throws MessagingException {
    Session session = Session.getInstance(props, null);

    Message msg = new MimeMessage(session);
    msg.setFrom(new InternetAddress(from));
    msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
    msg.setSubject(subject);
    if (!Strings.isNullOrEmpty(textBody)) {
      msg.setText(textBody);
    }
    if (!Strings.isNullOrEmpty(htmlBody)) {
      msg.setContent(htmlBody, "text/html");
    }
    msg.setSentDate(new Date());

    SMTPTransport t = (SMTPTransport) session.getTransport("smtp");
    t.connect(SMTP_SERVER, SMTP_USERNAME, SMTP_PASSWORD);
    t.sendMessage(msg, msg.getAllRecipients());
    t.close();
  }


}
