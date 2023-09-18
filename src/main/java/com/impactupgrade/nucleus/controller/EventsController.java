package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.entity.event.Event;
import com.impactupgrade.nucleus.entity.event.Interaction;
import com.impactupgrade.nucleus.entity.event.InteractionOption;
import com.impactupgrade.nucleus.entity.event.Participant;
import com.impactupgrade.nucleus.entity.event.ResponseOption;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.twilio.twiml.MessagingResponse;
import com.twilio.twiml.messaging.Body;
import com.twilio.twiml.messaging.Message;

import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Optional;
import java.util.UUID;

@Path("/events")
public class EventsController {

  protected final EnvironmentFactory envFactory;

  protected final HibernateDao<Long, Event> eventDao;
  protected final HibernateDao<Long, Interaction> interactionDao;
  protected final HibernateDao<Long, Participant> participantDao;
  protected final HibernateDao<Long, InteractionOption> interactionOptionDao;
  protected final HibernateDao<Long, com.impactupgrade.nucleus.entity.event.Response> responseDao;

  public EventsController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;

    eventDao = new HibernateDao<>(Event.class);
    interactionDao = new HibernateDao<>(Interaction.class);
    participantDao = new HibernateDao<>(Participant.class);
    responseDao = new HibernateDao<>(com.impactupgrade.nucleus.entity.event.Response.class);
    interactionOptionDao = new HibernateDao<>(com.impactupgrade.nucleus.entity.event.InteractionOption.class);
  }

  // TODO: Twilio specific
  @Path("/inbound")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_XML)
  public Response inbound(
      @FormParam("From") String from,
      @FormParam("Body") String _body
  ) throws Exception {
    String body = _body.trim();

    Optional<Event> eventForOptIn = eventDao.getQueryResult(
        "FROM Event WHERE lower(keyword) = lower(:keyword) AND status = 'ACTIVE'",
        query -> {
          query.setParameter("keyword", body);
        }
    );
    if (eventForOptIn.isPresent()) {
      createParticipant(from, eventForOptIn.get());

      Body responseBody = new Body.Builder("Thank you for joining!").build();
      Message responseSms = new Message.Builder().body(responseBody).build();
      MessagingResponse response = new MessagingResponse.Builder().message(responseSms).build();
      return Response.ok().entity(response.toXml()).build();
    } else {
      createResponse(from, body);
      MessagingResponse response = new MessagingResponse.Builder().build();
      return Response.ok().entity(response.toXml()).build();
    }
  }

  private void createParticipant(String from, Event event) {
    // prevent duplicates
    Optional<Participant> _participant = participantDao.getQueryResult(
        "FROM Participant WHERE mobilePhone = :mobilePhone AND event.status = 'ACTIVE'",
        query -> {
          query.setParameter("mobilePhone", from);
        }
    );
    if (_participant.isPresent()) {
      return;
    }

    Participant participant = new Participant();
    participant.id = UUID.randomUUID();
    participant.mobilePhone = from;
    participant.event = event;
    participantDao.create(participant);
  }

  // TODO: Post-demo, allow multiple free-form responses, allow multiple responses for multi-select options,
  //  but disallow multiple responses for all others.
  private void createResponse(String from, String body) {
    Optional<Participant> participant = participantDao.getQueryResult(
        "FROM Participant p JOIN FETCH p.event e WHERE p.mobilePhone = :mobilePhone AND e.status = 'ACTIVE'",
        query -> {
          query.setParameter("mobilePhone", from);
        }
    );
    if (participant.isPresent()) {
      Optional<Interaction> interaction = interactionDao.getQueryResult(
          "FROM Interaction WHERE event.id = :eventId AND event.status = 'ACTIVE' AND status = 'ACTIVE'",
          query -> {
            query.setParameter("eventId", participant.get().event.id);
          }
      );

      if (interaction.isPresent()) {
        com.impactupgrade.nucleus.entity.event.Response response = new com.impactupgrade.nucleus.entity.event.Response();
        response.id = UUID.randomUUID();
        response.participant = participant.get();
        response.interaction = interaction.get();

        switch (interaction.get().type) {
          case MULTI, SELECT -> {
            //TODO: might need to expand this to be more robust, currently only separating by commas and whitespace chars
            String[] optionValues = body.trim().split("[,\\s]+");
            //TODO will likely need some input validation/cleaning for the way participants will be sending in their selections, "1" vs "One" etc.
            for (String optionValue : optionValues) {
              Optional<InteractionOption> option = interactionOptionDao.getQueryResult(
                  "SELECT Id FROM interaction_option WHERE UPPER(value) = UPPER(optionValue)",
                  query -> {
                    query.setParameter("optionValue", optionValue);
                  }
              );
              if (option.isPresent()) {
                ResponseOption newOption = new ResponseOption();
                newOption.id = UUID.randomUUID();
                newOption.response = response;
                newOption.value = option.get();
                response.selectedOptions.add(newOption);
              }
            }
          }
          case FREE -> {
            response.freeResponse = body;
          }
          default -> {

          }
        }

        responseDao.create(response);
      }

    }
  }

}
