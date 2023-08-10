package com.impactupgrade.nucleus.service.segment;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Email;
import com.sendgrid.helpers.mail.objects.Personalization;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SendGridEmailService extends SmtpEmailService {

  @Override
  public String name() {
    return "sendgrid";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().sendgrid != null && !env.getConfig().sendgrid.isEmpty();
  }

  @Override
  public void sendEmailTemplate(String subject, String template, Map<String, Object> data, List<String> tos, String from) {
    Optional<EnvironmentConfig.CommunicationPlatform> _sg = env.getConfig().sendgrid.stream().filter(sg -> sg.transactionalSender).findFirst();
    if (_sg.isEmpty()) {
      env.logJobError("unable to find SendGrid config with transactionalSender=true");
      return;
    }

    SendGrid sg = new SendGrid(_sg.get().secretKey);
    Request request = new Request();
    request.setMethod(Method.POST);
    request.setEndpoint("/mail/send");

    Mail mail = new Mail();

    for (String to : tos) {
      Personalization personalization = new Personalization();
      personalization.addTo(new Email(to));
      for (Map.Entry<String, Object> d : data.entrySet()) {
        personalization.addDynamicTemplateData(d.getKey(), d.getValue());
      }
      mail.addPersonalization(personalization);
    }

    if (from.contains("<") && from.contains(">")) {
      // ex: Brett Meyer <brett@impactupgrade.com>
      String[] split = from.split("<");
      mail.setFrom(new Email(split[1].replace(">", "").trim(), split[0].trim()));
    } else {
      mail.setFrom(new Email(from));
    }
    mail.setSubject(subject);
    mail.templateId = template;

    try {
      request.setBody(mail.build());
      Response response = sg.api(request);
      env.logJobInfo("SendGrid response: code={} body={}", response.getStatusCode(), response.getBody());
    } catch (Exception e) {
      env.logJobError("failed to send email to {} from {}", String.join(",", tos), from, e);
    }
  }

  @Override protected String host() { return "smtp.sendgrid.net"; }
  @Override protected String port() { return "587"; }
  @Override protected String username() { return "apikey"; }
  @Override
  protected String password() {
    EnvironmentConfig.CommunicationPlatform config = getConfig();
    if (config == null || Strings.isNullOrEmpty(config.secretKey)) {
      // legacy support
      return System.getenv("SENDGRID_KEY");
    }
    return config.secretKey;
  }

  private EnvironmentConfig.CommunicationPlatform getConfig() {
    // Find the transactionalSender. If it's not explicitly set, assume the first (or only).
    return env.getConfig().sendgrid.stream().filter(ep -> ep.transactionalSender).findFirst().orElse(
        env.getConfig().sendgrid.stream().findFirst().orElse(null)
    );
  }
}
