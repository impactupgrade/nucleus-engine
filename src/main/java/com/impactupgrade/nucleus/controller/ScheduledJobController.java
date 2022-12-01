package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

  public ScheduledJobController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @GET
  public Response execute(@Context HttpServletRequest request) {
    log.info("executing scheduled jobs");

    Environment env = envFactory.init(request);
    new Thread(() -> {
      try {
        Instant now = Instant.now();
        env.scheduledJobService().processJobSchedules(now);
      } catch (Exception e) {
        log.error("scheduled jobs failed", e);
      }
    }).start();
    return Response.ok().build();
  }
}
