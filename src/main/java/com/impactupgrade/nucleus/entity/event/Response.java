package com.impactupgrade.nucleus.entity.event;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "events_response")
public class Response {

  @Id
  public UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "participant_id", nullable = false)
  public Participant participant;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "interaction_id", nullable = false)
  public Interaction interaction;

  @Column(name = "free_response")
  public String freeResponse;

  @Column(name = "selected_options")
  public List<ResponseOption> selectedOptions ;
}
