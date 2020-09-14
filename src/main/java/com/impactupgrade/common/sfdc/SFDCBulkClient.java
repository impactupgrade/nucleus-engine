package com.impactupgrade.common.sfdc;

import com.sforce.async.AsyncApiException;
import com.sforce.async.BatchInfo;
import com.sforce.async.BatchStateEnum;
import com.sforce.async.BulkConnection;
import com.sforce.async.CSVReader;
import com.sforce.async.ContentType;
import com.sforce.async.JobInfo;
import com.sforce.async.JobStateEnum;
import com.sforce.async.OperationEnum;
import com.sforce.soap.partner.LoginResult;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wraps the SFDC Bulk API, primarily to upload and import bulk data (ex: Windfall).
 *
 * Taken and adapted from:
 * https://developer.salesforce.com/docs/atlas.en-us.api_asynch.meta/api_asynch/asynch_api_code_walkthrough.htm
 */
public class SFDCBulkClient {

  private static final Logger log = LogManager.getLogger(SFDCBulkClient.class.getName());

  // Keep it simple and build on-demand, since this is rarely used! But if caching is needed, see the
  // approach in SFDCPartnerAPIClient.
  private BulkConnection bulkConn() throws ConnectionException, AsyncApiException {
    LoginResult loginResult = new AbstractSFDCClient.LoginSFDCClient().login();

    ConnectorConfig bulkConfig = new ConnectorConfig();
    bulkConfig.setSessionId(loginResult.getSessionId());
    // The endpoint for the Bulk API service is the same as for the normal
    // Partner API, up until the /Soap/ part. From there it's '/async/versionNumber'.
    // Ex: https://lovejustice.my.salesforce.com/services/Soap/u/47.0/00DA0000000AI1h ->
    //     https://lovejustice.my.salesforce.com/services/async/47.0
    String soapEndpoint = loginResult.getServerUrl();
    String restEndpoint = soapEndpoint.replaceFirst("/Soap/[cu]/", "/async/").substring(0, soapEndpoint.lastIndexOf(".0/") + 1);
    bulkConfig.setRestEndpoint(restEndpoint);
    // ideally we'd use gzip for large files, but likely overkill right now
    bulkConfig.setCompression(false);
    bulkConfig.setTraceMessage(false);
    return new BulkConnection(bulkConfig);
  }

  /**
   * Upload the given contactFile (InputStream of a Windfall CSV) through the SFDC Bulk API, including our
   * customized spec.csv which provides CSV->SFDC field mappings.
   *
   * @param contactFile
   * @throws AsyncApiException
   * @throws ConnectionException
   * @throws IOException
   */
  public void uploadWindfallFile(File contactFile) throws AsyncApiException, ConnectionException, IOException {
    try (
        InputStream specFileInputStream = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("com/impactupgrade/sfdc/windfall/spec.csv");
        InputStream contactFileInputStream = new FileInputStream(contactFile)
    ) {
      BulkConnection bulkConn = bulkConn();

      JobInfo job = createJob("Contact", OperationEnum.update, bulkConn);

      uploadSpec(job, specFileInputStream, bulkConn);

      List<BatchInfo> fileUpload = createBatchesFromCSVFile(job, contactFileInputStream, bulkConn);
      closeJob(job.getId(), bulkConn);
      awaitCompletion(job, fileUpload, bulkConn);
      checkResults(job, fileUpload, bulkConn);
    }
  }

  private void uploadSpec(JobInfo jobInfo, InputStream specFile, BulkConnection bulkConn) throws AsyncApiException {
    log.info("uploading the spec file");
    bulkConn.createTransformationSpecFromStream(jobInfo, specFile);
  }

  /**
   * Gets the results of the operation and checks for errors.
   */
  private void checkResults(JobInfo job, List<BatchInfo> batchInfoList, BulkConnection bulkConn)
      throws AsyncApiException, IOException {
    // batchInfoList was populated when batches were created and submitted
    for (BatchInfo b : batchInfoList) {
      CSVReader rdr =
          new CSVReader(bulkConn.getBatchResultStream(job.getId(), b.getId()));
      List<String> resultHeader = rdr.nextRecord();
      int resultCols = resultHeader.size();

      List<String> row;
      while ((row = rdr.nextRecord()) != null) {
        Map<String, String> resultInfo = new HashMap<String, String>();
        for (int i = 0; i < resultCols; i++) {
          resultInfo.put(resultHeader.get(i), row.get(i));
        }
        boolean success = Boolean.parseBoolean(resultInfo.get("Success"));
        boolean created = Boolean.parseBoolean(resultInfo.get("Created"));
        String id = resultInfo.get("Id");
        String error = resultInfo.get("Error");
        if (success && created) {
          log.info("Created row with id " + id);
        } else if (!success) {
          log.error("Failed with error: " + error);
        }
      }
    }
  }

  private void closeJob(String jobId, BulkConnection bulkConn) throws AsyncApiException {
    JobInfo job = new JobInfo();
    job.setId(jobId);
    job.setState(JobStateEnum.Closed);
    bulkConn.updateJob(job);
  }

  /**
   * Wait for a job to complete by polling the Bulk API.
   *
   * @param job
   *            The job awaiting completion.
   * @param batchInfoList
   *            List of batches for this job.
   * @throws AsyncApiException
   */
  private void awaitCompletion(JobInfo job, List<BatchInfo> batchInfoList, BulkConnection bulkConn)
      throws AsyncApiException {
    long sleepTime = 0L;
    Set<String> incomplete = new HashSet<>();
    for (BatchInfo bi : batchInfoList) {
      incomplete.add(bi.getId());
    }
    while (!incomplete.isEmpty()) {
      try {
        Thread.sleep(sleepTime);
      } catch (InterruptedException e) {}
      log.info("Awaiting results..." + incomplete.size());
      sleepTime = 10000L;
      BatchInfo[] statusList = bulkConn.getBatchInfoList(job.getId()).getBatchInfo();
      for (BatchInfo b : statusList) {
        if (b.getState() == BatchStateEnum.Completed
            || b.getState() == BatchStateEnum.Failed) {
          if (incomplete.remove(b.getId())) {
            log.info("BATCH STATUS:\n" + b);
          }
        }
      }
    }
  }

  /**
   * Create a new job using the Bulk API.
   *
   * @param sObjectType
   *            The object type being loaded, such as "Account"
   * @return The JobInfo for the new job.
   * @throws AsyncApiException
   */
  private JobInfo createJob(String sObjectType, OperationEnum operation, BulkConnection bulkConn)
      throws AsyncApiException {
    JobInfo job = new JobInfo();
    job.setObject(sObjectType);
    job.setOperation(operation);
    job.setContentType(ContentType.CSV);
    job = bulkConn.createJob(job);
    log.info(job);
    return job;
  }

  /**
   * Create and upload batches using a CSV file.
   * The file into the appropriate size batch files.
   *
   * @param jobInfo
   *            Job associated with new batches
   * @param csvFile
   *            The source file for batch data
   */
  private List<BatchInfo> createBatchesFromCSVFile(JobInfo jobInfo, InputStream csvFile, BulkConnection bulkConn)
      throws IOException, AsyncApiException, ConnectionException {
    List<BatchInfo> batchInfos = new ArrayList<BatchInfo>();
    BufferedReader rdr = new BufferedReader(
        new InputStreamReader(csvFile)
    );
    // read the CSV header row
    byte[] headerBytes = (rdr.readLine() + "\n").getBytes("UTF-8");
    int headerBytesLength = headerBytes.length;
    File tmpFile = File.createTempFile("bulkAPIInsert", ".csv");

    // Split the CSV file into multiple batches
    try {
      FileOutputStream tmpOut = new FileOutputStream(tmpFile);
      int maxBytesPerBatch = 10000000; // 10 million bytes per batch
      int maxRowsPerBatch = 10000; // 10 thousand rows per batch
      int currentBytes = 0;
      int currentLines = 0;
      String nextLine;
      while ((nextLine = rdr.readLine()) != null) {
        byte[] bytes = (nextLine + "\n").getBytes("UTF-8");
        // Create a new batch when our batch size limit is reached
        if (currentBytes + bytes.length > maxBytesPerBatch
            || currentLines > maxRowsPerBatch) {
          createBatch(tmpOut, tmpFile, batchInfos, jobInfo, bulkConn);
          currentBytes = 0;
          currentLines = 0;
        }
        if (currentBytes == 0) {
          tmpOut = new FileOutputStream(tmpFile);
          tmpOut.write(headerBytes);
          currentBytes = headerBytesLength;
          currentLines = 1;
        }
        tmpOut.write(bytes);
        currentBytes += bytes.length;
        currentLines++;
      }
      // Finished processing all rows
      // Create a final batch for any remaining data
      if (currentLines > 1) {
        createBatch(tmpOut, tmpFile, batchInfos, jobInfo, bulkConn);
      }
    } finally {
      tmpFile.delete();
    }
    return batchInfos;
  }

  /**
   * Create a batch by uploading the contents of the file.
   * This closes the output stream.
   *
   * @param tmpOut
   *            The output stream used to write the CSV data for a single batch.
   * @param tmpFile
   *            The file associated with the above stream.
   * @param batchInfos
   *            The batch info for the newly created batch is added to this list.
   * @param jobInfo
   *            The JobInfo associated with the new batch.
   */
  private void createBatch(FileOutputStream tmpOut, File tmpFile, List<BatchInfo> batchInfos, JobInfo jobInfo, BulkConnection bulkConn)
      throws IOException, AsyncApiException {
    tmpOut.flush();
    tmpOut.close();
    try (FileInputStream tmpInputStream = new FileInputStream(tmpFile)) {
      BatchInfo batchInfo = bulkConn.createBatchFromStream(jobInfo, tmpInputStream);
      log.info(batchInfo);
      batchInfos.add(batchInfo);
    }
  }
}
