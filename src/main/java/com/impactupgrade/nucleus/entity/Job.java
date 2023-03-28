package com.impactupgrade.nucleus.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonType;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.CascadeType;
import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;

@Entity
@Table(name = "core_job")
@TypeDef(name = "json", typeClass = JsonType.class)
public class Job {

  @Id
  @GeneratedValue(generator = "job_id_generator")
  @GenericGenerator(
      name = "job_id_generator",
      strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
      parameters = {
          @Parameter(name = "sequence_name", value = "core_job_id_seq"),
          @Parameter(name = "initial_value", value = "1"),
          @Parameter(name = "increment_size", value = "1")
      }
  )
  public Long id;

  @Column(name = "trace_id", nullable = false)
  public String traceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "job_type")
  public JobType jobType;

  @Column(name = "started_by", nullable = false)
  public String startedBy;

  @Column(name = "job_name", nullable = false)
  public String jobName;

  @Column(name = "originating_platform", nullable = false)
  public String originatingPlatform;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "organization_id")
  public Organization org;

  @Type(type = "json")
  // TODO: jsonb, but won't work in H2
  @Column(name = "payload", columnDefinition = "json", nullable = false)
  public JsonNode payload;

  @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
  @Fetch(FetchMode.SUBSELECT)
  public List<JobProgress> jobProgresses;

  @Column(name = "status", nullable = false)
  @Enumerated(EnumType.STRING)
  public JobStatus status;

  @Column(name = "schedule_frequency", nullable = false)
  @Enumerated(EnumType.STRING)
  public JobFrequency scheduleFrequency;

  @Column(name = "schedule_interval", nullable = true)
  public Integer scheduleInterval;

  @Column(name = "schedule_start", nullable = false)
  public Instant scheduleStart;

  @Column(name = "schedule_end", nullable = true)
  public Instant scheduleEnd;

  @Column(name = "sequence_order", nullable = true)
  @Enumerated(EnumType.STRING)
  public JobSequenceOrder sequenceOrder;

  @Column(name = "started_at", nullable = false)
  public Instant startedAt;

  @Column(name = "ended_at")
  public Instant endedAt;

  @ElementCollection
  @CollectionTable(name = "job_logs", joinColumns = @JoinColumn(name = "job_id", referencedColumnName = "id"))
  @Column(name = "log")
  @Fetch(FetchMode.SELECT)
  @BatchSize(size = 10)
  public List<String> logs = new LinkedList<>();

}
