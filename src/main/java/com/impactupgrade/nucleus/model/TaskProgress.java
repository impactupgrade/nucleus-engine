package com.impactupgrade.nucleus.model;

import com.vladmihalcea.hibernate.type.json.JsonType;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import org.json.JSONObject;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "task_progress", schema = "public")
@TypeDef(name = "json", typeClass = JsonType.class)
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

    @Column(name = "task_id")
    public Long taskId;

    @Column(name = "contact_id", nullable = false)
    public String contactId;

    @Type(type = "json")
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    public Map<String, String> payload;

}