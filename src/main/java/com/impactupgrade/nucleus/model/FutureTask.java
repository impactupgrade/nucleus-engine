package com.impactupgrade.nucleus.model;

import com.vladmihalcea.hibernate.type.json.JsonType;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "future_task", schema = "public")
@TypeDef(name = "json", typeClass = JsonType.class)
public class FutureTask {

    @Id
    @GeneratedValue(generator = "sequence-generator")
    @GenericGenerator(
            name = "sequence-generator",
            strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
            parameters = {
                    @Parameter(name = "sequence_name", value = "future_task_id"),
                    @Parameter(name = "initial_value", value = "1"),
                    @Parameter(name = "increment_size", value = "1")
            }
    )
    public Long id;

    @Column(name = "scheduled_at")
    public LocalDateTime scheduledAt;

    @Type(type = "json")
    @Column(name = "configuration", columnDefinition = "jsonb", nullable = false)
    public Map<String, String> configuration;


}
