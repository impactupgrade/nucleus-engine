/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.service.segment.CommunicationService;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.util.Calendar;
import java.util.List;

@Path("/communication")
public class CommunicationController {

  protected final EnvironmentFactory envFactory;

  public CommunicationController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  @GET
  @Path("/sync/daily")
  public Response syncDaily(@QueryParam("service") String serviceName, @QueryParam("syncDays") Integer syncDays,
      @Context HttpServletRequest request) throws Exception {
    Environment env = envFactory.init(request);

    Calendar lastSync = Calendar.getInstance();
    // run daily, but setting this high to catch previous misses
    if (syncDays == null || syncDays <= 0) {
      syncDays = 3;
    }
    lastSync.add(Calendar.DATE, -syncDays);

    Runnable thread = () -> {
      try {
        String jobName = "Communication: Daily Sync";
        env.startJobLog(JobType.EVENT, null, jobName, "Nucleus Portal");
        boolean success = true;

        List<CommunicationService> communicationServices = env.communicationServices(serviceName);

        for (CommunicationService communicationService : communicationServices) {
          try {
            // do unsubscribes first so that the CRM has the most recent data before attempting the main sync
            communicationService.syncUnsubscribes(lastSync);
            env.logJobInfo("{}: sync unsubscribes done", communicationService.name());
          } catch (Exception e) {
            env.logJobError("communication syncUnsubscribes failed for {}", communicationService.name(), e);
            env.logJobError(e.getMessage());
            success = false;
          }

          try {
            communicationService.syncContacts(lastSync);
            env.logJobInfo("{}: sync contacts done", communicationService.name());
          } catch (Exception e) {
            env.logJobError("communication syncContacts failed for {}", communicationService.name(), e);
            env.logJobError(e.getMessage());
            success = false;
          }
        }

        if (success) {
          env.endJobLog(JobStatus.DONE);
        } else {
          env.endJobLog(JobStatus.FAILED);
        }
      } catch (Exception e) {
        env.logJobError("communication syncDaily failed", e);
        env.logJobError(e.getMessage());
        env.endJobLog(JobStatus.FAILED);
      }
    };
    new Thread(thread).start();

    return Response.ok().build();
  }

  @GET
  @Path("/sync/all")
  public Response syncAll(@QueryParam("service") String serviceName, @Context HttpServletRequest request)
      throws Exception {
    Environment env = envFactory.init(request);

    Runnable thread = () -> {
      try {
        String jobName = "Communication: Full Sync";
        env.startJobLog(JobType.EVENT, null, jobName, "Nucleus Portal");
        boolean success = true;

        List<CommunicationService> communicationServices = env.communicationServices(serviceName);

        for (CommunicationService communicationService : communicationServices) {
          try {
            // do unsubscribes first so that the CRM has the most recent data before attempting the main sync
            communicationService.syncUnsubscribes(null);
            env.logJobInfo("{}: sync unsubscribes done", communicationService.name());
          } catch (Exception e) {
            env.logJobError("communication syncUnsubscribes failed for {}", communicationService.name(), e);
            env.logJobError(e.getMessage());
            success = false;
          }

          try {
            communicationService.syncContacts(null);
            env.logJobInfo("{}: sync contacts done", communicationService.name());
          } catch (Exception e) {
            env.logJobError("communication syncContacts failed for {}", communicationService.name(), e);
            env.logJobError(e.getMessage());
            success = false;
          }
        }

        if (success) {
          env.endJobLog(JobStatus.DONE);
        } else {
          env.endJobLog(JobStatus.FAILED);
        }
      } catch (Exception e) {
        env.logJobError("communication syncAll failed", e);
        env.logJobError(e.getMessage());
        env.endJobLog(JobStatus.FAILED);
      }
    };
    new Thread(thread).start();

    return Response.ok().build();
  }

  @POST
  @Path("/upsert")
  @Consumes("application/x-www-form-urlencoded")
  public Response upsertContact(
      @FormParam("contact-id") String contactId,
      @FormParam("service") String serviceName,
      @Context HttpServletRequest request
  ) throws Exception {
    Environment env = envFactory.init(request);
    Runnable thread = () -> {
      try {
        String jobName = "Communication: Single Contact";
        env.startJobLog(JobType.EVENT, null, jobName, "Nucleus Portal");

        List<CommunicationService> communicationServices = env.communicationServices(serviceName);

        for (CommunicationService communicationService : communicationServices) {
          try {
            communicationService.upsertContact(contactId);
            env.logJobInfo("{}: upsert contact done ({})", communicationService.name(), contactId);
          } catch (Exception e) {
            env.logJobError("contact upsert failed for contact: {} platform: {}", contactId, communicationService.name(), e);
            env.logJobError(e.getMessage());
          }
        }
        env.endJobLog(JobStatus.DONE);
      } catch (Exception e) {
        env.logJobError("communication upsert contact failed ({})", contactId, e);
        env.logJobError(e.getMessage());
        env.endJobLog(JobStatus.FAILED);
      }
    };
    new Thread(thread).start();

    return Response.ok().build();
  }

  // Auto archive the MC contact if they don't exist as a subscriber in the CRM. Enable only if the organization
  // ensures all contacts are first in the CRM. If they still manually upload to MC directly, keep this turned off.
  @GET
  @Path("/mass-archive")
  public Response massArchive(@QueryParam("service") String serviceName, @Context HttpServletRequest request)
      throws Exception {
    Environment env = envFactory.init(request);

    Runnable thread = () -> {
      try {
        String jobName = "Communication: Mass Archive";
        env.startJobLog(JobType.EVENT, null, jobName, "Nucleus Portal");
        boolean success = true;

        List<CommunicationService> communicationServices = env.communicationServices(serviceName);

        for (CommunicationService communicationService : communicationServices) {
          try {
            communicationService.massArchive();
            env.logJobInfo("{}: massArchive done", communicationService.name());
          } catch (Exception e) {
            env.logJobError("communication massArchive failed for {}", communicationService.name(), e);
            env.logJobError(e.getMessage());
            success = false;
          }
        }

        if (success) {
          env.endJobLog(JobStatus.DONE);
        } else {
          env.endJobLog(JobStatus.FAILED);
        }
      } catch (Exception e) {
        env.logJobError("communication massArchive failed", e);
        env.logJobError(e.getMessage());
        env.endJobLog(JobStatus.FAILED);
      }
    };
    new Thread(thread).start();

    return Response.ok().build();
  }
}
