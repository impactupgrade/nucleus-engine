package com.impactupgrade.nucleus.entity.event;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "interaction_option")
public class InteractionOption {

  @Id
  public UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "interaction_id", nullable = false)
  public Interaction interaction;

  @Column(name = "`value`")
  public String value;
}
