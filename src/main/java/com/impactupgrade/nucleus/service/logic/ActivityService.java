/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.model.ContactSearch;
import com.impactupgrade.nucleus.model.CrmActivity;
import com.impactupgrade.nucleus.model.CrmContact;
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

  public void upsertActivity(String targetId, ActivityType activityType, String conversationId, String messageBody) throws Exception {
    Optional<CrmActivity> _crmTask = crmService.getActivityByExternalRef(conversationId);
    CrmActivity crmActivity;

    if (_crmTask.isEmpty()) {
      crmActivity = new CrmActivity();
    } else {
      crmActivity = _crmTask.get();
    }

    crmActivity.targetId = targetId;
    crmActivity.subject = activityType.name() + ": " + conversationId;
    crmActivity.type = CrmActivity.Type.CALL;
    crmActivity.status = CrmActivity.Status.DONE;
    crmActivity.priority = CrmActivity.Priority.LOW;
    crmActivity.externalReference = conversationId;

    if (Strings.isNullOrEmpty(crmActivity.description)) {
      crmActivity.description = messageBody;
    } else {
      crmActivity.description += "\n\n" + messageBody;
    }

    if (Strings.isNullOrEmpty(crmActivity.id)) {
      crmService.insertActivity(crmActivity);
    } else {
      crmService.updateActivity(crmActivity);
    }
  }

  public void upsertActivityFromEmail(String email, ActivityType activityType, String conversationId,
      String messageBody) throws Exception {
    Optional<CrmContact> crmContact = crmService.searchContacts(ContactSearch.byEmail(email)).getSingleResult();
    if (crmContact.isPresent()) {
      upsertActivity(crmContact.get().id, activityType, conversationId, messageBody);
    } else {
      env.logJobWarn("unable to find CRM contact for email: {}", email);
    }
  }

  public void upsertActivityFromPhoneNumber(String phoneNumber, ActivityType activityType, String conversationId,
      String messageBody) throws Exception {
    Optional<CrmContact> crmContact = crmService.searchContacts(ContactSearch.byPhone(phoneNumber)).getSingleResult();
    if (crmContact.isPresent()) {
      upsertActivity(crmContact.get().id, activityType, conversationId, messageBody);
    } else {
      env.logJobWarn("unable to find CRM contact for phone number: {}", phoneNumber);
    }
  }
}
