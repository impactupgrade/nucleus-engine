package com.impactupgrade.nucleus.model;

import java.util.Date;

public class CrmTask {

    public String targetId;
    public String assignTo; //accountId

    public String subject;
    public String description;

    public Status status;
    public Priority priority;

    public Date dueDate;

    public CrmTask() {}

    public CrmTask(String targetId, String assignTo, String subject, String description,
                   Status status, Priority priority, Date dueDate) {
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
