/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

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
    Environment env = envFactory.init(request);

    log.info("executing scheduled jobs");

    new Thread(() -> {
      Instant now = Instant.now();
      env.scheduledJobService().processJobSchedules(now);
    }).start();
    return Response.ok().build();
  }
}
