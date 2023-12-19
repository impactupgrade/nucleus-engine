package com.impactupgrade.nucleus.controller;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.entity.event.Event;
import com.impactupgrade.nucleus.entity.event.Interaction;
import com.impactupgrade.nucleus.entity.event.InteractionOption;
import com.impactupgrade.nucleus.entity.event.InteractionType;
import com.impactupgrade.nucleus.entity.event.Participant;
import com.impactupgrade.nucleus.entity.event.ResponseOption;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.twilio.twiml.MessagingResponse;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Path("/events")
public class EventsController {

  protected final EnvironmentFactory envFactory;

  protected final HibernateDao<Long, Event> eventDao;
  protected final HibernateDao<Long, Interaction> interactionDao;
  protected final HibernateDao<Long, Participant> participantDao;
  protected final HibernateDao<Long, InteractionOption> interactionOptionDao;
  protected final HibernateDao<Long, com.impactupgrade.nucleus.entity.event.Response> responseDao;
  protected final HibernateDao<Long, ResponseOption> responseOptionDao;

  protected final LoadingCache<String, Optional<Event>> eventKeywordToEventCache;

  public EventsController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;

    eventDao = new HibernateDao<>(Event.class);
    interactionDao = new HibernateDao<>(Interaction.class);
    participantDao = new HibernateDao<>(Participant.class);
    interactionOptionDao = new HibernateDao<>(InteractionOption.class);
    responseDao = new HibernateDao<>(com.impactupgrade.nucleus.entity.event.Response.class);
    responseOptionDao = new HibernateDao<>(ResponseOption.class);

    eventKeywordToEventCache = CacheBuilder.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build(new CacheLoader<>() {
          @Override
          public Optional<Event> load(String keyword) {
            return eventDao.getQueryResult(
                "FROM Event WHERE lower(keyword) = lower(:keyword) AND status = 'ACTIVE'",
                query -> {
                  query.setParameter("keyword", keyword);
                }
            );
          }
        });
  }

  // TODO: Twilio specific
  @Path("/inbound")
  @POST
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @Produces(MediaType.APPLICATION_XML)
  public Response inbound(
      @FormParam("From") String from,
      @FormParam("Body") String _body,
      @Context HttpServletRequest request
  ) throws Exception {
    String body = _body.trim();
    Environment env = envFactory.init(request);

    env.logJobInfo("from={} body={}", from, body);

    Optional<Event> activeEventByKeyword = eventKeywordToEventCache.get(body);

    if (activeEventByKeyword != null && activeEventByKeyword.isPresent()) {
      createParticipant(from, activeEventByKeyword.get());

      // We don't use Twiml responses here since this may be positioned in a Studio Flow.
      env.twilioClient().sendMessage(from, "Thank you for joining!");

      MessagingResponse response = new MessagingResponse.Builder().build();
      return Response.ok().entity(response.toXml()).build();
    } else {
      createResponse(from, body, env);
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
    participant.responded = false;
    participantDao.insert(participant);
  }

  private void createResponse(String from, String body, Environment env) {
    // TODO: Cache, with a decently long TTL?
    Optional<Participant> participant = participantDao.getQueryResult(
        "FROM Participant p JOIN FETCH p.event e WHERE p.mobilePhone = :mobilePhone AND e.status = 'ACTIVE'",
        query -> {
          query.setParameter("mobilePhone", from);
        }
    );
    if (participant.isEmpty()) {
      env.logJobWarn("participant {} not opted into an event; dropping the response ({})", from, body);
      return;
    }

    // TODO: Cache, with a really short TTL (3 sec?)
    Optional<Interaction> interaction = interactionDao.getQueryResult(
        "FROM Interaction WHERE event.id = :eventId AND event.status = 'ACTIVE' AND status = 'ACTIVE'",
        query -> {
          query.setParameter("eventId", participant.get().event.id);
        }
    );

    if (interaction.isEmpty()) {
      env.logJobWarn("participant {} sent response, but no active interaction; dropping the response ({})", from, body);
      return;
    }

    // Skip FREE in this, since we allow multiple per person per interaction.
    Optional<com.impactupgrade.nucleus.entity.event.Response> existingResponse = responseDao.getQueryResult(
        "FROM Response WHERE participant.id = :participantId AND interaction.id = :interactionId AND interaction.type != 'FREE'",
        query -> {
          query.setParameter("participantId", participant.get().id);
          query.setParameter("interactionId", interaction.get().id);
        }
    );

    com.impactupgrade.nucleus.entity.event.Response response;
    if (existingResponse.isPresent()) {
      if (interaction.get().type != InteractionType.MULTI) {
        return;
      }
      response = existingResponse.get();
    } else {
      response = new com.impactupgrade.nucleus.entity.event.Response();
      response.id = UUID.randomUUID();
      response.participant = participant.get();
      response.interaction = interaction.get();

      if (interaction.get().type == InteractionType.FREE) {
        response.freeResponse = body;
      }

      responseDao.insert(response);
    }

    switch (interaction.get().type) {
      case MULTI, SELECT -> {
        String[] optionValues = body.trim().split("[,\\s]+");

        for (String optionValue : optionValues) {
          // TODO: Cache with a LONG TTL!
          Optional<InteractionOption> option = interactionOptionDao.getQueryResult(
              "FROM InteractionOption io WHERE UPPER(io.value) = UPPER(:optionValue) AND io.interaction.id = :interactionId",
              query -> {
                query.setParameter("optionValue", optionValue);
                query.setParameter("interactionId", interaction.get().id);
              }
          );
          if (option.isEmpty()) {
            env.logJobWarn("Failed to find a ResponseOption with value: {}, interaction: {}", optionValue, interaction.get().id);
            continue;
          }

          if (interaction.get().type == InteractionType.MULTI) {
            Optional<ResponseOption> existingResponseOption = responseOptionDao.getQueryResult(
                "FROM ResponseOption WHERE response.id = :responseId AND interactionOption.value = :optionValue",
                query -> {
                  query.setParameter("responseId", response.id);
                  query.setParameter("optionValue", optionValue);
                }
            );
            if (existingResponseOption.isPresent()) {
              continue;
            }
          }

          ResponseOption responseOption = new ResponseOption();
          responseOption.id = UUID.randomUUID();
          responseOption.response = response;
          responseOption.interactionOption = option.get();

          responseOptionDao.insert(responseOption);
        }
      }
    }

    // update the Participant to "responded", if not set already
    if (participant.get().responded == null || !participant.get().responded) {
      participant.get().responded = true;
      participantDao.update(participant.get());
    }
  }
}
