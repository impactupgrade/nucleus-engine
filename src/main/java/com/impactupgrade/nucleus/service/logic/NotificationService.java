package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.apache.commons.collections.CollectionUtils;

import java.util.Calendar;

public class NotificationService {

  private final Environment env;

  public NotificationService(Environment env) {
    this.env = env;
  }

  public boolean notificationConfigured(String notificationKey) {
    EnvironmentConfig.Notifications notificationsConfig = env.getConfig().notifications.get(notificationKey);
    return notificationsConfig != null;
  }

  public void sendNotification(String subject, String body, String notificationKey) throws Exception {
    sendNotification(subject, body, null, notificationKey);
  }

  public void sendNotification(String subject, String body, String targetId, String notificationKey) throws Exception {
    if (!notificationConfigured(notificationKey)) {
      env.logJobInfo("no notification configured for: {}", notificationKey);

      // nothing to do
      return;
    }

    EnvironmentConfig.Notifications notificationsConfig = env.getConfig().notifications.get(notificationKey);

    if (notificationsConfig.email != null) {
      sendEmailNotification(subject, body, notificationsConfig.email);
    }
    if (notificationsConfig.sms != null) {
      sendSmsNotification(body, notificationsConfig.sms);
    }
    if (notificationsConfig.task != null) {
      createCrmTask(subject, body, targetId, notificationsConfig.task);
    }
  }

  protected void sendEmailNotification(String subject, String body, EnvironmentConfig.Notification notificationConfig) {
    if (Strings.isNullOrEmpty(notificationConfig.from) || CollectionUtils.isEmpty(notificationConfig.to)) {
      env.logJobWarn("Email notification is not valid (missing required parameters). Skipping notification...");
      return;
    }
    String emailFrom = notificationConfig.from;
    String emailTo = String.join(",", notificationConfig.to);
    env.transactionalEmailService().sendEmailText(subject, body, true, emailTo, emailFrom);

    env.logJobInfo("emailed notification to: {}", emailTo);
  }

  protected void sendSmsNotification(String body, EnvironmentConfig.Notification notificationConfig) {
    if (CollectionUtils.isEmpty(notificationConfig.to)) {
      env.logJobWarn("Email notification is not valid (missing required parameters). Skipping notification...");
      return;
    }

    for (String to : notificationConfig.to) {
      env.twilioClient().sendMessage(to, body);

      env.logJobInfo("texted notification to: {}", to);
    }
  }

  protected void createCrmTask(String subject, String body, String targetId, EnvironmentConfig.Task notificationConfig)
      throws Exception {
    CrmService crmService = env.primaryCrmService();

    if (crmService == null) {
      env.logJobInfo("CRM is not defined. Skipping notification...");
      return;
    }

    if (Strings.isNullOrEmpty(targetId)) {
      env.logJobInfo("CRM Target ID is not defined. Skipping notification...");
      return;
    }

    Calendar dueDate = Calendar.getInstance();
    // TODO: For now, push it out a week, but make this configurable?
    dueDate.add(Calendar.HOUR, 7 * 24);
    String assignTo = notificationConfig.assignTo;

    env.logJobInfo("attaching a Task CRM notification to {} and assigning to {}", targetId, assignTo);

    crmService.insertTask(new CrmTask(
        targetId, assignTo,
        subject, body,
        CrmTask.Status.TO_DO, CrmTask.Priority.MEDIUM, dueDate
    ));
  }
}
