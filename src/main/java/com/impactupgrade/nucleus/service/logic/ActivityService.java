package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmActivity;
import com.impactupgrade.nucleus.service.segment.CrmService;

import java.util.Optional;

public class ActivityService {

  private final Environment env;
  private final CrmService crmService;

  public ActivityService(Environment env) {
    this.env = env;
    crmService = env.primaryCrmService();
  }

  public enum ActivityType {
    EMAIL, SMS
  }

  public void upsertActivity(ActivityType activityType, String conversationId, String messageSid, String messageBody) throws Exception {
    String subject = activityType.name() + " CONVERSATION: " + conversationId;

    Optional<CrmActivity> _crmTask = crmService.getActivityByExternalRef(subject);
    CrmActivity crmActivity;

    if (_crmTask.isEmpty()) {
      crmActivity = new CrmActivity();
      crmActivity.subject = subject;
      crmActivity.type = CrmActivity.Type.CALL;
      crmActivity.status = CrmActivity.Status.DONE;
      crmActivity.priority = CrmActivity.Priority.MEDIUM;
    } else {
      crmActivity = _crmTask.get();
    }

    if (!Strings.isNullOrEmpty(crmActivity.description)) {
      crmActivity.description += "\n";
    }
    crmActivity.description += messageSid + ": " + messageBody;

    if (Strings.isNullOrEmpty(crmActivity.id)) {
      crmService.insertActivity(crmActivity);
    } else {
      crmService.updateActivity(crmActivity);
    }
  }
}
