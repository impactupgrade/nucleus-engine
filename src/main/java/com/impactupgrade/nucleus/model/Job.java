package com.impactupgrade.nucleus.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonType;
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
import javax.persistence.OneToMany;
import javax.persistence.Table;
import java.util.List;

@Entity
@Table(name = "job")
@TypeDef(name = "json", typeClass = JsonType.class)
public class Job {

  @Id
  @GeneratedValue(generator = "sequence-generator")
  @GenericGenerator(
      name = "sequence-generator",
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

  @Column(name = "org_id", nullable = false)
  public String orgId;

  @Type(type = "json")
  @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
  public JsonNode payload;

  @OneToMany(mappedBy = "job", fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
  public List<JobProgress> jobProgresses;
}
