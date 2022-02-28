package com.impactupgrade.nucleus.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonType;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.CascadeType;
import javax.persistence.Column;
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
import java.util.List;

@Entity
@Table(name = "job")
@TypeDef(name = "json", typeClass = JsonType.class)
public class Job {

  @Id
  @GeneratedValue(generator = "job_id_generator")
  @GenericGenerator(
      name = "job_id_generator",
      strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
      parameters = {
          @Parameter(name = "sequence_name", value = "job_id"),
          @Parameter(name = "initial_value", value = "1"),
          @Parameter(name = "increment_size", value = "1")
      }
  )
  public Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "job_type")
  public JobType jobType;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "org_id")
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
  public JobFrequency frequency;

  @Column(name = "schedule_interval", nullable = true)
  public Integer interval;

  @Column(name = "schedule_start", nullable = false)
  public Instant start;

  @Column(name = "schedule_end", nullable = true)
  public Instant end;

  @Column(name = "sequence_order", nullable = true)
  @Enumerated(EnumType.STRING)
  public JobSequenceOrder sequenceOrder;
}
