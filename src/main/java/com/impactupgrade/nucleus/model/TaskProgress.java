package com.impactupgrade.nucleus.model;

import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "task_progress", schema = "public")
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
public class TaskProgress {

    @Id
    @GeneratedValue(generator = "sequence-generator")
    @GenericGenerator(
            name = "sequence-generator",
            strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
            parameters = {
                    @Parameter(name = "sequence_name", value = "task_progress_id"),
                    @Parameter(name = "initial_value", value = "1"),
                    @Parameter(name = "increment_size", value = "1")
            }
    )
    public Long id;

    @Enumerated(EnumType.STRING)
    public Task task;

    @Column(name = "org_id", nullable = false)
    public String orgId;

    @Column(name = "contact_id", nullable = false)
    public String contactId;

    @Type(type = "jsonb")
    @Column(name = "progress", columnDefinition = "jsonb", nullable = false)
    public String progress;

}
