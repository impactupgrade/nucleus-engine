package com.impactupgrade.common.backup;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

@Path("/backup")
public class BackupService {

    private static final Logger log = LogManager.getLogger(BackupService.class);

  private static final String SFDC_USERNAME = System.getenv("SFDC.USERNAME");
  private static final String SFDC_PASSWORD = System.getenv("SFDC.PASSWORD");
  private static final String SFDC_URL = System.getenv("SFDC.URL");

  @GET
  @Path("/weekly")
  public Response weekly() {
    log.info("backing up all platforms");

    BackupClient.backupSFDC(SFDC_USERNAME, SFDC_PASSWORD, SFDC_URL);

    return Response.ok().build();
  }
}
