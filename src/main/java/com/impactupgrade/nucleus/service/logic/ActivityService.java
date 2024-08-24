/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.CrmActivity;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.service.segment.CrmService;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ActivityService {

  private final CrmService crmService;

  public ActivityService(Environment env) {
    crmService = env.primaryCrmService();
  }

  public void upsertActivities(List<String> targetIds, CrmActivity.Type type, String activityId, Calendar date,
      String subject, String messageBody) throws Exception {
    List<String> extRefs = targetIds.stream().map(targetId -> targetId + "::" + activityId).toList();
    Map<String, CrmActivity> existingCrmActivities = crmService.getActivitiesByExternalRefs(extRefs).stream()
        .collect(Collectors.toMap(a -> a.externalReference, a -> a));

    for (String targetId : targetIds) {
      String extRef = targetId + "::" + activityId;

      CrmActivity crmActivity;

      if (existingCrmActivities.containsKey(extRef)) {
        crmActivity = existingCrmActivities.get(extRef);
      } else {
        crmActivity = new CrmActivity();
      }

      crmActivity.targetId = targetId;
      crmActivity.subject = subject;
      crmActivity.type = type;
      crmActivity.status = CrmActivity.Status.DONE;
      crmActivity.priority = CrmActivity.Priority.LOW;
      crmActivity.dueDate = date;
      crmActivity.externalReference = extRef;

      if (Strings.isNullOrEmpty(crmActivity.description)) {
        crmActivity.description = messageBody;
      } else {
        crmActivity.description += "\n\n" + messageBody;
      }

      if (Strings.isNullOrEmpty(crmActivity.id)) {
        crmService.batchInsertActivity(crmActivity);
      } else {
        crmService.batchUpdateActivity(crmActivity);
      }
    }

    crmService.batchFlush();
  }

  public void upsertActivityFromEmails(List<String> emails, CrmActivity.Type type, String activityId,
      Calendar date, String subject, String messageBody) throws Exception {
    List<CrmContact> crmContacts = crmService.getContactsByEmails(emails);
    List<String> targetIds = crmContacts.stream().map(c -> c.id).toList();
    upsertActivities(targetIds, type, activityId, date, subject, messageBody);
  }

  public void upsertActivityFromPhoneNumbers(List<String> phoneNumbers, CrmActivity.Type type, String activityId,
      Calendar date, String subject, String messageBody) throws Exception {
    List<CrmContact> crmContacts = crmService.getContactsByPhones(phoneNumbers);
    List<String> targetIds = crmContacts.stream().map(c -> c.id).toList();
    upsertActivities(targetIds, type, activityId, date, subject, messageBody);
  }
}
