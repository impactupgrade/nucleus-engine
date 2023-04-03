package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.entity.JobFrequency;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.entity.Organization;
import com.impactupgrade.nucleus.environment.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

import javax.persistence.NoResultException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.impactupgrade.nucleus.entity.JobStatus.DONE;
import static com.impactupgrade.nucleus.entity.JobStatus.FAILED;

public class JobProgressLoggingService {

  private static final Logger log = LogManager.getLogger(JobProgressLoggingService.class);

  private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

  protected Environment env;

  public JobProgressLoggingService(Environment env) {
    this.env = env;
  }

  public void logProgress(JobType jobType, String username, String jobName, String originatingPlatform, JobStatus jobStatus, String message) {
    Session session = env.getSession();
    if (session == null) {
      log.warn("Session not provided. Skipping job progress log...");
      return;
    }
    String jobTraceId = env.getJobTraceId();
    if (Strings.isNullOrEmpty(jobTraceId)) {
      log.warn("Job trace id not provided. Skipping job progress log...");
      return;
    }
    Job job = getOrCreateJob(session, jobTraceId, jobType, username, jobName, originatingPlatform);
    if (job == null) {
      log.warn("Job not provided. Skipping job progress log... ");
      return;
    }

    DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT);
    LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
    String timestamp = now.format(dateTimeFormatter);

    job.logs.add(timestamp + " : " + message);
    if (jobStatus != null) {
      job.status = jobStatus;
      if (jobStatus == DONE || jobStatus == FAILED) {
        job.endedAt = now.toInstant(ZoneOffset.UTC);
      }
    }

    saveJob(session, job);
  }

  public List<Job> getJobs(JobType jobType) {
    Session session = env.getSession();
    if (session == null) {
      log.info("Session not provided. Can not get jobs!");
      return null;
    }
    String nucleusApikey = getApiKey();
    Organization org = getOrg(session, nucleusApikey);
    if (org == null) {
      log.warn("Can not get org for nucleus api key '{}'!", nucleusApikey);
      return null;
    }
    return getJobs(session, org, jobType);
  }

  public Job getJob(String traceId) {
    Session session = env.getSession();
    if (session == null) {
      log.info("Session not provided. Can not get jobs!");
      return null;
    }
    String nucleusApikey = getApiKey();
    Organization org = getOrg(session, nucleusApikey);
    if (org == null) {
      log.warn("Can not get org for nucleus api key '{}'!", nucleusApikey);
      return null;
    }
    return getJob(session, traceId);
  }

  private String getApiKey() {
    String apiKey = env.getHeaders().get("Nucleus-Api-Key");
    if (Strings.isNullOrEmpty(apiKey)) {
      apiKey = env.getConfig().apiKey;
    }
    return apiKey;
  }

  private Job getOrCreateJob(Session session, String jobTraceId, JobType jobType, String username, String jobName, String originatingPlatform) {
    Job job = getJob(session, jobTraceId);
    if (job == null) {
      String nucleusApikey = env.getHeaders().get("Nucleus-Api-Key");
      Organization org = getOrg(session, nucleusApikey);
      if (org == null) {
        log.warn("Can not get org for nucleus api key '{}'!", nucleusApikey);
        return null;
      }
      job = createJob(session, jobTraceId, jobType, username, jobName, originatingPlatform, org);
    }
    return job;
  }

  private Job getJob(Session session, String jobTraceId) {
    try {
      String queryString = "select j from Job j " +
          "where j.traceId like '%" + jobTraceId + "%'";
      Query<Job> query = session.createQuery(queryString);
      return query.getSingleResult();
    } catch (NoResultException e) {
      return null;
    }
  }

  private Organization getOrg(Session session, String nucleusApiKey) {
    try {
      String queryString = "select o from Organization o " +
          "where o.nucleusApiKey = :nucleusApiKey";
      Query<Organization> query = session.createQuery(queryString);
      query.setParameter("nucleusApiKey", nucleusApiKey);
      return query.getSingleResult();
    } catch (NoResultException e) {
      return null;
    }
  }

  private Job createJob(Session session, String jobTraceId, JobType jobType, String username, String jobName, String originatingPlatform, Organization org) {
    Job job = new Job();
    job.traceId = jobTraceId;
    job.jobType = jobType;
    job.startedBy = username;
    job.jobName = jobName;
    job.originatingPlatform = originatingPlatform;

    JsonNode payload = new ObjectMapper().createObjectNode();
    job.payload = payload;

    job.status = JobStatus.ACTIVE;
    job.scheduleFrequency = JobFrequency.ONETIME;
    job.scheduleInterval = 1;
    Instant now = Instant.now();
    job.scheduleStart = now; // one-time run
    job.scheduleEnd = now;
    job.org = org;
    job.startedAt = now;

    saveJob(session, job);

    return job;
  }

  private Job saveJob(Session session, Job job) {
    Transaction transaction = session.beginTransaction();
    session.save(job);
    transaction.commit();
    return job;
  }

  private List<Job> getJobs(Session session, Organization org, JobType jobType) {
    try {
      String queryString = "select j from Job j " +
          "where j.org.id = :orgId " +
          "and j.jobType = :jobType";
      Query<Job> query = session.createQuery(queryString);
      query.setParameter("orgId", org.getId());
      query.setParameter("jobType", jobType);
      return query.getResultList();
    } catch (NoResultException e) {
      return null;
    }
  }
}