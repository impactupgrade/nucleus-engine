package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.impactupgrade.nucleus.dao.HibernateUtil;
import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.entity.JobFrequency;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.entity.Organization;
import com.impactupgrade.nucleus.environment.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

import javax.persistence.NoResultException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TimeZone;

import static com.impactupgrade.nucleus.entity.JobStatus.DONE;
import static com.impactupgrade.nucleus.entity.JobStatus.FAILED;

public class JobProgressLoggingService extends ConsoleLoggingService {

  private static final Logger log = LogManager.getLogger(JobProgressLoggingService.class);

  private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

  protected Environment env;

  public JobProgressLoggingService(Environment env) {
    super(env);
    this.env = env;
  }

  @Override
  public void startLog(JobType jobType, String username, String jobName, String originatingPlatform, JobStatus jobStatus, String message) {
    super.info(message);

    String nucleusApikey = getApiKey();
    Organization org = getOrg(nucleusApikey);
    if (org == null) {
      log.debug("Can not get org for nucleus api key '{}'!", nucleusApikey);
      return;
    }
    Job job = createJob(env.getJobTraceId(), jobType, username, jobName, originatingPlatform, org);

    DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT);
    LocalDateTime now = LocalDateTime.now(ZoneId.of(env.getConfig().timezoneId));
    String timestamp = now.format(dateTimeFormatter);

    job.logs.add(timestamp + " : " + message);
    if (jobStatus != null) {
      job.status = jobStatus;
      if (jobStatus == DONE || jobStatus == FAILED) {
        job.endedAt = now.toInstant(ZoneOffset.UTC);
      }
    }

    saveJob(job);
  }

  @Override
  public void endLog(String message) {
    super.info(message);

    Job job = getJob(env.getJobTraceId(), false);
    if (job != null) {
      job.status = DONE;
      log(job.id, message);
    }
  }

  @Override
  public void info(String message) {
    super.info(message);

    Job job = getJob(env.getJobTraceId(), false);
    if (job != null) {
      log(job.id, message);
    }
  }

  @Override
  public void error(String message) {
    super.error(message);

    Job job = getJob(env.getJobTraceId(), false);
    if (job != null) {
      job.status = FAILED;
      log(job.id, message);
    }
  }

  private String getApiKey() {
    String apiKey = env.getHeaders().get("Nucleus-Api-Key");
    if (Strings.isNullOrEmpty(apiKey)) {
      apiKey = env.getConfig().apiKey;
    }
    return apiKey;
  }

  private Organization getOrg(String nucleusApiKey) {
    try (Session session = openSession()) {
      String queryString = "select o from Organization o " +
          "where o.nucleusApiKey = :nucleusApiKey";
      Query<Organization> query = session.createQuery(queryString);
      query.setParameter("nucleusApiKey", nucleusApiKey);
      return query.getSingleResult();
    } catch (NoResultException e) {
      return null;
    }
  }

  private Job createJob(String jobTraceId, JobType jobType, String username, String jobName, String originatingPlatform, Organization org) {
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
    job.scheduleTz = "UTC";

    return job;
  }

  private Job saveJob(Job job) {
    try (Session session = openSession()) {
      Transaction transaction = session.beginTransaction();
      session.save(job);
      transaction.commit();
    }
    return job;
  }

  public Job getJob(String jobTraceId) {
    return getJob(jobTraceId, true);
  }

  private Job getJob(String jobTraceId, boolean fetchLogs) {
    try (Session session = openSession()) {
      String queryString = "select j from Job j " +
          (fetchLogs ? "left join fetch j.logs l " : "") +
          "where j.traceId like '%" + jobTraceId + "%'";
      Query<Job> query = session.createQuery(queryString);
      return query.getSingleResult();
    } catch (NoResultException e) {
      return null;
    }
  }

  private void log(Long jobId, String logMessage) {
    try (Session session = openSession()) {
      String queryString = "INSERT INTO job_logs(job_id, log) VALUES (:jobId, :logMessage)";
      Query query = session.createNativeQuery(queryString);
      query.setParameter("jobId", jobId);
      query.setParameter("logMessage", logMessage);
      Transaction transaction = session.beginTransaction();
      query.executeUpdate();
      transaction.commit();
    }
  }

  public List<Job> getJobs(JobType jobType) {
    String nucleusApikey = getApiKey();
    Organization org = getOrg(nucleusApikey);
    if (org == null) {
      log.debug("Can not get org for nucleus api key '{}'!", nucleusApikey);
      return null;
    }
    return getJobs(org, jobType);
  }

  private List<Job> getJobs(Organization org, JobType jobType) {
    try (Session session = openSession()) {
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

  private Session openSession() {
    SessionFactory sessionFactory = HibernateUtil.getSessionFactory();
    if (sessionFactory != null) {
      String timezoneId = env.getConfig().timezoneId;
      if (Strings.isNullOrEmpty(timezoneId)) {
        // default to EST if not configured
        timezoneId = "EST";
      }

      return sessionFactory.withOptions()
          .jdbcTimeZone(TimeZone.getTimeZone(timezoneId))
          .openSession();
    }
    return null;
  }
}