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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TimeZone;

import static com.impactupgrade.nucleus.entity.JobStatus.ACTIVE;
import static com.impactupgrade.nucleus.entity.JobStatus.DONE;
import static com.impactupgrade.nucleus.entity.JobStatus.FAILED;

public class DBJobLoggingService extends ConsoleJobLoggingService {

  private static final Logger log = LogManager.getLogger(DBJobLoggingService.class);

  private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

  private final SessionFactory sessionFactory;

  protected Environment env;

  public DBJobLoggingService(Environment env) {
    super(env);
    this.env = env;
    this.sessionFactory = HibernateUtil.getSessionFactory();
  }

  @Override
  public void startLog(JobType jobType, String username, String jobName, String originatingPlatform, String message) {
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
    job.status = ACTIVE;

    saveOrUpdateJob(job);
  }

  @Override
  public void endLog(String message) {
    super.info(message);

    Job job = getJob(env.getJobTraceId(), false);
    endLog(job, DONE, message);
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

    message = "Please contact support@impactnucleus.com and mention Job ID [" + env.getJobTraceId() + "]. We'll dive in! Error: " + message;
    Job job = getJob(env.getJobTraceId(), false);
    endLog(job, FAILED, message);
  }

  private void endLog(Job job , JobStatus jobStatus, String message) {
    if (job == null) {
      return;
    }
    log(job.id, message);

    job.status = jobStatus;
    job.endedAt = Instant.now();
    saveOrUpdateJob(job);
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

  private Job saveOrUpdateJob(Job job) {
    try (Session session = openSession()) {
      Transaction transaction = session.beginTransaction();
      session.saveOrUpdate(job);
      transaction.commit();
    }
    return job;
  }

  @Override
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

  @Override
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
    String timezoneId = env.getConfig().timezoneId;
    if (Strings.isNullOrEmpty(timezoneId)) {
      // default to EST if not configured
      timezoneId = "EST";
    }

    return sessionFactory.withOptions()
        .jdbcTimeZone(TimeZone.getTimeZone(timezoneId))
        .openSession();
  }
}