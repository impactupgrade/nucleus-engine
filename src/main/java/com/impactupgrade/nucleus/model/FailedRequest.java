package com.impactupgrade.nucleus.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.Date;

@Getter
@Setter
@Entity
@Table(name = "failed_request", schema = "public")
public class FailedRequest {
    @Id
    private String id;
    @Column(name = "payload", nullable = false)
    private String payload;
    @Column(name = "error_message", nullable = false)
    private String errorMessage;
    @Column(name = "attempt_count")
    private int attemptCount;
    @Column(name = "first_attempt_time", nullable = false)
    private Date firstAttemptTime;
    @Column(name = "last_attempt_time")
    private Date lastAttemptTime;

}