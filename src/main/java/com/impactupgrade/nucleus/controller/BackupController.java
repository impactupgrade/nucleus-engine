/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.controller;

import com.backblaze.b2.client.B2StorageClient;
import com.backblaze.b2.client.B2StorageClientFactory;
import com.backblaze.b2.client.contentSources.B2ContentSource;
import com.backblaze.b2.client.contentSources.B2ContentTypes;
import com.backblaze.b2.client.contentSources.B2FileContentSource;
import com.backblaze.b2.client.structures.B2FileVersion;
import com.backblaze.b2.client.structures.B2UploadFileRequest;
import com.backblaze.b2.client.structures.B2UploadListener;
import com.backblaze.b2.util.B2ExecutorUtils;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.environment.Environment;
import com.impactupgrade.nucleus.environment.EnvironmentFactory;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jruby.embed.PathType;
import org.jruby.embed.ScriptingContainer;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.File;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Path("/backup")
public class BackupController {

  private static final Logger log = LogManager.getLogger(BackupController.class);

  protected final EnvironmentFactory envFactory;

  public BackupController(EnvironmentFactory envFactory) {
    this.envFactory = envFactory;
  }

  /**
   * Backup SFDC using a mix of https://github.com/carojkov/salesforce-export-downloader and
   * https://github.com/Backblaze/b2-sdk-java.
   */
  @GET
  @Path("/weekly")
  public Response weekly(@Context HttpServletRequest request) {
    log.info("backing up all platforms");
    Environment env = envFactory.init(request);

    // some of the tasks (like Backblaze B2) can multi-thread and process in parallel, so create
    // an executor pool for the whole setup to run off of
    final ExecutorService executorService = Executors.newFixedThreadPool(
        10, B2ExecutorUtils.createThreadFactory("backup-executor-%02d"));

    Runnable thread = () -> {
      // SALESFORCE
      if (!Strings.isNullOrEmpty(env.getConfig().salesforce.url)) {
        try {
          // start with a clean slate and delete the dir used by the script
          FileUtils.deleteDirectory(new File("backup-salesforce"));

          log.info("downloading backup file from SFDC");

          // using jruby to kick off the ruby script -- see https://github.com/carojkov/salesforce-export-downloader
          ScriptingContainer container = new ScriptingContainer();
          container.getEnvironment().put("SFDC_USERNAME", env.getConfig().salesforce.username);
          container.getEnvironment().put("SFDC_PASSWORD", env.getConfig().salesforce.password);
          container.getEnvironment().put("SFDC_URL", env.getConfig().salesforce.url);
          container.runScriptlet(PathType.CLASSPATH, "salesforce-downloader/salesforce-backup.rb");

          // should have only downloaded a single zip, so grab the first file
          Collection<File> files = FileUtils.listFiles(new File("backup-salesforce"), null, false);

          if (files.isEmpty()) {
            log.info("no export files existed");
          } else {
            // from here on out, closely following example code from
            // https://github.com/Backblaze/b2-sdk-java/blob/master/samples/src/main/java/com/backblaze/b2/sample/B2Sample.java

            B2StorageClient client = B2StorageClientFactory
                .createDefaultFactory()
                .create(
                    env.getConfig().backblaze.publicKey,
                    env.getConfig().backblaze.secretKey,
                    "impact-upgrade-hub"
                );

            for (File file : files) {
              log.info("uploading {} to Backblaze B2", file.getName());

              B2ContentSource source = B2FileContentSource.builder(file).build();

              final B2UploadListener uploadListener = (progress) -> {
                final double percent = (100. * (progress.getBytesSoFar() / (double) progress.getLength()));
                log.info(String.format("upload progress: %3.2f, %s", percent, progress.toString()));
              };

              B2UploadFileRequest uploadRequest = B2UploadFileRequest
                  .builder(env.getConfig().backblaze.bucketId, "salesforce/" + file.getName(), B2ContentTypes.APPLICATION_OCTET, source)
                  .setListener(uploadListener)
                  .build();
              B2FileVersion upload = client.uploadLargeFile(uploadRequest, executorService);

              log.info("upload complete: {}", upload);
            }
          }
        } catch(Exception e){
          log.error("SFDC backup failed", e);
        }
      }

      // TODO: others
    };
    new Thread(thread).start();

    return Response.ok().build();
  }
}
