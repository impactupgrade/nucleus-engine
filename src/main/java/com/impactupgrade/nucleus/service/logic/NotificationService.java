package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.service.segment.CrmService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.mail.MessagingException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Objects;

public class NotificationService {

    private static final Logger log = LogManager.getLogger(NotificationService.class);

    private final Environment env;

    public NotificationService(Environment env) {
        this.env = env;
    }

    // TODO: This needs refactored! env.json allows a flexible configuration of notifications to be set up for specific
    //  contexts. Don't force the caller of this class to know ahead of time what to send. Instead, let it simply call
    //  sendNotification(...) and we'll check out notification.email, notification.sms, and notification.task here.

    public void sendEmailNotification(String subject, String textBody, String notificationsKey) throws MessagingException {
        sendEmailNotification(subject, textBody, false, notificationsKey);
    }

    public void sendEmailNotification(String subject, String body, boolean isHtml, String notificationsKey) throws MessagingException {
        EnvironmentConfig.Notifications notifications = env.getConfig().notifications.get(notificationsKey);

        if (Objects.isNull(notifications) || Objects.isNull(notifications.email)) {
            // Nothing to do
            return;
        }
        EnvironmentConfig.Notification emailNotification = notifications.email;
        if (!isValidEmailNotification(emailNotification)) {
            log.warn("Email notification is not valid (missing required parameters). Returning...");
            return;
        }
        String emailFrom = emailNotification.from;
        String emailTo = String.join(",", emailNotification.to);
        env.transactionalEmailService().sendEmailText(subject, body, isHtml, emailTo, emailFrom);
    }

    public void sendSMSNotification(String smsText, String notificationsKey) {
        EnvironmentConfig.Notifications notifications = env.getConfig().notifications.get(notificationsKey);

        if (Objects.isNull(notifications) || Objects.isNull(notifications.sms)) {
            // Nothing to do
            return;
        }
        EnvironmentConfig.Notification smsNotification = notifications.sms;
        // TODO: validation
        //String fromPhoneNumber = smsNotification.from;
        //List<String> toPhoneNumbers = smsNotification.to;
        // TODO: send sms messages
    }

    public void createCrmTask(CrmService crmService, String targetId, String description, String notificationsKey) throws Exception {
        EnvironmentConfig.Notifications notifications = env.getConfig().notifications.get(notificationsKey);

        if (Objects.isNull(notifications) || Objects.isNull(notifications.task)) {
            // Nothing to do
            return;
        }
        EnvironmentConfig.Task task = notifications.task;
        if (!isValidTask(task)) {
            log.warn("Task notification is not valid (missing required parameters). Returning...");
            return;
        }
        if (Objects.isNull(crmService)) {
            log.warn("Crm Service is not defined. Returning...");
            return;
        }
        if (Strings.isNullOrEmpty(targetId)) {
            log.warn("Target id is not defined. Returning...");
            return;
        }
        LocalDate now = LocalDate.now();
        LocalDate inAWeek = now.plusDays(7);
        Date dueDate = Date.from(inAWeek.atStartOfDay().toInstant(ZoneOffset.UTC)); // TODO: define due date
        String assignTo = task.assignTo;
        String subject = task.subject;

        crmService.insertTask(new CrmTask(
                targetId, assignTo,
                subject, description,
                CrmTask.Status.TO_DO, CrmTask.Priority.MEDIUM, dueDate));
    }

    // Utils
    private boolean isValidEmailNotification(EnvironmentConfig.Notification emailNotification) {
        return Objects.nonNull(emailNotification)
                && !Strings.isNullOrEmpty(emailNotification.from)
                && CollectionUtils.isNotEmpty(emailNotification.to);
    }

    private boolean isValidTask(EnvironmentConfig.Task task) {
        return Objects.nonNull(task)
                && !Strings.isNullOrEmpty(task.assignTo)
                && !Strings.isNullOrEmpty(task.subject);
    }

}
