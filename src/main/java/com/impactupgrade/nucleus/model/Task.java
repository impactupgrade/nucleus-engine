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
@Table(name = "task", schema = "public")
@TypeDef(name = "json", typeClass = JsonType.class)
public class Task {

    @Id
    @GeneratedValue(generator = "sequence-generator")
    @GenericGenerator(
            name = "sequence-generator",
            strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
            parameters = {
                    @Parameter(name = "sequence_name", value = "task_id"),
                    @Parameter(name = "initial_value", value = "1"),
                    @Parameter(name = "increment_size", value = "1")
            }
    )
    public Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type")
    public TaskType taskType;

    @Column(name = "org_id", nullable = false)
    public String orgId;

    @Type(type = "json")
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    public JsonNode payload;

    @OneToMany(mappedBy = "task", fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    public List<TaskProgress> taskProgresses;

}
