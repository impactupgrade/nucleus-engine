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
  @Path("/sync/daily")
  public Response syncDaily(@Context HttpServletRequest request) throws Exception {
    Environment env = envFactory.init(request);

    Calendar lastSync = Calendar.getInstance();
    // run daily, but setting this high to catch previous misses
    int syncDays = 3;
    lastSync.add(Calendar.DATE, -syncDays);

    Runnable thread = () -> {
      try {
        for (EmailPlatformService emailPlatformService : env.allEmailPlatformServices()) {
          try {
            emailPlatformService.syncContacts(lastSync);
          } catch (Exception e) {
            log.error("email syncDaily failed for {}", emailPlatformService.name(), e);
          }
        }
      } catch (Exception e) {
        log.error("email syncDaily failed", e);
      }
    };
    new Thread(thread).start();

    return Response.ok().build();
  }

  @GET
  @Path("/sync/all")
  public Response syncAll(@Context HttpServletRequest request) throws Exception {
    Environment env = envFactory.init(request);

    Runnable thread = () -> {
      try {
        for (EmailPlatformService emailPlatformService : env.allEmailPlatformServices()) {
          try {
            emailPlatformService.syncContacts(null);
          } catch (Exception e) {
            log.error("email syncAll failed for {}", emailPlatformService.name(), e);
          }
        }
      } catch (Exception e) {
        log.error("email syncAll failed", e);
      }
    };
    new Thread(thread).start();

    return Response.ok().build();
  }
}
