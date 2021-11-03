package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmTask;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.util.EmailUtil;
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

    public void sendEmailNotification(String textBody, EnvironmentConfig.Notifications notifications) throws MessagingException {
        sendEmailNotification(textBody, null, notifications);
    }

    public void sendEmailNotification(String textBody, String htmlBody, EnvironmentConfig.Notifications notifications) throws MessagingException {
        if (Objects.isNull(notifications) || Objects.isNull(notifications.email)) {
            // Nothing to do
            return;
        }
        EnvironmentConfig.EmailNotification emailNotification = notifications.email;
        if (!isValidEmailNotification(emailNotification)) {
            log.warn("Email notification is not valid (missing required parameters). Returning...");
            return;
        }
        String emailFrom = emailNotification.from;
        String emailTo = String.join(",", emailNotification.to);
        String emailSubject = emailNotification.subject;
        EmailUtil.sendEmail(emailSubject,
                textBody, htmlBody,
                emailTo, emailFrom);
    }

    public void sendSMSNotification(String smsText, EnvironmentConfig.Notifications notifications) {
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

    public void createCrmTask(CrmService crmService, String targetId, String description, EnvironmentConfig.Notifications notifications) throws Exception {
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
    private boolean isValidEmailNotification(EnvironmentConfig.EmailNotification emailNotification) {
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
