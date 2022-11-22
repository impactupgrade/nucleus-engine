package com.impactupgrade.nucleus.service.logic;

import com.google.common.base.Strings;
import com.impactupgrade.nucleus.dao.HibernateDao;
import com.impactupgrade.nucleus.entity.Job;
import com.impactupgrade.nucleus.environment.Environment;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.persistence.TemporalType;
import java.time.Instant;
import java.util.List;

public class ScheduledJobService {

  private static final Logger log = LogManager.getLogger(ScheduledJobService.class);

  private final HibernateDao<Long, Job> jobDao;
  private final Environment env;

  public ScheduledJobService(Environment env) {
    this.env = env;
    this.jobDao = new HibernateDao<>(Job.class);
  }

  // May seem a little odd to require the now argument, rather than simply doing Instant.now() inline. However:
  // 1) Helps with testing.
  // 2) May be future situations where we need granular control...
  public void processJobSchedules(Instant now) throws Exception {
    if (Strings.isNullOrEmpty(env.getConfig().apiKey)) {
      log.info("no apiKey in the env config; skipping the scheduled job run");
      return;
    }

    List<Job> jobs = jobDao.getQueryResultList(
        "FROM Job WHERE scheduleStart <= :now AND (scheduleEnd IS NULL OR scheduleEnd >= :now) AND status = 'ACTIVE' AND org.nucleusApiKey = :nucleusApiKey",
        query -> {
          query.setParameter("now", now, TemporalType.TIMESTAMP);
          query.setParameter("nucleusApiKey", env.getConfig().apiKey);
        },
        entities -> {
          if (!entities.isEmpty()) {
            // initialize the jobProgresses subselect
            entities.get(0).jobProgresses.size();
          }
        }
    );

    if (CollectionUtils.isEmpty(jobs)) {
      log.info("Could not find any active job schedules. Skipping...");
    }
    for (Job job : jobs) {
      log.info("Processing job {}...", job.id);
      switch (job.jobType) {
        case SMS_CAMPAIGN -> new SmsCampaignJobExecutor(env).execute(job, now);
        default -> log.error("Job type {} is not yet supported!", job.jobType);
      }
    }
  }
}
