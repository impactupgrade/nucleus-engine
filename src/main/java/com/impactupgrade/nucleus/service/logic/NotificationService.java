/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmActivity;
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

  public void sendNotification(Notification notification, String notificationKey) {
    sendNotification(notification, null, notificationKey);
  }

  public void sendNotification(Notification notification, String targetId, String notificationKey) {
    if (!notificationConfigured(notificationKey)) {
      env.logJobInfo("no notification configured for: {}", notificationKey);

      // nothing to do
      return;
    }

    EnvironmentConfig.Notifications notificationsConfig = env.getConfig().notifications.get(notificationKey);

    if (notificationsConfig.email != null) {
      sendEmailNotification(notification, notificationsConfig.email);
    }
    if (notificationsConfig.sms != null) {
      sendSmsNotification(notification, notificationsConfig.sms);
    }
    if (notificationsConfig.task != null) {
      createCrmTask(notification, targetId, notificationsConfig.task);
    }
  }

  protected void sendEmailNotification(Notification notification, EnvironmentConfig.Notification notificationConfig) {
    if (Strings.isNullOrEmpty(notificationConfig.from) || CollectionUtils.isEmpty(notificationConfig.to)) {
      env.logJobWarn("Email notification is not valid (missing required parameters). Skipping notification...");
      return;
    }
    String emailFrom = notificationConfig.from;
    String emailTo = String.join(",", notificationConfig.to);
    env.transactionalEmailService().sendEmailText(notification.emailSubject, notification.emailBody, true, emailTo, emailFrom);

    env.logJobInfo("emailed notification to: {}", emailTo);
  }

  protected void sendSmsNotification(Notification notification, EnvironmentConfig.Notification notificationConfig) {
    if (CollectionUtils.isEmpty(notificationConfig.to)) {
      env.logJobWarn("SMS notification is not valid (missing required parameters). Skipping notification...");
      return;
    }

    for (String to : notificationConfig.to) {
      env.twilioClient().sendMessage(to, notification.smsBody);

      env.logJobInfo("texted notification to: {}", to);
    }
  }

  protected void createCrmTask(Notification notification, String targetId, EnvironmentConfig.Task notificationConfig) {
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

    try {
      crmService.batchInsertActivity(new CrmActivity(
          null,
          targetId,
          assignTo,
          notification.taskSubject,
          notification.taskBody,
          CrmActivity.Type.TASK,
          CrmActivity.Status.TO_DO,
          CrmActivity.Priority.MEDIUM,
          dueDate,
          null,
          null,
          null
      ));
      crmService.batchFlush();
    } catch (Exception e) {
      env.logJobWarn("unable to create notification", e);
    }
  }

  public static class Notification {
    public String emailSubject;
    public String emailBody;
    public String smsBody;
    public String taskSubject;
    public String taskBody;

    public Notification(String subject, String body) {
      emailSubject = subject;
      emailBody = body;
      smsBody = body;
      taskSubject = subject;
      taskBody = body;
    }
  }
}
