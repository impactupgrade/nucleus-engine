package com.impactupgrade.nucleus.entity.event;

import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "events_event")
public class Event {

  @Id
  public UUID id;

  public String keyword;

  @Enumerated(EnumType.STRING)
  public EventStatus status;
}
