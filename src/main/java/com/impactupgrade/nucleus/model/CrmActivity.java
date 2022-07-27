package com.impactupgrade.nucleus.model;

import java.util.Calendar;

public class CrmActivity {

  public String id;
  public String targetId;
  public String assignTo; // User ID
  public String subject;
  public String description;
  public Type type;
  public Status status;
  public Priority priority;
  public Calendar dueDate;

  public CrmActivity() {
  }

  public CrmActivity(String targetId, String assignTo, String subject, String description, Type type, Status status,
      Priority priority, Calendar dueDate) {
    this.targetId = targetId;
    this.assignTo = assignTo;
    this.subject = subject;
    this.description = description;
    this.type = type;
    this.status = status;
    this.priority = priority;
    this.dueDate = dueDate;
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
    DONE;
  }

  public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;
  }

}
