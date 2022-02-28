package com.impactupgrade.nucleus.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonType;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

@Entity
@Table(name = "job_progress")
@TypeDef(name = "json", typeClass = JsonType.class)
public class JobProgress {

  @Id
  @GeneratedValue(generator = "job_progress_id_generator")
  @GenericGenerator(
      name = "job_progress_id_generator",
      strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
      parameters = {
          @Parameter(name = "sequence_name", value = "job_progress_id"),
          @Parameter(name = "initial_value", value = "1"),
          @Parameter(name = "increment_size", value = "1")
      }
  )
  public Long id;

  // Could be a CrmContact, etc.
  @Column(name = "target_id", nullable = false)
  public String targetId;

  @Type(type = "json")
  // TODO: jsonb, but won't work in H2
  @Column(name = "payload", columnDefinition = "json", nullable = false)
  public JsonNode payload;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "job_id")
  public Job job;
}
