package com.impactupgrade.nucleus.entity.event;


import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "response_option")
public class ResponseOption {

  @Id
  public UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "response_id", nullable = false)
  public Response response;

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinColumn(name = "value")
  public InteractionOption value;
}
