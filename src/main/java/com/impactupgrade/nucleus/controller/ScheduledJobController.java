package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.time.Instant;

@Path("/scheduled-job")
public class ScheduledJobController {

  protected final EnvironmentFactory envFactory;

  public ScheduledJobController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @GET
  public Response execute(@Context HttpServletRequest request) {
    Environment env = envFactory.init(request);

    env.logJobInfo("executing scheduled jobs");

    new Thread(() -> {
      try {
        Instant now = Instant.now();
        env.scheduledJobService().processJobSchedules(now);
      } catch (Exception e) {
        env.logJobError("scheduled jobs failed", e);
      }
    }).start();
    return Response.ok().build();
  }
}
