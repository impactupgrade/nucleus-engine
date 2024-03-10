/*
 * Copyright (c) 2021 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.impactupgrade.nucleus.environment.Environment;
import com.sforce.async.AsyncApiException;
import com.sforce.async.BatchInfo;
import com.sforce.async.BatchStateEnum;
import com.sforce.async.BulkConnection;
import com.sforce.async.CSVReader;
import com.sforce.async.ContentType;
import com.sforce.async.JobInfo;
import com.sforce.async.JobStateEnum;
import com.sforce.async.OperationEnum;
import com.sforce.async.QueryResultList;
import com.sforce.soap.partner.LoginResult;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import org.apache.commons.io.IOUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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
public class SfdcBulkClient {

  protected final Environment env;

  public SfdcBulkClient(Environment env) {
    this.env = env;
  }

  // Keep it simple and build on-demand, since this is rarely used! But if caching is needed, see the
  // approach in SFDCPartnerAPIClient.
  private BulkConnection bulkConn() throws ConnectionException, AsyncApiException {
    LoginResult loginResult = env.sfdcClient().login();

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

  public void ownerTransfer(String oldOwnerId, String newOwnerId, String object, String... whereClauses)
      throws ConnectionException, AsyncApiException, IOException {
    String query = "SELECT Id, OwnerId FROM " + object + " WHERE OwnerId='" + oldOwnerId + "'";
    for (String whereClause : whereClauses) {
      query += " AND " + whereClause;
    }

    env.logJobInfo("retrieving all {} records to transfer; bulk query: {}", object, query);

    try (
        ByteArrayInputStream queryIS = new ByteArrayInputStream(query.getBytes());
        InputStream specFileInputStream = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("sfdc/ownertransfer_spec.csv")
    ) {
      BulkConnection bulkConn = bulkConn();

      JobInfo queryJob = createJob(object, OperationEnum.query, bulkConn);
      BatchInfo queryBatchInfo = bulkConn.createBatchFromStream(queryJob, queryIS);
      closeJob(queryJob.getId(), bulkConn);
      awaitCompletion(queryJob, queryBatchInfo, bulkConn);

      QueryResultList queryResultList = bulkConn.getQueryResultList(queryJob.getId(), queryBatchInfo.getId());
      String[] queryResults = queryResultList.getResult();
      for (int i = 0; i < queryResults.length; i++) {
        env.logJobInfo("processing query result set {} of {}", i+1, queryResults.length);
        String queryResultId = queryResults[i];

        InputStream queryResultIS = null;
        InputStream newOwnerIS = null;

        try {
          // TODO: I'm assuming each one of the results contains the CSV header, and can therefore be run
          // as independent chunks!
          queryResultIS = bulkConn.getQueryResultStream(queryJob.getId(), queryBatchInfo.getId(), queryResultId);
          String queryResult = IOUtils.toString(queryResultIS, StandardCharsets.UTF_8);
          // TODO: SHOULD be ok for now, but may need to switch to a streaming setup to preserve memory for huge sets...
          queryResult = queryResult.replaceAll(oldOwnerId, newOwnerId);

          newOwnerIS = IOUtils.toInputStream(queryResult, StandardCharsets.UTF_8);

          JobInfo updateJob = createJob(object, OperationEnum.update, bulkConn);

          uploadSpec(updateJob, specFileInputStream, bulkConn);

          List<BatchInfo> fileUpload = createBatchesFromCSV(updateJob, newOwnerIS, bulkConn);
          closeJob(updateJob.getId(), bulkConn);
          awaitCompletion(updateJob, fileUpload, bulkConn);
          checkUploadResults(updateJob, fileUpload, bulkConn);
        } finally {
          if (newOwnerIS != null) {
            newOwnerIS.close();
          }
          if (queryResultIS != null) {
            queryResultIS.close();
          }
        }
      }
    }
  }

  /**
   * Upload the given contactFile (InputStream of a Windfall CSV) through the SFDC Bulk API, including our
   * customized iwave_spec.csv which provides CSV->SFDC field mappings.
   *
   * @param contactFileInputStream
   * @throws AsyncApiException
   * @throws ConnectionException
   * @throws IOException
   */
  public void uploadWindfallFile(InputStream contactFileInputStream) throws AsyncApiException, ConnectionException, IOException {
    try (
        InputStream specFileInputStream = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("sfdc/windfall_spec.csv")
    ) {
      BulkConnection bulkConn = bulkConn();

      JobInfo job = createJob("Contact", OperationEnum.update, bulkConn);

      uploadSpec(job, specFileInputStream, bulkConn);

      List<BatchInfo> fileUpload = createBatchesFromCSV(job, contactFileInputStream, bulkConn);
      closeJob(job.getId(), bulkConn);
      awaitCompletion(job, fileUpload, bulkConn);
      checkUploadResults(job, fileUpload, bulkConn);
    }
  }

  public void uploadIWaveFile(File iwaveFile) throws AsyncApiException, ConnectionException, IOException {
    try (
        InputStream specFileInputStream = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("sfdc/iwave_spec.csv");
        InputStream iwaveFileInputStream = new FileInputStream(iwaveFile)
    ) {
      BulkConnection bulkConn = bulkConn();

      JobInfo job = createJob("Contact", OperationEnum.update, bulkConn);

      uploadSpec(job, specFileInputStream, bulkConn);

      List<BatchInfo> fileUpload = createBatchesFromCSV(job, iwaveFileInputStream, bulkConn);
      closeJob(job.getId(), bulkConn);
      awaitCompletion(job, fileUpload, bulkConn);
      checkUploadResults(job, fileUpload, bulkConn);
    }
  }

  private void uploadSpec(JobInfo jobInfo, InputStream specFile, BulkConnection bulkConn) throws AsyncApiException {
    env.logJobInfo("uploading the spec file");
    bulkConn.createTransformationSpecFromStream(jobInfo, specFile);
  }

  /**
   * Gets the results of the operation and checks for errors.
   */
  private void checkUploadResults(JobInfo job, List<BatchInfo> batchInfoList, BulkConnection bulkConn)
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
          env.logJobInfo("Created row with id " + id);
        } else if (!success) {
          env.logJobError("Failed with error: " + error);
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

  private void awaitCompletion(JobInfo job, BatchInfo batchInfo, BulkConnection bulkConn) throws AsyncApiException {
    awaitCompletion(job, Collections.singletonList(batchInfo), bulkConn);
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
      env.logJobInfo("Awaiting results..." + incomplete.size());
      sleepTime = 10000L;
      BatchInfo[] statusList = bulkConn.getBatchInfoList(job.getId()).getBatchInfo();
      for (BatchInfo b : statusList) {
        if (b.getState() == BatchStateEnum.Completed
            || b.getState() == BatchStateEnum.Failed) {
          if (incomplete.remove(b.getId())) {
            env.logJobInfo("BATCH STATUS:\n" + b);
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
    env.logJobInfo(job.toString());
    return job;
  }

  /**
   * Create and upload batches using a CSV file.
   * The file into the appropriate size batch files.
   *
   * @param jobInfo
   *            Job associated with new batches
   * @param csv
   *            The source file for batch data
   */
  private List<BatchInfo> createBatchesFromCSV(JobInfo jobInfo, InputStream csv, BulkConnection bulkConn)
      throws IOException, AsyncApiException {
    List<BatchInfo> batchInfos = new ArrayList<BatchInfo>();
    BufferedReader rdr = new BufferedReader(
        new InputStreamReader(csv)
    );
    // read the CSV header row
    byte[] headerBytes = (rdr.readLine() + "\n").getBytes("UTF-8");
    int headerBytesLength = headerBytes.length;
    File tmpFile = File.createTempFile("bulkAPIUpdate", ".csv");

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
      env.logJobInfo(batchInfo.toString());
      batchInfos.add(batchInfo);
    }
  }

  public static void main(String[] args) throws ConnectionException, InterruptedException, AsyncApiException, IOException {
//    new SfdcBulkClient().ownerTransfer("005f40000027xCZAAY", "005f4000000s4r7AAA", "Account");
//    new SfdcBulkClient().ownerTransfer("005f40000027xCZAAY", "005f4000000s4r7AAA", "Contact");
//    new SfdcBulkClient().ownerTransfer("005f40000027xCZAAY", "005f4000000s4r7AAA", "npe03__Recurring_Donation__c", "npe03__Open_Ended_Status__c='Open'");
//    new SfdcBulkClient().ownerTransfer("005f40000027xCZAAY", "005f4000000s4r7AAA", "Opportunity", "StageName='Pledged'");
  }
}
