package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.environment.Environment;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.SessionFactory;

import javax.persistence.TemporalType;
import java.time.Instant;
import java.util.List;

public class ScheduledJobService {

  private static final Logger log = LogManager.getLogger(ScheduledJobService.class);

  private final HibernateDao<Long, Job> jobDao;
  private final Environment env;
  private final SessionFactory sessionFactory;

  public ScheduledJobService(Environment env, SessionFactory sessionFactory) {
    this.env = env;
    this.sessionFactory = sessionFactory;
    this.jobDao = new HibernateDao<>(Job.class, sessionFactory);
  }

  public void processJobSchedules() throws Exception {
    if (Strings.isNullOrEmpty(env.getConfig().apiKey)) {
      log.warn("no apiKey in the env config; skipping the scheduled job run");
      return;
    }

    List<Job> jobs = jobDao.getQueryResultList(
        "FROM Job WHERE start <= :now AND (stop IS NULL OR stop >= :now) AND status = 'ACTIVE' AND org.nucleusApiKey = :nucleusApiKey",
        query -> {
          query.setParameter("now", Instant.now(), TemporalType.TIMESTAMP);
          query.setParameter("nucleusApiKey", env.getConfig().apiKey);
        }
    );

    if (CollectionUtils.isEmpty(jobs)) {
      log.info("Could not find any active job schedules. Skipping...");
    }
    for (Job job : jobs) {
      log.info("Processing job {}...", job.id);
      switch (job.jobType) {
        case SMS_CAMPAIGN -> new SmsCampaignJobExecutor(env, sessionFactory).execute(job);
        default -> log.error("Job type {} is not yet supported!", job.jobType);
      }
    }
  }
}
