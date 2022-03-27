package com.impactupgrade.nucleus.model;

import java.util.Calendar;

public class CrmTask {

  public String targetId;
  public String assignTo; // User ID
  public String subject;
  public String description;
  public Status status;
  public Priority priority;
  public Calendar dueDate;

  public CrmTask() {
  }

  public CrmTask(String targetId, String assignTo, String subject, String description,
      Status status, Priority priority, Calendar dueDate) {
    this.targetId = targetId;
    this.assignTo = assignTo;
    this.subject = subject;
    this.description = description;
    this.status = status;
    this.priority = priority;
    this.dueDate = dueDate;
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
