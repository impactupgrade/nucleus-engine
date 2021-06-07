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
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jruby.embed.PathType;
import org.jruby.embed.ScriptingContainer;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import java.io.File;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Path("/backup")
public class BackupController {

  private static final Logger log = LogManager.getLogger(BackupController.class);

  // TODO: Config must shift to env.json

  private static final String SFDC_USERNAME = System.getenv("SFDC_USERNAME");
  private static final String SFDC_PASSWORD = System.getenv("SFDC_PASSWORD");
  private static final String SFDC_URL = System.getenv("SFDC_URL");

  private static final String BACKBLAZE_KEYID = System.getenv("BACKBLAZE_KEYID");
  private static final String BACKBLAZE_KEY = System.getenv("BACKBLAZE_KEY");
  private static final String BACKBLAZE_BUCKETID = System.getenv("BACKBLAZE_BUCKETID");

  /**
   * Backup SFDC using a mix of https://github.com/carojkov/salesforce-export-downloader and
   * https://github.com/Backblaze/b2-sdk-java.
   */
  @GET
  @Path("/weekly")
  public Response weekly() {
    log.info("backing up all platforms");

    // some of the tasks (like Backblaze B2) can multi-thread and process in parallel, so create
    // an executor pool for the whole setup to run off of
    final ExecutorService executorService = Executors.newFixedThreadPool(
        10, B2ExecutorUtils.createThreadFactory("backup-executor-%02d"));

    Runnable thread = () -> {
      try {
        // start with a clean slate and delete the dir used by the script
        FileUtils.deleteDirectory(new File("backup-salesforce"));

        log.info("downloading backup file from SFDC");

        // using jruby to kick off the ruby script -- see https://github.com/carojkov/salesforce-export-downloader
        ScriptingContainer container = new ScriptingContainer();
        container.getEnvironment().put("SFDC_USERNAME", SFDC_USERNAME);
        container.getEnvironment().put("SFDC_PASSWORD", SFDC_PASSWORD);
        container.getEnvironment().put("SFDC_URL", SFDC_URL);
        container.runScriptlet(PathType.CLASSPATH, "salesforce-export-downloader/salesforce-backup.rb");

        // should have only downloaded a single zip, so grab the first file
        Collection<File> files = FileUtils.listFiles(new File("backup-salesforce"), null, false);

        // from here on out, closely following example code from
        // https://github.com/Backblaze/b2-sdk-java/blob/master/samples/src/main/java/com/backblaze/b2/sample/B2Sample.java

        B2StorageClient client = B2StorageClientFactory
            .createDefaultFactory()
            .create(BACKBLAZE_KEYID, BACKBLAZE_KEY, "impact-upgrade-hub");

        for (File file : files) {
          log.info("uploading {} to Backblaze B2", file.getName());

          B2ContentSource source = B2FileContentSource.builder(file).build();

          final B2UploadListener uploadListener = (progress) -> {
            final double percent = (100. * (progress.getBytesSoFar() / (double) progress.getLength()));
            log.info(String.format("upload progress: %3.2f, %s", percent, progress.toString()));
          };

          B2UploadFileRequest request = B2UploadFileRequest
              .builder(BACKBLAZE_BUCKETID, "salesforce/" + file.getName(), B2ContentTypes.APPLICATION_OCTET, source)
              .setListener(uploadListener)
              .build();
          B2FileVersion upload = client.uploadLargeFile(request, executorService);

          log.info("upload complete: {}", upload);
        }
      } catch (Exception e) {
        log.error("SFDC backup failed", e);
      }
    };
    new Thread(thread).start();

    return Response.ok().build();
  }
}
