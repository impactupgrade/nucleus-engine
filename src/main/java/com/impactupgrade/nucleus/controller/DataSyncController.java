package com.impactupgrade.nucleus.controller;

import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import com.impactupgrade.nucleus.service.segment.DataSyncService;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.util.Calendar;
import java.util.List;

@Path("/data-sync")
public class DataSyncController {

  protected final EnvironmentFactory environmentFactory;

  public DataSyncController(EnvironmentFactory environmentFactory) {
    this.environmentFactory = environmentFactory;
  }

  @GET
  @Path("/contacts/daily")
  public Response syncContactsDaily(@QueryParam("syncDays") Integer syncDays, @QueryParam("service") String serviceName,
      @Context HttpServletRequest request) throws Exception {
    Environment env = environmentFactory.init(request);

    Calendar lastSync = Calendar.getInstance();
    // run daily, but setting this high to catch previous misses
    if (syncDays == null || syncDays <= 0) {
      syncDays = 3;
    }
    lastSync.add(Calendar.DATE, -syncDays);

    Runnable thread = () -> {
      try {
        String jobName = "Contacts Sync: Daily";
        env.startJobLog(JobType.EVENT, null, jobName, "Nucleus Portal");
        boolean success = true;

        List<DataSyncService> dataSyncServices = env.dataSyncServices(serviceName);

        for (DataSyncService dataSyncService : dataSyncServices) {
          try {
            dataSyncService.syncContacts(lastSync);
            env.logJobInfo("{}: sync contacts done", dataSyncService.name());
          } catch (Exception e) {
            env.logJobError("sync contacts failed for {}", dataSyncService.name(), e);
            env.logJobError(e.getMessage());
            success = false;
          }
        }

        env.endJobLog(success ? JobStatus.DONE : JobStatus.FAILED);

      } catch (Exception e) {
        env.logJobError("sync contacts failed!", e);
        env.logJobError(e.getMessage());
        env.endJobLog(JobStatus.FAILED);
      }
    };
    new Thread(thread).start();

    return Response.ok().build();
  }

  @GET
  @Path("/transactions/daily")
  public Response syncTransactionsDaily(@QueryParam("syncDays") Integer syncDays,
      @QueryParam("service") String serviceName, @Context HttpServletRequest request) throws Exception {
    Environment env = environmentFactory.init(request);

    Calendar lastSync = Calendar.getInstance();
    // run daily, but setting this high to catch previous misses
    if (syncDays == null || syncDays <= 0) {
      syncDays = 3;
    }
    lastSync.add(Calendar.DATE, -syncDays);

    Runnable thread = () -> {
      try {
        String jobName = "Transactions Sync: Daily";
        env.startJobLog(JobType.EVENT, null, jobName, "Nucleus Portal");
        boolean success = true;

        List<DataSyncService> dataSyncServices = env.dataSyncServices(serviceName);

        for (DataSyncService dataSyncService : dataSyncServices) {
          try {
            dataSyncService.syncTransactions(lastSync);
            env.logJobInfo("{}: sync transactions done", dataSyncService.name());
          } catch (Exception e) {
            env.logJobError("sync transactions failed for {}", dataSyncService.name(), e);
            env.logJobError(e.getMessage());
            success = false;
          }
        }

        env.endJobLog(success ? JobStatus.DONE : JobStatus.FAILED);

      } catch (Exception e) {
        env.logJobError("sync transactions failed!", e);
        env.logJobError(e.getMessage());
        env.endJobLog(JobStatus.FAILED);
      }
    };
    new Thread(thread).start();

    return Response.ok().build();
  }
}
