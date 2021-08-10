package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.service.segment.EmailPlatformService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.util.Calendar;

@Path("/email")
public class EmailController {

  private static final Logger log = LogManager.getLogger(EmailController.class);

  protected final EnvironmentFactory envFactory;

  public EmailController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  // TODO: wire to cron
  @GET
  @Path("/daily")
  public Response dailySync(@Context HttpServletRequest request) throws Exception {
    Environment env = envFactory.init(request);
    Calendar lastSync = Calendar.getInstance();
    // run daily, but setting this high to catch previous misses
    int syncDays = 3;
    lastSync.add(Calendar.DATE, -syncDays);

    Runnable thread = () -> {
      // TODO: Each EmailPlatformService currently queries the CRM on its own. Should instead query first here, then
      //  pass the results down.
      for (EmailPlatformService emailPlatformService : env.allEmailPlatformServices()) {
        try {
          emailPlatformService.syncNewContacts(lastSync);
          emailPlatformService.syncNewDonors(lastSync);
        } catch (Exception e) {
          log.error("dailySync failed", e);
        }
      }
    };
    new Thread(thread).start();

    return Response.ok().build();
  }
}
