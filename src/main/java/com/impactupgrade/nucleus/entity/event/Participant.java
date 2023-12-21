package com.impactupgrade.nucleus.entity.event;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "events_participant")
public class Participant {

  @Id
  public UUID id;

  @Column(name = "mobile_phone")
  public String mobilePhone;

  @Column(name = "responded")
  public Boolean responded;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "event_id", nullable = false)
  public Event event;

  @Column(name = "created_datetime")
  public ZonedDateTime createdDateTime;
}
