package com.impactupgrade.nucleus.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.Date;

@Entity
@Table(name = "webhook_request", schema = "public")
public class WebhookRequest {
    @Id
    public String id;
    @Column(name = "payload_type", nullable = false)
    public String payloadType;
    @Column(name = "payload", nullable = false)
    public String payload;
    @Column(name = "error_message")
    public String errorMessage;
    @Column(name = "attempt_count")
    public int attemptCount;
    @Column(name = "first_attempt_time", nullable = false)
    public Date firstAttemptTime;
    @Column(name = "last_attempt_time")
    public Date lastAttemptTime;

}