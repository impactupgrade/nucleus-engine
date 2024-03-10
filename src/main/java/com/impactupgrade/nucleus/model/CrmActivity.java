package com.impactupgrade.nucleus.model;

import java.util.Calendar;

public class CrmActivity extends CrmRecord {

  public String targetId;
  public String assignTo; // User ID
  public String subject;
  public String description;
  public Type type;
  public Status status;
  public Priority priority;
  public Calendar dueDate;
  public String externalReference;

  public CrmActivity() {
  }

  public CrmActivity(String id) {
    super(id);
  }

  public CrmActivity(
      String id,
      String targetId,
      String assignTo,
      String subject,
      String description,
      Type type,
      Status status,
      Priority priority,
      Calendar dueDate,
      String externalReference,
      Object crmRawObject,
      String crmUrl
  ) {
    super(id, crmRawObject, crmUrl);

    this.targetId = targetId;
    this.assignTo = assignTo;
    this.subject = subject;
    this.description = description;
    this.type = type;
    this.status = status;
    this.priority = priority;
    this.dueDate = dueDate;
    this.externalReference = externalReference;
  }

  public enum Type {
    TASK,
    EMAIL,
    LIST_EMAIL,
    CADENCE,
    CALL
  }

  public enum Status {
    TO_DO,
    IN_PROGRESS,
    DONE
  }

  public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
  }

}
