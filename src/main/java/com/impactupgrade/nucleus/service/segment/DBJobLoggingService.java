/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.service.segment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.impactupgrade.nucleus.dao.HibernateUtil;
import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.entity.JobFrequency;
import com.impactupgrade.nucleus.entity.JobStatus;
import com.impactupgrade.nucleus.entity.JobType;
import com.impactupgrade.nucleus.entity.Organization;
import com.impactupgrade.nucleus.environment.Environment;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

import javax.persistence.NoResultException;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;

public class DBJobLoggingService implements JobLoggingService {

  protected Environment env;
  protected SessionFactory sessionFactory;

  protected String jobTraceId;
  protected String defaultTimezoneId;

  @Override
  public String name() {
    return "db";
  }

  @Override
  public boolean isConfigured(Environment env) {
    return env.getConfig().isDatabaseConnected();
  }

  @Override
  public void init(Environment env) {
    this.env = env;
    this.sessionFactory = HibernateUtil.getSessionFactory();

    jobTraceId = env.getJobTraceId();
    defaultTimezoneId = env.getConfig().timezoneId;
  }

  @Override
  public void startLog(JobType jobType, String username, String jobName, String originatingPlatform) {
    Organization org = getOrg(env.getConfig().apiKey);
    if (org == null) {
      env.jobLoggingService("console").warn("Can not get org for nucleus api key '{}'!", env.getConfig().apiKey);
      return;
    }

    Job job = createJob(jobTraceId, jobType, username, jobName, originatingPlatform, org);
    saveOrUpdateJob(job);
  }

  @Override
  public void info(String message, Object... params) {
    insertLog(message, params);
  }

  @Override
  public void warn(String message, Object... params) {
    insertLog(message, params);
  }

  @Override
  public void error(String message, Object... params) {
    message = "[Please contact support@impactnucleus.com and mention Job ID " + jobTraceId + ". We'll dive in!] " + message;
    insertLog(message, params);
  }

  @Override
  public void endLog(JobStatus jobStatus) {
    Job job = getJob(jobTraceId, false);
    if (job != null) {
      job.status = jobStatus;
      job.endedAt = Instant.now();
      saveOrUpdateJob(job);
    }
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

    job.payload = new ObjectMapper().createObjectNode();

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

  private void insertLog(String logMessage, Object... params) {
    Job job = getJob(jobTraceId, false);
    if (job == null) return;

    logMessage = format(logMessage, params);

    try (Session session = openSession()) {
      String queryString = "INSERT INTO job_logs(job_id, log) VALUES (:jobId, :logMessage)";
      Query query = session.createNativeQuery(queryString);
      query.setParameter("jobId", job.id);
      query.setParameter("logMessage", logMessage);
      Transaction transaction = session.beginTransaction();
      query.executeUpdate();
      transaction.commit();
    }
  }

  @Override
  public List<Job> getJobs(JobType jobType) {
    Organization org = getOrg(env.getConfig().apiKey);
    if (org == null) {
      env.jobLoggingService("console").warn("Can not get org for nucleus api key '{}'!", env.getConfig().apiKey);
      return null;
    }
    return getJobs(org, jobType);
  }

  private List<Job> getJobs(Organization org, JobType jobType) {
    try (Session session = openSession()) {
      String queryString = "select j from Job j " +
          "where j.org.id = :orgId " +
          "and j.jobType = :jobType " +
          "order by j.id desc";
      Query<Job> query = session.createQuery(queryString);
      query.setParameter("orgId", org.getId());
      query.setParameter("jobType", jobType);
      return query.getResultList();
    } catch (NoResultException e) {
      return null;
    }
  }

  private Session openSession() {
    return sessionFactory.withOptions()
        .jdbcTimeZone(TimeZone.getTimeZone(defaultTimezoneId))
        .openSession();
  }
}