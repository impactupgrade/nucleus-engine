package com.impactupgrade.nucleus.controller;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.model.CrmContact;
import com.impactupgrade.nucleus.service.segment.CrmService;
import com.impactupgrade.nucleus.service.segment.EmailPlatformService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.util.Calendar;
import java.util.List;
import java.util.stream.Collectors;

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
    CrmService crmService = env.primaryCrmService();

    Calendar lastSync = Calendar.getInstance();
    // run daily, but setting this high to catch previous misses
    int syncDays = 3;
    lastSync.add(Calendar.DATE, -syncDays);

    Runnable thread = () -> {
      try {
        // TODO: We'll likely need a configurable set of filters...
        List<CrmContact> marketingContacts = crmService.getContactsUpdatedSince(lastSync).stream()
            .filter(c -> !Strings.isNullOrEmpty(c.email)).collect(Collectors.toList());
        List<CrmContact> donorContacts = crmService.getDonorContactsSince(lastSync).stream()
            .filter(c -> !Strings.isNullOrEmpty(c.email)).collect(Collectors.toList());

        for (EmailPlatformService emailPlatformService : env.allEmailPlatformServices()) {
          try {
            emailPlatformService.syncContacts(marketingContacts);
            emailPlatformService.syncDonors(donorContacts);
          } catch (Exception e) {
            log.error("email dailySync failed for {}", emailPlatformService.name(), e);
          }
        }
      } catch (Exception e) {
        log.error("email dailySync failed", e);
      }
    };
    new Thread(thread).start();

    return Response.ok().build();
  }
}
