package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.SessionFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.time.Instant;

@Path("/scheduled-job")
public class ScheduledJobController {

  private static final Logger log = LogManager.getLogger(ScheduledJobController.class);

  protected final EnvironmentFactory envFactory;
  protected final SessionFactory sessionFactory;

  public ScheduledJobController(EnvironmentFactory envFactory, SessionFactory sessionFactory) {
    this.envFactory = envFactory;
    this.sessionFactory = sessionFactory;
  }

  @GET
  public Response execute(@Context HttpServletRequest request) {
    log.info("executing scheduled jobs");

    Environment env = envFactory.init(request);
    new Thread(() -> {
      try {
        Instant now = Instant.now();
        env.scheduledJobService(sessionFactory).processJobSchedules(now);
      } catch (Exception e) {
        log.error("scheduled job failed", e);
      }
    }).start();
    return Response.ok().build();
  }
}
