package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentConfig;
import com.impactupgrade.nucleus.model.CrmTask;
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

    // TODO: Decide which parameters should be "static" (taken from config file)
    // and which ones should be "dynamic" (textBody, htmlBody etc.)
    public void sendEmailNotification(EnvironmentConfig.EmailNotification emailNotification, String textBody, String htmlBody) throws MessagingException {
        if (Objects.isNull(emailNotification)) {
            log.info("Email notification is not defined. Returning...");
            return;
        }
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

    public void sendSMSNotification(EnvironmentConfig.Notification smsNotification, String smsText) {
        // TODO: validation
        //String fromPhoneNumber = smsNotification.from;
        //List<String> toPhoneNumbers = smsNotification.to;
        // TODO: send sms messages
    }

    public void createCrmTask(EnvironmentConfig.Task task, String crmService, String targetId, String description) throws Exception {
        if (Objects.isNull(task)) {
            log.info("Task is not defined. Returning...");
            return;
        }
        if (!isValidTask(task)) {
            log.warn("Task notification is not valid (missing required parameters). Returning...");
            return;
        }
        if (Strings.isNullOrEmpty(crmService)) {
            log.warn("Crm Service name is not defined. Returning...");
            return;
        }
        if (Objects.isNull(env.crmService(crmService))) {
            log.warn("Failed to find crmService for name '{}'. Returning...", crmService);
            return;
        }
        if (Strings.isNullOrEmpty(targetId)) {
            log.warn("Target id is not defined. Returning...");
        }
        LocalDate now = LocalDate.now();
        LocalDate inAWeek = now.plusDays(7);
        Date dueDate = Date.from(inAWeek.atStartOfDay().toInstant(ZoneOffset.UTC)); // TODO: define due date
        String assignTo = task.assignTo;
        String subject = task.subject;

        env.crmService(crmService).insertTask(new CrmTask(
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
